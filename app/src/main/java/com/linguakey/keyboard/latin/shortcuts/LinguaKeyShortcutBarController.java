package com.linguakey.keyboard.latin.shortcuts;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.linguakey.keyboard.R;
import com.linguakey.keyboard.latin.shortcuts.LinguaKeyShortcutModels.ShortcutEntry;

import java.util.List;
import java.util.Locale;

/**
 * Real-Time Personal Shortcuts Suggestion Strip Controller inside LinguaKey IME.
 *
 * - Evaluates the exact word immediately preceding the cursor in O(1) via LinguaKeyShortcutRepository.
 * - Strictly prevents false triggers on partial/similar words (word-boundary exact match only).
 * - Automatically skips password fields for privacy (following SwiftFloris best practices).
 * - Replaces ONLY the exact trigger token with the full replacement text (supporting unlimited length,
 *   Arabic RTL, English, symbols, emojis, and multi-line strings) in an atomic batch edit.
 * - Stays completely hidden (View.GONE) when no shortcut matches, keeping the keyboard clean.
 */
public final class LinguaKeyShortcutBarController {
    private final Context mContext;
    private final LinguaKeyShortcutRepository mRepository;
    private final HorizontalScrollView mBarScroll;
    private final LinearLayout mChipsContainer;

    public interface SourceBufferReplacer {
        boolean replaceTrigger(String matchedTrigger, String replacementText);
    }

    private String mCurrentMatchedTrigger = "";
    private boolean mIsPasswordField = false;
    private long mLastEvalUptimeMs = 0L;

    public LinguaKeyShortcutBarController(final Context context, final View inputView) {
        mContext = context;
        mRepository = LinguaKeyShortcutRepository.getInstance(context);
        mBarScroll = inputView != null ? (HorizontalScrollView) inputView.findViewById(R.id.lk_shortcut_suggestion_bar) : null;
        mChipsContainer = inputView != null ? (LinearLayout) inputView.findViewById(R.id.lk_shortcut_chips_container) : null;
        hideBar();
    }

    public void onStartInputView(final EditorInfo editorInfo) {
        mIsPasswordField = isPasswordInput(editorInfo);
        hideBar();
    }

    /**
     * Evaluates the exact word before cursor and updates the shortcut suggestion strip immediately.
     */
    public void evaluateBeforeCursor(final InputConnection ic, final Locale activeLocale) {
        if (mBarScroll == null || mChipsContainer == null || mIsPasswordField || ic == null) {
            hideBar();
            return;
        }
        // Zero-cost fast exit: avoid any Binder IPC call on keystrokes when user has 0 shortcuts
        if (mRepository.getTotalCount() == 0) {
            if (mBarScroll.getVisibility() != View.GONE) {
                hideBar();
            }
            return;
        }
        // Deduplicate back-to-back onEvent + onUpdateSelection calls within 25ms
        long now = android.os.SystemClock.uptimeMillis();
        if (now - mLastEvalUptimeMs < 25L) {
            return;
        }
        mLastEvalUptimeMs = now;
        try {
            CharSequence beforeSeq = ic.getTextBeforeCursor(32, 0);
            String exactToken = extractExactWordBeforeCursor(beforeSeq != null ? beforeSeq.toString() : "");
            if (exactToken.isEmpty()) {
                if (!mCurrentMatchedTrigger.isEmpty()) hideBar();
                return;
            }
            if (exactToken.equals(mCurrentMatchedTrigger) && mBarScroll.getVisibility() == View.VISIBLE) {
                return;
            }
            String activeLang = activeLocale != null ? activeLocale.getLanguage() : "";
            List<ShortcutEntry> matches = mRepository.findMatchesForWord(exactToken, activeLang);
            if (matches == null || matches.isEmpty()) {
                if (!mCurrentMatchedTrigger.isEmpty()) hideBar();
                return;
            }
            mCurrentMatchedTrigger = exactToken;
            renderSuggestionChips(matches, ic);
        } catch (Throwable ignored) {
            hideBar();
        }
    }

    /**
     * Evaluates the exact word before cursor in the ORIGINAL Live Translation source buffer
     * (instead of the translated chat box text) and updates the shortcut suggestion strip.
     */
    public void evaluateSourceBuffer(
            final String sourceTextBeforeCursor,
            final Locale activeLocale,
            final SourceBufferReplacer replacer
    ) {
        if (mBarScroll == null || mChipsContainer == null || mIsPasswordField || replacer == null) {
            hideBar();
            return;
        }
        if (mRepository.getTotalCount() == 0) {
            if (mBarScroll.getVisibility() != View.GONE) {
                hideBar();
            }
            return;
        }
        try {
            String exactToken = extractExactWordBeforeCursor(sourceTextBeforeCursor != null ? sourceTextBeforeCursor : "");
            if (exactToken.isEmpty()) {
                if (!mCurrentMatchedTrigger.isEmpty()) hideBar();
                return;
            }
            if (exactToken.equals(mCurrentMatchedTrigger) && mBarScroll.getVisibility() == View.VISIBLE) {
                return;
            }
            String activeLang = activeLocale != null ? activeLocale.getLanguage() : "";
            List<ShortcutEntry> matches = mRepository.findMatchesForWord(exactToken, activeLang);
            if (matches == null || matches.isEmpty()) {
                if (!mCurrentMatchedTrigger.isEmpty()) hideBar();
                return;
            }
            mCurrentMatchedTrigger = exactToken;
            renderSuggestionChipsForSourceBuffer(matches, replacer);
        } catch (Throwable ignored) {
            hideBar();
        }
    }

    private void renderSuggestionChipsForSourceBuffer(
            final List<ShortcutEntry> matches,
            final SourceBufferReplacer replacer
    ) {
        mChipsContainer.removeAllViews();

        for (int i = 0; i < matches.size(); i++) {
            final ShortcutEntry entry = matches.get(i);
            final String triggerSnapshot = mCurrentMatchedTrigger;

            TextView chip = buildShortcutChipView(entry, matches.size() > 1);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(final View v) {
                    replacer.replaceTrigger(triggerSnapshot, entry.replacement);
                    hideBar();
                }
            });
            mChipsContainer.addView(chip);
        }

        mBarScroll.setVisibility(View.VISIBLE);
    }

    private TextView buildShortcutChipView(final ShortcutEntry entry, final boolean showGroup) {
        TextView chip = new TextView(mContext);
        String singleLinePreview = entry.replacement.replace('\n', ' ').replace('\r', ' ').trim();
        if (singleLinePreview.length() > 42) {
            singleLinePreview = singleLinePreview.substring(0, 41) + "…";
        }
        String label = "⚡ " + singleLinePreview;
        if (showGroup && !TextUtils.isEmpty(entry.groupName)) {
            label = "⚡ [" + entry.groupName + "] " + singleLinePreview;
        }

        chip.setText(label);
        chip.setTextColor(0xFFFFFFFF);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);

        int padH = dpToPx(12f);
        int padV = dpToPx(5f);
        chip.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(14f));
        bg.setColor(0xFF1A3A6E);
        bg.setStroke(Math.max(1, dpToPx(1f)), 0xFF8AB4F8);
        chip.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMarginEnd(dpToPx(6f));
        chip.setLayoutParams(lp);
        return chip;
    }

    /**
     * Extracts the exact token immediately before the cursor bounded by whitespace/newline.
     * If the character right before cursor is whitespace, returns "" (no false trigger).
     */
    public static String extractExactWordBeforeCursor(final String textBeforeCursor) {
        if (textBeforeCursor == null || textBeforeCursor.isEmpty()) return "";
        int end = textBeforeCursor.length();
        char lastChar = textBeforeCursor.charAt(end - 1);
        if (Character.isWhitespace(lastChar)) {
            return "";
        }
        int start = end - 1;
        while (start > 0 && !Character.isWhitespace(textBeforeCursor.charAt(start - 1))) {
            start--;
        }
        return textBeforeCursor.substring(start, end);
    }

    /**
     * Replaces the exact trigger token before the cursor with the full shortcut replacement text.
     */
    public static boolean applyShortcutReplacement(
            final InputConnection ic,
            final String matchedTrigger,
            final String replacementText
    ) {
        if (ic == null || matchedTrigger == null || matchedTrigger.isEmpty() || replacementText == null) {
            return false;
        }
        try {
            ic.beginBatchEdit();
            ic.finishComposingText();
            CharSequence verifyBefore = ic.getTextBeforeCursor(matchedTrigger.length() + 4, 0);
            String beforeStr = verifyBefore != null ? verifyBefore.toString() : "";
            if (beforeStr.endsWith(matchedTrigger)) {
                ic.deleteSurroundingText(matchedTrigger.length(), 0);
            } else if (beforeStr.toLowerCase(Locale.ROOT).endsWith(matchedTrigger.toLowerCase(Locale.ROOT))) {
                ic.deleteSurroundingText(matchedTrigger.length(), 0);
            } else {
                ic.endBatchEdit();
                return false;
            }
            ic.commitText(replacementText, 1);
            ic.endBatchEdit();
            return true;
        } catch (Throwable ignored) {
            try {
                ic.endBatchEdit();
            } catch (Throwable ignored2) {
            }
            return false;
        }
    }

    private void renderSuggestionChips(final List<ShortcutEntry> matches, final InputConnection ic) {
        mChipsContainer.removeAllViews();

        for (int i = 0; i < matches.size(); i++) {
            final ShortcutEntry entry = matches.get(i);
            final String triggerSnapshot = mCurrentMatchedTrigger;

            TextView chip = new TextView(mContext);
            String singleLinePreview = entry.replacement.replace('\n', ' ').replace('\r', ' ').trim();
            if (singleLinePreview.length() > 42) {
                singleLinePreview = singleLinePreview.substring(0, 41) + "…";
            }
            String label = "⚡ " + singleLinePreview;
            if (matches.size() > 1 && !TextUtils.isEmpty(entry.groupName)) {
                label = "⚡ [" + entry.groupName + "] " + singleLinePreview;
            }

            chip.setText(label);
            chip.setTextColor(0xFFFFFFFF);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);

            int padH = dpToPx(12f);
            int padV = dpToPx(5f);
            chip.setPadding(padH, padV, padH, padV);

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(dpToPx(14f));
            bg.setColor(0xFF1A3A6E);
            bg.setStroke(Math.max(1, dpToPx(1f)), 0xFF8AB4F8);
            chip.setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.setMarginEnd(dpToPx(6f));
            chip.setLayoutParams(lp);

            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(final View v) {
                    applyShortcutReplacement(ic, triggerSnapshot, entry.replacement);
                    hideBar();
                }
            });

            mChipsContainer.addView(chip);
        }

        mBarScroll.setVisibility(View.VISIBLE);
    }

    public void hideBar() {
        mCurrentMatchedTrigger = "";
        if (mChipsContainer != null) {
            mChipsContainer.removeAllViews();
        }
        if (mBarScroll != null && mBarScroll.getVisibility() != View.GONE) {
            mBarScroll.setVisibility(View.GONE);
        }
    }

    private static boolean isPasswordInput(final EditorInfo ei) {
        if (ei == null) return false;
        int variation = ei.inputType & InputType.TYPE_MASK_VARIATION;
        int cls = ei.inputType & InputType.TYPE_MASK_CLASS;
        if (cls == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        }
        if (cls == InputType.TYPE_CLASS_NUMBER) {
            return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        }
        return false;
    }

    private int dpToPx(final float dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                mContext.getResources().getDisplayMetrics()
        );
    }
}
