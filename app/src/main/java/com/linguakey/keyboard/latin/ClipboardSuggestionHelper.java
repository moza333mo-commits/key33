package com.linguakey.keyboard.latin;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;
import android.widget.Toast;

import com.linguakey.keyboard.R;
import com.linguakey.keyboard.compat.PreferenceManagerCompat;

/**
 * Helper class to manage the clipboard paste suggestions overlay bar.
 * Automatically yields to LinguaKeyMessageTouchService when a foreign chat message is copied for translation.
 */
public final class ClipboardSuggestionHelper {
    private final ClipboardBarController mController;
    private final Context mContext;
    private final View mClipboardSuggestionBar;
    private final TextView mClipboardSuggestionChip;
    private final TextView mClipboardSuggestionCloseBtn;

    private static String sDismissedText = "";
    private static String sLastShownClipText = null;
    private static long sClipShowStartTime = 0L;

    private final Handler mSuggestionDismissHandler = new Handler(Looper.getMainLooper());
    private final Runnable mSuggestionDismissRunnable = new Runnable() {
        @Override
        public void run() {
            if (mClipboardSuggestionBar != null) {
                mClipboardSuggestionBar.setVisibility(View.GONE);
            }
        }
    };

    private final android.content.ClipboardManager.OnPrimaryClipChangedListener mPrimaryClipListener =
        new android.content.ClipboardManager.OnPrimaryClipChangedListener() {
            @Override
            public void onPrimaryClipChanged() {
                sLastShownClipText = null;
                sDismissedText = "";
                checkAndShowClipboardSuggestion();
            }
        };

    public ClipboardSuggestionHelper(ClipboardBarController controller, View inputView) {
        mController = controller;
        mContext = controller.getContext();

        mClipboardSuggestionBar = inputView.findViewById(R.id.clipboard_suggestion_bar);
        mClipboardSuggestionChip = inputView.findViewById(R.id.clipboard_suggestion_chip);
        mClipboardSuggestionCloseBtn = inputView.findViewById(R.id.clipboard_suggestion_close_btn);

        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.addPrimaryClipChangedListener(mPrimaryClipListener);
        }
    }

    public void hideClipboardSuggestion() {
        if (mClipboardSuggestionBar != null) {
            mClipboardSuggestionBar.setVisibility(View.GONE);
        }
        mSuggestionDismissHandler.removeCallbacks(mSuggestionDismissRunnable);
    }

    public void checkAndShowClipboardSuggestion() {
        if (mClipboardSuggestionBar == null || mClipboardSuggestionChip == null) return;

        mSuggestionDismissHandler.removeCallbacks(mSuggestionDismissRunnable);

        // Hide the paste chip when LinguaKeyMessageTouchService is auto-translating a copied foreign message
        if (LinguaKeyMessageTouchService.shouldSuppressClipboardPasteChip()) {
            mClipboardSuggestionBar.setVisibility(View.GONE);
            return;
        }

        boolean isWindowShown = false;
        if (mContext instanceof LatinIME) {
            isWindowShown = ((LatinIME) mContext).isInputViewShown();
        }

        if (!isWindowShown) {
            mClipboardSuggestionBar.setVisibility(View.GONE);
            return;
        }

        final String clipText = mController.getMostRecentClipboardText();
        if (clipText != null && !clipText.trim().isEmpty() && !clipText.equals(sDismissedText)) {
            if (!clipText.equals(sLastShownClipText)) {
                sLastShownClipText = clipText;
                sClipShowStartTime = System.currentTimeMillis();
            }

            long elapsedMs = System.currentTimeMillis() - sClipShowStartTime;

            SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(mContext);
            int timeoutSecs = 60;
            try {
                String timeoutStr = prefs.getString("pref_clip_suggestion_timeout", "60");
                timeoutSecs = Integer.parseInt(timeoutStr.trim());
            } catch (Exception e) {
            }

            long timeoutMs = timeoutSecs * 1000L;
            if (timeoutSecs > 0 && elapsedMs >= timeoutMs) {
                mClipboardSuggestionBar.setVisibility(View.GONE);
                return;
            }

            String preview = clipText.replace("\n", " ").trim();
            if (preview.length() > 30) {
                preview = preview.substring(0, 30);
            }
            mClipboardSuggestionChip.setText("لصق: " + preview);

            mClipboardSuggestionChip.setOnClickListener(v -> {
                if (mContext instanceof LatinIME) {
                    InputConnection conn = ((LatinIME) mContext).getCurrentInputConnection();
                    if (conn != null) {
                        conn.commitText(clipText, 1);
                    }
                }
                mClipboardSuggestionBar.setVisibility(View.GONE);
                sDismissedText = clipText;
                mSuggestionDismissHandler.removeCallbacks(mSuggestionDismissRunnable);
            });

            mClipboardSuggestionChip.setOnLongClickListener(v -> {
                Vibrator vibrator = mController.getVibrator();
                if (vibrator != null) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
                        } else {
                            vibrator.vibrate(40);
                        }
                    } catch (Exception e) {}
                }
                mClipboardSuggestionBar.setVisibility(View.GONE);
                sDismissedText = clipText;
                mSuggestionDismissHandler.removeCallbacks(mSuggestionDismissRunnable);
                Toast.makeText(mContext, "تم إخفاء الاقتراح", Toast.LENGTH_SHORT).show();
                return true;
            });

            if (mClipboardSuggestionCloseBtn != null) {
                mClipboardSuggestionCloseBtn.setOnClickListener(v -> {
                    mClipboardSuggestionBar.setVisibility(View.GONE);
                    sDismissedText = clipText;
                    mSuggestionDismissHandler.removeCallbacks(mSuggestionDismissRunnable);
                });
            }

            mClipboardSuggestionBar.setVisibility(View.VISIBLE);

            if (timeoutSecs > 0) {
                long remainingMs = timeoutMs - elapsedMs;
                if (remainingMs > 0) {
                    mSuggestionDismissHandler.postDelayed(mSuggestionDismissRunnable, remainingMs);
                } else {
                    mClipboardSuggestionBar.setVisibility(View.GONE);
                }
            }
        } else {
            mClipboardSuggestionBar.setVisibility(View.GONE);
        }
    }

    public void applyTheming(int accentColor, boolean isDark) {
        if (mClipboardSuggestionChip != null) {
            GradientDrawable csBg = new GradientDrawable();
            csBg.setShape(GradientDrawable.RECTANGLE);
            csBg.setCornerRadius(mController.dpToPx(16));
            csBg.setColor(Color.argb(isDark ? 45 : 30, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)));
            csBg.setStroke(mController.dpToPx(1f), accentColor);

            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.RECTANGLE);
            mask.setCornerRadius(mController.dpToPx(16));
            mask.setColor(0xFFFFFFFF);

            RippleDrawable ripple = new RippleDrawable(
                    ColorStateList.valueOf(0x40FFFFFF), csBg, mask);
            mClipboardSuggestionChip.setBackground(ripple);
            mClipboardSuggestionChip.setTextColor(isDark ? 0xFFFFFFFF : 0xFF1C1B1F);

            Drawable pasteIcon = mContext.getDrawable(R.drawable.ic_content_paste);
            if (pasteIcon != null) {
                pasteIcon = pasteIcon.mutate();
                pasteIcon.setTint(accentColor);
                int size = mController.dpToPx(14);
                pasteIcon.setBounds(0, 0, size, size);
                mClipboardSuggestionChip.setCompoundDrawables(pasteIcon, null, null, null);
                mClipboardSuggestionChip.setCompoundDrawablePadding(mController.dpToPx(6));
            }
        }
        if (mClipboardSuggestionCloseBtn != null) {
            GradientDrawable closeBg = new GradientDrawable();
            closeBg.setShape(GradientDrawable.OVAL);
            closeBg.setColor(isDark ? 0xCC303134 : 0xDDE0E2E6);
            closeBg.setStroke(mController.dpToPx(1f), isDark ? 0x66FFFFFF : 0x44000000);
            mClipboardSuggestionCloseBtn.setBackground(closeBg);
            mClipboardSuggestionCloseBtn.setTextColor(isDark ? 0xFFE8EAED : 0xFF1C1B1F);
        }
    }

    public void onDestroy() {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && mPrimaryClipListener != null) {
            clipboard.removePrimaryClipChangedListener(mPrimaryClipListener);
        }
        mSuggestionDismissHandler.removeCallbacks(mSuggestionDismissRunnable);
    }
}
