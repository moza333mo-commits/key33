package com.linguakey.keyboard.latin;

import android.content.Context;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.widget.Toast;

/**
 * LinguaKey Coordinator for One-Tap Full-Screen In-Place OCR Translation.
 *
 * Clean, Play-Protect-Safe Implementation:
 * - Zero BIND_ACCESSIBILITY_SERVICE and zero SYSTEM_ALERT_WINDOW.
 * - Zero unsolicited clipboard interception (only triggers on explicit user tap).
 * - Bridges LinguaKeySmartBarController directly to LinguaKeyScreenTranslateService with full
 *   stage-by-stage diagnostic logging via LinguaKeyScreenDiag.
 */
public final class LinguaKeyMessageTouchService {
    private static LinguaKeyMessageTouchService sInstance;
    private static volatile String sLastOutgoingTranslation = "";

    private final Context mContext;
    private View mAnchorView;
    private boolean mIsActive = false;

    private LinguaKeyMessageTouchService(final Context context) {
        mContext = context.getApplicationContext();
    }

    public static synchronized LinguaKeyMessageTouchService getOrCreateInstance(final Context context) {
        if (sInstance == null && context != null) {
            sInstance = new LinguaKeyMessageTouchService(context);
        }
        return sInstance;
    }

    public static LinguaKeyMessageTouchService getInstance() {
        return sInstance;
    }

    public static void setLastOutgoingTranslation(final String outgoing) {
        sLastOutgoingTranslation = outgoing != null ? outgoing.trim() : "";
    }

    public static boolean shouldSuppressClipboardPasteChip() {
        return LinguaKeyScreenTranslateService.getInstance().isOverlayShowing();
    }

    public void attachAnchorView(final View anchorView) {
        mAnchorView = anchorView;
        if (anchorView != null) {
            LinguaKeyScreenCaptureManager.getInstance(mContext).updateAnchorViewIfActive(anchorView);
        }
    }

    public boolean isOverlayShowing() {
        return LinguaKeyScreenTranslateService.getInstance().isOverlayShowing();
    }

    public boolean isSelectionModeActive() {
        return mIsActive || isOverlayShowing();
    }

    public void setSelectionMode(final boolean active) {
        mIsActive = active;
        if (!active) {
            dismissTranslationPopup();
        }
    }

    /**
     * Primary entry point invoked when the user taps the Screen Translate icon on the keyboard bar.
     */
    public boolean triggerOneTapFullScreenTranslate(
            final View anchorView,
            final LinguaKeyScreenTranslateService.ScreenOverlayCallback callback
    ) {
        View targetAnchor = anchorView != null ? anchorView : mAnchorView;
        if (targetAnchor == null) {
            LinguaKeyScreenDiag.warn("TAP", "anchorView is null — cannot start screen translation");
            return false;
        }
        mAnchorView = targetAnchor;
        mIsActive = true;
        LinguaKeyScreenDiag.stage("TAP", "One-tap Screen Translate invoked | anchor="
                + targetAnchor.getClass().getSimpleName()
                + " windowToken=" + (targetAnchor.getWindowToken() != null));

        return LinguaKeyScreenTranslateService.getInstance().translateVisibleScreenNow(
                targetAnchor,
                null,
                new LinguaKeyScreenTranslateService.ScreenOverlayCallback() {
                    @Override
                    public void onOverlayStateChanged(final boolean active) {
                        mIsActive = active;
                        if (callback != null) {
                            callback.onOverlayStateChanged(active);
                        }
                    }

                    @Override
                    public void onLastMessageTranslated(final String arabicText) {
                        if (callback != null) {
                            callback.onLastMessageTranslated(arabicText);
                        }
                    }
                }
        );
    }

    public void onMessageTranslateButtonTapped(final InputConnection ic) {
        triggerOneTapFullScreenTranslate(mAnchorView, null);
    }

    public void dismissTranslationPopup() {
        LinguaKeyScreenTranslateService.getInstance().dismissOverlayImmediate();
    }

    public void dismissPromptPopup() {
        dismissTranslationPopup();
    }

    public static void openAccessibilitySettings(final Context context) {
        if (context == null) return;
        Toast.makeText(
                context,
                "✨ ترجمة الشاشة الفورية مفعّلة وتعمل مباشرة عبر التقاط الشاشة و OCR المدمج بدون الحاجة لإذن إمكانية الوصول",
                Toast.LENGTH_SHORT
        ).show();
    }
}
