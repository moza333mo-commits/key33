package com.linguakey.keyboard.latin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.linguakey.keyboard.R;
import com.linguakey.keyboard.compat.PreferenceManagerCompat;
import com.linguakey.keyboard.latin.common.Constants;
import com.linguakey.keyboard.latin.settings.Settings;
import com.linguakey.keyboard.latin.utils.DialogUtils;

import java.util.List;

/**
 * LinguaKey Minimalist Smart Header Strip Controller (Gboard-Matched Top Bar):
 *
 * Top bar contains ONLY 2 buttons + borderless center original-source text area:
 * 1. Start Button (mMsgTranslateModeBtn): One-Tap Full-Screen In-Place OCR Translation (Google Lens / Gemini style).
 *    - Single tap captures the screen above the keyboard, runs bundled ML Kit OCR, and translates all messages in-place.
 *    - Long press toggles the collapsible Arabic tools & language drawer.
 * 2. Center Area (mSourceBufferInput): Completely borderless (background = null, hint = ""),
 *    hidden when Live Typing Translation is OFF; holds the user's original typed text when ON,
 *    auto-clears after the user's configured delay (default 1.5s) after typing stops, and clears
 *    immediately when a message is sent.
 * 3. End Button (mTranslateToggleBtn): Live Typing Translation Toggle (A文).
 *    - Turns Google Blue (#0D6EFD) when ON, dark gray (#3C4043) when OFF.
 */
public final class LinguaKeySmartBarController implements LiveTranslationEngine.StatusListener {
    private final Context mContext;
    private final View mInputView;
    private final LiveTranslationEngine mLiveEngine;
    private final LiveTranslationEngine.InputConnectionProvider mInputConnectionProvider;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public interface QuickToolsHost {
        void onQuickClipboardClick();
        void onQuickEmojiClick();
        void onQuickLastPhotoClick();
        void onQuickSettingsClick();
    }

    private View mLiveBarContainer;
    private View mQuickToolsStrip;
    private ImageView mQuickClipboardBtn;
    private ImageView mQuickEmojiBtn;
    private ImageView mQuickLastPhotoBtn;
    private ImageView mQuickSettingsBtn;
    private QuickToolsHost mQuickToolsHost;
    private ImageView mMsgTranslateModeBtn;
    private EditText mSourceBufferInput;
    private ProgressBar mLiveProgress;
    private ImageView mTranslateToggleBtn;

    private View mToolsDrawer;
    private TextView mSourceLangChip;
    private TextView mSwapDirectionBtn;
    private TextView mTargetLangChip;
    private TextView mPolishChip;
    private TextView mUndoChip;

    private HorizontalScrollView mAlternativesScroll;
    private LinearLayout mAlternativesContainer;

    private LinguaKeyMessageTouchService mMsgTouchService;

    private boolean mUpdatingBufferProgrammatically = false;
    private boolean mIsToolsDrawerOpen = false;
    private boolean mIsMsgSelectModeActive = false;
    private boolean mShowingInlineMsgPreview = false;
    private Boolean mLastStyledMsgActive = null;
    private Boolean mLastStyledTranslateActive = null;
    private Runnable mInlinePreviewResetRunnable;

    public LinguaKeySmartBarController(
            final Context context,
            final View inputView,
            final LiveTranslationEngine liveEngine,
            final LiveTranslationEngine.InputConnectionProvider icProvider
    ) {
        mContext = context;
        mInputView = inputView;
        mLiveEngine = liveEngine;
        mInputConnectionProvider = icProvider;
        initViews();
    }

    public void setMessageTouchService(final LinguaKeyMessageTouchService service) {
        mMsgTouchService = service;
    }

    public void setQuickToolsHost(final QuickToolsHost host) {
        mQuickToolsHost = host;
    }

    private void initViews() {
        if (mInputView == null) return;
        mLiveBarContainer = mInputView.findViewById(R.id.linguakey_live_bar);
        mQuickToolsStrip = mInputView.findViewById(R.id.lk_quick_tools_strip);
        mQuickClipboardBtn = mInputView.findViewById(R.id.lk_quick_clipboard_btn);
        mQuickEmojiBtn = mInputView.findViewById(R.id.lk_quick_emoji_btn);
        mQuickLastPhotoBtn = mInputView.findViewById(R.id.lk_quick_last_photo_btn);
        mQuickSettingsBtn = mInputView.findViewById(R.id.lk_quick_settings_btn);
        mMsgTranslateModeBtn = mInputView.findViewById(R.id.lk_msg_translate_mode_btn);
        mSourceBufferInput = mInputView.findViewById(R.id.lk_source_buffer_input);
        mLiveProgress = mInputView.findViewById(R.id.lk_live_progress);
        mTranslateToggleBtn = mInputView.findViewById(R.id.lk_translate_toggle_btn);

        mToolsDrawer = mInputView.findViewById(R.id.lk_tools_drawer);
        mSourceLangChip = mInputView.findViewById(R.id.lk_source_lang_chip);
        mSwapDirectionBtn = mInputView.findViewById(R.id.lk_swap_direction_btn);
        mTargetLangChip = mInputView.findViewById(R.id.lk_target_lang_chip);
        mPolishChip = mInputView.findViewById(R.id.lk_polish_chip);
        mUndoChip = mInputView.findViewById(R.id.lk_undo_chip);

        mAlternativesScroll = mInputView.findViewById(R.id.lk_alternatives_scroll);
        mAlternativesContainer = mInputView.findViewById(R.id.lk_alternatives_container);

        if (mSourceBufferInput != null) {
            mSourceBufferInput.setShowSoftInputOnFocus(false);
            mSourceBufferInput.setLongClickable(false);
            mSourceBufferInput.setTextIsSelectable(false);
            mSourceBufferInput.setBackground(null);
            mSourceBufferInput.setHint("");

            mSourceBufferInput.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (!isLiveTranslationActive()) {
                        return true;
                    }
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        cancelInlineMsgPreviewImmediate();
                        v.requestFocus();
                        int offset = mSourceBufferInput.getOffsetForPosition(event.getX(), event.getY());
                        if (offset >= 0 && offset <= mSourceBufferInput.getText().length()) {
                            mSourceBufferInput.setSelection(offset);
                        }
                        return true;
                    }
                    return false;
                }
            });

            mSourceBufferInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable s) {
                    if (mUpdatingBufferProgrammatically || mShowingInlineMsgPreview) return;
                    if (!isLiveTranslationActive()) return;
                    String text = s != null ? s.toString() : "";
                    updateSourceBufferTypography(text);
                    mLiveEngine.onSourceBufferChanged(text, mInputConnectionProvider, false);
                }
            });
        }

        if (mQuickClipboardBtn != null) {
            mQuickClipboardBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    vibrateTick();
                    if (mQuickToolsHost != null) mQuickToolsHost.onQuickClipboardClick();
                }
            });
        }
        if (mQuickEmojiBtn != null) {
            mQuickEmojiBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    vibrateTick();
                    if (mQuickToolsHost != null) mQuickToolsHost.onQuickEmojiClick();
                }
            });
        }
        if (mQuickLastPhotoBtn != null) {
            mQuickLastPhotoBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    vibrateTick();
                    if (mQuickToolsHost != null) mQuickToolsHost.onQuickLastPhotoClick();
                }
            });
        }
        if (mQuickSettingsBtn != null) {
            mQuickSettingsBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    vibrateTick();
                    if (mQuickToolsHost != null) mQuickToolsHost.onQuickSettingsClick();
                    else toggleToolsDrawer();
                }
            });
        }

        // 1. One-Tap Full-Screen In-Place OCR Translation Button (Start Side)
        if (mMsgTranslateModeBtn != null) {
            mMsgTranslateModeBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    vibrateTick();
                    onOneTapFullScreenTranslateClicked();
                }
            });
            // Long-press on the icon opens the collapsible Arabic Tools & Languages Drawer
            mMsgTranslateModeBtn.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    vibrateTick();
                    toggleToolsDrawer();
                    return true;
                }
            });
        }

        // 2. Live Typing Translation Toggle Button (End Side - A文)
        if (mTranslateToggleBtn != null) {
            mTranslateToggleBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    vibrateTick();
                    cancelInlineMsgPreviewImmediate();
                    boolean nowEnabled = mLiveEngine.toggleAutoTranslate(mInputConnectionProvider);
                    updateSourceBufferVisibility(nowEnabled);
                    styleTranslateToggleButton(nowEnabled);
                }
            });
            mTranslateToggleBtn.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    vibrateTick();
                    toggleToolsDrawer();
                    return true;
                }
            });
        }

        if (mSourceLangChip != null) {
            mSourceLangChip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    vibrateTick();
                    showLanguagePickerDialog(true);
                }
            });
        }

        if (mTargetLangChip != null) {
            mTargetLangChip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    vibrateTick();
                    showLanguagePickerDialog(false);
                }
            });
        }

        if (mSwapDirectionBtn != null) {
            mSwapDirectionBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    vibrateTick();
                    mLiveEngine.swapDirection(mInputConnectionProvider);
                }
            });
        }

        if (mPolishChip != null) {
            mPolishChip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    vibrateTick();
                    String sourceText = getSourceText();
                    if (!sourceText.trim().isEmpty()) {
                        mLiveEngine.triggerLiveTranslationNow(sourceText, mInputConnectionProvider, true);
                    }
                }
            });
        }

        if (mUndoChip != null) {
            mUndoChip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    vibrateTick();
                    clearSourceAndTranslatedText();
                }
            });
        }

        applyGboardTheme();
        mLiveEngine.setStatusListener(this);
    }

    /**
     * One-Tap Full-Screen In-Place OCR Translation (Google Lens / Gemini style):
     * - Single tap captures the screen above the keyboard, runs bundled ML Kit OCR, translates all
     *   visible foreign text blocks into Arabic, and renders them in-place at their exact screen coordinates.
     * - Automatically stays visible based on character count and vanishes on its own or when tapped again.
     */
    private void onOneTapFullScreenTranslateClicked() {
        if (mMsgTouchService == null) return;

        if (mIsMsgSelectModeActive || mMsgTouchService.isOverlayShowing()) {
            mIsMsgSelectModeActive = false;
            mMsgTouchService.setSelectionMode(false);
            mMsgTouchService.dismissTranslationPopup();
            cancelInlineMsgPreviewImmediate();
            styleMsgTranslateModeButton(false);
            return;
        }

        mIsMsgSelectModeActive = true;
        styleMsgTranslateModeButton(true);

        boolean triggered = mMsgTouchService.triggerOneTapFullScreenTranslate(
                mInputView,
                new LinguaKeyScreenTranslateService.ScreenOverlayCallback() {
                    @Override
                    public void onOverlayStateChanged(final boolean active) {
                        mMainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                mIsMsgSelectModeActive = active;
                                styleMsgTranslateModeButton(active);
                                if (mInputView != null) {
                                    mInputView.requestLayout();
                                    mInputView.invalidate();
                                }
                            }
                        });
                    }

                    @Override
                    public void onLastMessageTranslated(final String arabicText) {
                        // Rendered directly in-place on the screen overlay above the keyboard!
                    }
                }
        );

        if (!triggered) {
            mIsMsgSelectModeActive = false;
            styleMsgTranslateModeButton(false);
        }
    }

    private void cancelInlineMsgPreviewImmediate() {
        if (mInlinePreviewResetRunnable != null) {
            mMainHandler.removeCallbacks(mInlinePreviewResetRunnable);
            mInlinePreviewResetRunnable = null;
        }
        if (mShowingInlineMsgPreview) {
            mShowingInlineMsgPreview = false;
            if (mSourceBufferInput != null) {
                mSourceBufferInput.setTextColor(0xFFFFFFFF);
                if (isLiveTranslationActive()) {
                    setSourceBufferTextInternal(mLiveEngine.getCurrentSourceText());
                    mSourceBufferInput.setVisibility(View.VISIBLE);
                } else {
                    setSourceBufferTextInternal("");
                    mSourceBufferInput.setVisibility(View.GONE);
                }
            }
        }
    }

    /**
     * Called by LatinIME.onUpdateSelection whenever the target app's cursor or text length changes.
     */
    public void onExternalSelectionChanged(
            final int oldSelStart,
            final int oldSelEnd,
            final int newSelStart,
            final int newSelEnd
    ) {
        if (!isLiveTranslationActive()) return;
        if (newSelStart == 0 && newSelEnd == 0 && hasSourceText()) {
            if (mLiveEngine.syncWithChatBoxBeforeKeystroke(mInputConnectionProvider)) {
                setSourceBufferTextInternal("");
            }
        }
    }

    public void toggleToolsDrawer() {
        if (mToolsDrawer == null) return;
        mIsToolsDrawerOpen = !mIsToolsDrawerOpen;
        mToolsDrawer.setVisibility(mIsToolsDrawerOpen ? View.VISIBLE : View.GONE);
    }

    public boolean isLiveTranslationActive() {
        return mLiveEngine != null && mLiveEngine.isAutoTranslateEnabled();
    }

    public boolean hasSourceText() {
        return !getSourceText().isEmpty();
    }

    public String getSourceText() {
        if (mSourceBufferInput == null || mShowingInlineMsgPreview) return "";
        CharSequence cs = mSourceBufferInput.getText();
        return cs != null ? cs.toString() : "";
    }

    /**
     * Returns the original source text immediately before the cursor inside mSourceBufferInput
     * so personal shortcuts match the user's ORIGINAL text when Live Translation is active.
     */
    public String getSourceTextBeforeCursor() {
        if (mSourceBufferInput == null || mShowingInlineMsgPreview || !isLiveTranslationActive()) {
            return "";
        }
        CharSequence cs = mSourceBufferInput.getText();
        if (cs == null || cs.length() == 0) return "";
        int selEnd = mSourceBufferInput.getSelectionEnd();
        if (selEnd < 0 || selEnd > cs.length()) {
            selEnd = cs.length();
        }
        int start = Math.max(0, selEnd - 48);
        return cs.subSequence(start, selEnd).toString();
    }

    /**
     * Replaces the matched shortcut trigger inside the ORIGINAL source buffer and immediately
     * triggers live translation on the expanded text.
     */
    public boolean replaceTriggerInSourceBuffer(final String matchedTrigger, final String replacementText) {
        if (mSourceBufferInput == null || matchedTrigger == null || matchedTrigger.isEmpty() || replacementText == null) {
            return false;
        }
        Editable editable = mSourceBufferInput.getText();
        if (editable == null) return false;
        int selEnd = mSourceBufferInput.getSelectionEnd();
        if (selEnd < 0 || selEnd > editable.length()) {
            selEnd = editable.length();
        }
        int trigLen = matchedTrigger.length();
        if (selEnd < trigLen) return false;
        String before = editable.subSequence(selEnd - trigLen, selEnd).toString();
        if (!before.equals(matchedTrigger)
                && !before.toLowerCase(java.util.Locale.ROOT).equals(matchedTrigger.toLowerCase(java.util.Locale.ROOT))) {
            return false;
        }
        mUpdatingBufferProgrammatically = true;
        try {
            editable.replace(selEnd - trigLen, selEnd, replacementText);
            int newCursor = selEnd - trigLen + replacementText.length();
            if (newCursor <= editable.length()) {
                mSourceBufferInput.setSelection(newCursor);
            }
        } finally {
            mUpdatingBufferProgrammatically = false;
        }
        String updated = editable.toString();
        updateSourceBufferTypography(updated);
        mLiveEngine.onSourceBufferChanged(updated, mInputConnectionProvider, true);
        return true;
    }

    public void clearSourceAndTranslatedText() {
        cancelInlineMsgPreviewImmediate();
        setSourceBufferTextInternal("");
        mLiveEngine.clearSessionAndChatTranslation(mInputConnectionProvider);
    }

    public boolean onInterceptCodeInput(final int codePoint, final boolean isKeyRepeat) {
        // Extend reading timer if the user is typing a reply while the screen translation overlay is visible
        LinguaKeyScreenTranslateService.getInstance().extendReadingTimerOnUserActivity();

        if (!isLiveTranslationActive() || mSourceBufferInput == null) {
            return false;
        }

        cancelInlineMsgPreviewImmediate();

        if (codePoint == Constants.CODE_DELETE) {
            if (!hasSourceText()) {
                return false;
            }
            Editable editable = mSourceBufferInput.getText();
            int selStart = mSourceBufferInput.getSelectionStart();
            int selEnd = mSourceBufferInput.getSelectionEnd();
            if (selStart < 0 || selEnd < 0) {
                selStart = editable.length();
                selEnd = editable.length();
            }
            int min = Math.min(selStart, selEnd);
            int max = Math.max(selStart, selEnd);

            mUpdatingBufferProgrammatically = true;
            try {
                if (min != max) {
                    editable.delete(min, max);
                    mSourceBufferInput.setSelection(min);
                } else if (min > 0) {
                    int charCount = Character.charCount(Character.codePointBefore(editable, min));
                    int delStart = Math.max(0, min - charCount);
                    editable.delete(delStart, min);
                    mSourceBufferInput.setSelection(delStart);
                } else {
                    int lastCp = Character.codePointBefore(editable, editable.length());
                    int charCount = Character.charCount(lastCp);
                    editable.delete(Math.max(0, editable.length() - charCount), editable.length());
                    mSourceBufferInput.setSelection(editable.length());
                }
            } finally {
                mUpdatingBufferProgrammatically = false;
            }

            String updated = editable.toString();
            updateSourceBufferTypography(updated);
            mLiveEngine.onSourceBufferChanged(updated, mInputConnectionProvider, false);
            return true;
        }

        if (codePoint == Constants.CODE_ENTER) {
            if (hasSourceText()) {
                setSourceBufferTextInternal("");
                mLiveEngine.onSessionCommittedByEnter();
            }
            return false;
        }

        if (codePoint <= 0) {
            return false;
        }

        // Before inserting any new character, verify whether the previous message was sent or cleared in the chat box!
        if (mLiveEngine.syncWithChatBoxBeforeKeystroke(mInputConnectionProvider)) {
            setSourceBufferTextInternal("");
        }

        String textToInsert = new String(Character.toChars(codePoint));
        insertIntoSourceBuffer(textToInsert, isBoundaryCodePoint(codePoint));
        return true;
    }

    public boolean onInterceptTextInput(final String rawText) {
        if (!isLiveTranslationActive() || mSourceBufferInput == null || rawText == null || rawText.isEmpty()) {
            return false;
        }
        if (mLiveEngine.syncWithChatBoxBeforeKeystroke(mInputConnectionProvider)) {
            setSourceBufferTextInternal("");
        }
        insertIntoSourceBuffer(rawText, true);
        return true;
    }

    public boolean onMoveCursorInSourceBuffer(final int steps) {
        if (!isLiveTranslationActive() || !hasSourceText() || mSourceBufferInput == null) {
            return false;
        }
        int len = mSourceBufferInput.getText().length();
        int current = mSourceBufferInput.getSelectionEnd();
        if (current < 0) current = len;
        int next = Math.max(0, Math.min(len, current + steps));
        mSourceBufferInput.setSelection(next);
        return true;
    }

    public boolean onSwipeDeleteInSourceBuffer(final int steps) {
        if (!isLiveTranslationActive() || !hasSourceText() || mSourceBufferInput == null) {
            return false;
        }
        if (steps < 0) {
            int count = Math.min(Math.abs(steps), 8);
            for (int i = 0; i < count; i++) {
                if (!hasSourceText()) break;
                onInterceptCodeInput(Constants.CODE_DELETE, true);
            }
        }
        return true;
    }

    private void insertIntoSourceBuffer(final String textToInsert, final boolean isBoundary) {
        if (mSourceBufferInput == null) return;
        if (mSourceBufferInput.getVisibility() != View.VISIBLE) {
            mSourceBufferInput.setVisibility(View.VISIBLE);
        }
        if (!mSourceBufferInput.hasFocus()) {
            mSourceBufferInput.requestFocus();
        }

        Editable editable = mSourceBufferInput.getText();
        int selStart = mSourceBufferInput.getSelectionStart();
        int selEnd = mSourceBufferInput.getSelectionEnd();
        if (selStart < 0 || selEnd < 0) {
            selStart = editable.length();
            selEnd = editable.length();
        }
        int min = Math.min(selStart, selEnd);
        int max = Math.max(selStart, selEnd);

        mUpdatingBufferProgrammatically = true;
        try {
            editable.replace(min, max, textToInsert);
            int newCursor = min + textToInsert.length();
            if (newCursor <= editable.length()) {
                mSourceBufferInput.setSelection(newCursor);
            }
        } finally {
            mUpdatingBufferProgrammatically = false;
        }

        String updated = editable.toString();
        updateSourceBufferTypography(updated);
        mLiveEngine.onSourceBufferChanged(updated, mInputConnectionProvider, isBoundary);
    }

    private boolean isBoundaryCodePoint(final int codePoint) {
        return codePoint == ' '
                || codePoint == '?'
                || codePoint == '؟'
                || codePoint == '.'
                || codePoint == '!'
                || codePoint == ','
                || codePoint == '،'
                || codePoint == ';';
    }

    private void setSourceBufferTextInternal(final String text) {
        if (mSourceBufferInput == null) return;
        String safe = text != null ? text : "";
        String current = getSourceText();
        if (safe.equals(current)) {
            updateSourceBufferTypography(safe);
            return;
        }
        mUpdatingBufferProgrammatically = true;
        try {
            mSourceBufferInput.setText(safe);
            mSourceBufferInput.setSelection(safe.length());
        } finally {
            mUpdatingBufferProgrammatically = false;
        }
        updateSourceBufferTypography(safe);
    }

    private void updateSourceBufferTypography(final String text) {
        if (mSourceBufferInput == null) return;
        int len = text != null ? text.length() : 0;
        float targetSp;
        int targetMaxLines;
        if (len <= 34) {
            targetSp = 13.5f;
            targetMaxLines = 1;
        } else if (len <= 72) {
            targetSp = 12.0f;
            targetMaxLines = 2;
        } else {
            targetSp = 10.8f;
            targetMaxLines = 3;
        }
        mSourceBufferInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, targetSp);
        mSourceBufferInput.setMaxLines(targetMaxLines);
    }

    private void updateSourceBufferVisibility(final boolean translationActive) {
        if (mQuickToolsStrip != null) {
            // Instantly hide quick tools when live translation is active, and show them cleanly when OFF
            mQuickToolsStrip.setVisibility(translationActive ? View.GONE : View.VISIBLE);
        }
        if (mSourceBufferInput == null) return;
        if (translationActive) {
            mSourceBufferInput.setVisibility(View.VISIBLE);
            mSourceBufferInput.setHint("");
            mSourceBufferInput.requestFocus();
            mSourceBufferInput.setCursorVisible(true);
        } else if (!mShowingInlineMsgPreview) {
            setSourceBufferTextInternal("");
            mSourceBufferInput.setHint("");
            mSourceBufferInput.clearFocus();
            mSourceBufferInput.setVisibility(View.GONE);
        }
    }

    public void closeToolsDrawer() {
        if (mIsToolsDrawerOpen) {
            mIsToolsDrawerOpen = false;
            if (mToolsDrawer != null) {
                mToolsDrawer.setVisibility(View.GONE);
            }
        }
        // Do NOT cancel an in-flight screen capture if the system MediaProjection consent dialog briefly hides the IME window!
        if (mMsgTouchService != null && !LinguaKeyScreenCaptureManager.getInstance(mContext).isCaptureInProgress()) {
            mMsgTouchService.setSelectionMode(false);
            mMsgTouchService.dismissTranslationPopup();
        }
        mShowingInlineMsgPreview = false;
    }

    @Override
    public void onStateChanged(
            final boolean translationActive,
            final String sourceLang,
            final String targetLang,
            final String dialectMode,
            final boolean isTranslating,
            final String sourceBufferText,
            final String currentTranslatedText,
            final List<String> alternatives
    ) {
        styleTranslateToggleButton(translationActive);

        if (mSourceBufferInput != null && !mShowingInlineMsgPreview) {
            if (!translationActive) {
                if (mSourceBufferInput.getVisibility() != View.GONE || hasSourceText()) {
                    updateSourceBufferVisibility(false);
                }
            } else {
                if (mSourceBufferInput.getVisibility() != View.VISIBLE) {
                    updateSourceBufferVisibility(true);
                }
                String targetBuf = sourceBufferText != null ? sourceBufferText : "";
                if (!mUpdatingBufferProgrammatically && !targetBuf.equals(getSourceText())) {
                    setSourceBufferTextInternal(targetBuf);
                }
            }
        }

        if (mLiveProgress != null) {
            mLiveProgress.setVisibility((translationActive && isTranslating) ? View.VISIBLE : View.GONE);
        }

        if (mSourceLangChip != null) mSourceLangChip.setText(mLiveEngine.getBadge(sourceLang));
        if (mTargetLangChip != null) mTargetLangChip.setText(mLiveEngine.getBadge(targetLang));

        if (mUndoChip != null) {
            boolean hasAny = translationActive && sourceBufferText != null && !sourceBufferText.trim().isEmpty();
            mUndoChip.setVisibility(hasAny ? View.VISIBLE : View.GONE);
        }

        renderAlternatives(translationActive ? alternatives : null);
    }

    private void renderAlternatives(final List<String> alternatives) {
        if (mAlternativesContainer == null) return;
        if (alternatives == null || alternatives.isEmpty()) {
            if (mAlternativesScroll != null && mAlternativesScroll.getVisibility() != View.GONE) {
                mAlternativesContainer.removeAllViews();
                mAlternativesScroll.setVisibility(View.GONE);
            }
            return;
        }

        mAlternativesContainer.removeAllViews();
        if (mAlternativesScroll != null) {
            mAlternativesScroll.setVisibility(View.VISIBLE);
        }

        TextView labelView = new TextView(mContext);
        labelView.setText("بدائل:");
        labelView.setTextSize(10.5f);
        labelView.setTextColor(0xFF8AB4F8);
        labelView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        labelView.setPadding((int) dpToPx(4f), (int) dpToPx(2f), (int) dpToPx(6f), (int) dpToPx(2f));
        mAlternativesContainer.addView(labelView);

        for (int i = 0; i < alternatives.size(); i++) {
            final int idx = i;
            String altText = alternatives.get(i);
            TextView altChip = new TextView(mContext);
            altChip.setText(altText);
            altChip.setTextSize(11f);
            altChip.setTextColor(0xFFE8EAED);
            altChip.setSingleLine(true);
            altChip.setGravity(Gravity.CENTER_VERTICAL);
            altChip.setPadding((int) dpToPx(10f), (int) dpToPx(3f), (int) dpToPx(10f), (int) dpToPx(3f));

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(dpToPx(12f));
            bg.setColor(0xFF3C4043);
            bg.setStroke((int) dpToPx(1f), 0xFF5F6368);
            altChip.setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.setMarginEnd((int) dpToPx(6f));
            altChip.setLayoutParams(lp);

            altChip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    vibrateTick();
                    mLiveEngine.selectAlternativeInChatBox(idx, mInputConnectionProvider);
                }
            });
            mAlternativesContainer.addView(altChip);
        }
    }

    private void showLanguagePickerDialog(final boolean isSource) {
        final String[] codes = LiveTranslationEngine.SUPPORTED_LANG_CODES;
        final String[] names = LiveTranslationEngine.SUPPORTED_LANG_NAMES_AR;
        String currentCode = isSource
                ? LinguaKeySecurityManager.getSourceLang(mContext)
                : LinguaKeySecurityManager.getTargetLang(mContext);
        int checkedIdx = 0;
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(currentCode)) {
                checkedIdx = i;
                break;
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(DialogUtils.getPlatformDialogThemeContext(mContext));
        builder.setTitle(isSource ? "اختر لغة الكتابة (المصدر)" : "اختر لغة الترجمة (الهدف)");
        builder.setSingleChoiceItems(names, checkedIdx, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String selectedCode = codes[which];
                if (isSource) {
                    LinguaKeySecurityManager.setSourceLang(mContext, selectedCode);
                } else {
                    LinguaKeySecurityManager.setTargetLang(mContext, selectedCode);
                }
                mLiveEngine.notifyStatus();
                if (isLiveTranslationActive() && hasSourceText()) {
                    mLiveEngine.triggerLiveTranslationNow(getSourceText(), mInputConnectionProvider, false);
                }
                dialog.dismiss();
            }
        });

        AlertDialog dialog = builder.create();
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.token = mInputView.getWindowToken();
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
            window.setAttributes(lp);
            window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        }
        dialog.show();
    }

    private void styleMsgTranslateModeButton(final boolean active) {
        if (mMsgTranslateModeBtn == null) return;
        if (mLastStyledMsgActive != null && mLastStyledMsgActive == active) return;
        mLastStyledMsgActive = active;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(9f));
        if (active) {
            bg.setColor(0xFF0D6EFD);
            mMsgTranslateModeBtn.setBackground(bg);
            mMsgTranslateModeBtn.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        } else {
            bg.setColor(0xFF222427);
            mMsgTranslateModeBtn.setBackground(bg);
            mMsgTranslateModeBtn.setColorFilter(0xFFE8EAED, PorterDuff.Mode.SRC_IN);
        }
    }

    private void styleTranslateToggleButton(final boolean active) {
        if (mTranslateToggleBtn == null) return;
        if (mLastStyledTranslateActive != null && mLastStyledTranslateActive == active) return;
        mLastStyledTranslateActive = active;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(9f));
        if (active) {
            bg.setColor(0xFF0D6EFD);
            mTranslateToggleBtn.setBackground(bg);
            mTranslateToggleBtn.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        } else {
            bg.setColor(0xFF222427);
            mTranslateToggleBtn.setBackground(bg);
            mTranslateToggleBtn.setColorFilter(0xFFE8EAED, PorterDuff.Mode.SRC_IN);
        }
    }

    public void applyGboardTheme() {
        if (mLiveBarContainer != null) {
            mLiveBarContainer.setBackgroundColor(0xFF000000);
        }
        int toolIconTint = 0xFFDADCE0;
        if (mQuickClipboardBtn != null) mQuickClipboardBtn.setColorFilter(toolIconTint, PorterDuff.Mode.SRC_IN);
        if (mQuickEmojiBtn != null) mQuickEmojiBtn.setColorFilter(toolIconTint, PorterDuff.Mode.SRC_IN);
        if (mQuickLastPhotoBtn != null) mQuickLastPhotoBtn.setColorFilter(toolIconTint, PorterDuff.Mode.SRC_IN);
        if (mQuickSettingsBtn != null) mQuickSettingsBtn.setColorFilter(toolIconTint, PorterDuff.Mode.SRC_IN);
        if (mSourceBufferInput != null) {
            mSourceBufferInput.setBackground(null);
            mSourceBufferInput.setTextColor(0xFFFFFFFF);
            mSourceBufferInput.setHint("");
        }
        mLastStyledMsgActive = null;
        mLastStyledTranslateActive = null;
        styleMsgTranslateModeButton(mIsMsgSelectModeActive);
        styleTranslateToggleButton(isLiveTranslationActive());
        updateSourceBufferVisibility(isLiveTranslationActive());

        stylePill(mSourceLangChip, 0xFF1C1E21, 0xFF32363B, 0xFFE8EAED);
        stylePill(mSwapDirectionBtn, Color.TRANSPARENT, Color.TRANSPARENT, 0xFF8AB4F8);
        stylePill(mTargetLangChip, 0xFF1C1E21, 0xFF8AB4F8, 0xFF8AB4F8);
        stylePill(mPolishChip, 0xFF174EA6, 0xFF8AB4F8, 0xFFD2E3FC);
        stylePill(mUndoChip, 0xFF222427, 0xFFF28B82, 0xFFF28B82);

        if (mToolsDrawer != null) {
            GradientDrawable drawerBg = new GradientDrawable();
            drawerBg.setShape(GradientDrawable.RECTANGLE);
            drawerBg.setCornerRadius(dpToPx(12f));
            drawerBg.setColor(0xFF141517);
            mToolsDrawer.setBackground(drawerBg);
        }
    }

    public void applyPremiumMinimalTheme() {
        applyGboardTheme();
    }

    private void stylePill(final TextView view, final int fillColor, final int strokeColor, final int textColor) {
        if (view == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(14f));
        bg.setColor(fillColor);
        if (strokeColor != Color.TRANSPARENT) {
            bg.setStroke((int) dpToPx(1f), strokeColor);
        }
        view.setBackground(bg);
        view.setTextColor(textColor);
    }

    private void vibrateTick() {
        AudioAndHapticFeedbackManager.getInstance().performTickFeedback();
    }

    private float dpToPx(final float dp) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                mContext.getResources().getDisplayMetrics()
        );
    }
}
