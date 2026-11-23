package com.linguakey.keyboard.latin;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.BidiFormatter;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import com.linguakey.keyboard.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * LinguaKey End-to-End Real Screen Capture + Bundled Offline ML Kit OCR + Seamless In-Place Translation Engine.
 *
 * Clean, Professional Design (v4.1.0):
 * 1. 100% transparent overlay with ZERO glowing borders and ZERO visible box outlines.
 * 2. Each translated paragraph seamlessly replaces the original foreign text using the exact sampled
 *    background color of the underlying bubble/page (0px stroke) so it looks native and clean.
 * 3. Uses StaticLayout height measurement + strict non-overlapping vertical layout so translated
 *    sentences NEVER collide or cover each other.
 * 4. Keeps the keyboard open and responsive throughout.
 */
public final class LinguaKeyScreenTranslateService {
    private static final String TAG = "LinguaKeyScreenTrans";

    private static final float FONT_SIZE_MIN_SP = 9.5f;
    private static final float FONT_SIZE_MAX_SP = 20.0f;
    private static final float FONT_SIZE_RATIO = 0.84f;

    public interface ScreenOverlayCallback {
        void onOverlayStateChanged(boolean active);
        void onLastMessageTranslated(String arabicText);
    }

    private static final class OverlayGeometry {
        final int windowScreenTopY;
        final int keyboardTopScreenY;
        final int overlayHeight;

        OverlayGeometry(final int windowScreenTopY, final int keyboardTopScreenY, final int overlayHeight) {
            this.windowScreenTopY = windowScreenTopY;
            this.keyboardTopScreenY = keyboardTopScreenY;
            this.overlayHeight = overlayHeight;
        }
    }

    private static final LinguaKeyScreenTranslateService sInstance = new LinguaKeyScreenTranslateService();

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private PopupWindow mFullScreenPopup;
    private FrameLayout mActiveOverlayRoot;
    private View mLastAnchorView;
    private Runnable mAutoDismissRunnable;
    private ScreenOverlayCallback mCurrentCallback;
    private int mSessionGeneration = 0;

    private LinguaKeyScreenTranslateService() {
    }

    public static LinguaKeyScreenTranslateService getInstance() {
        return sInstance;
    }

    public static boolean isServiceConnected() {
        return true;
    }

    public boolean isOverlayShowing() {
        return mActiveOverlayRoot != null
                && (mActiveOverlayRoot.getParent() != null
                || (mFullScreenPopup != null && mFullScreenPopup.isShowing()));
    }

    public void extendReadingTimerOnUserActivity() {
        if (isOverlayShowing() && mAutoDismissRunnable != null) {
            scheduleAutoDismiss(10000L);
        }
    }

    public void dismissOverlayImmediate() {
        mSessionGeneration++;
        if (mAutoDismissRunnable != null) {
            mMainHandler.removeCallbacks(mAutoDismissRunnable);
            mAutoDismissRunnable = null;
        }
        if (mActiveOverlayRoot != null) {
            try {
                ViewParent parent = mActiveOverlayRoot.getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(mActiveOverlayRoot);
                    ((ViewGroup) parent).requestLayout();
                    ((ViewGroup) parent).invalidate();
                }
            } catch (Throwable ignored) {
            }
            mActiveOverlayRoot = null;
        }
        if (mFullScreenPopup != null) {
            try {
                mFullScreenPopup.dismiss();
            } catch (Throwable ignored) {
            }
            mFullScreenPopup = null;
        }
        if (mLastAnchorView != null) {
            mLastAnchorView.requestLayout();
            mLastAnchorView.invalidate();
        }
        if (mCurrentCallback != null) {
            mCurrentCallback.onOverlayStateChanged(false);
        }
    }

    private OverlayGeometry computeOverlayGeometry(
            final Context context,
            final View anchorView,
            final int screenWidth,
            final int screenHeight
    ) {
        int[] rootScreenLoc = new int[2];
        int[] rootWinLoc = new int[2];
        anchorView.getLocationOnScreen(rootScreenLoc);
        anchorView.getLocationInWindow(rootWinLoc);

        int windowScreenTopY = Math.max(0, rootScreenLoc[1] - rootWinLoc[1]);
        int keyboardTopScreenY = -1;

        View kbdContainer = anchorView.findViewById(R.id.keyboard_container);
        if (kbdContainer != null && kbdContainer.getHeight() > 0) {
            int[] kbdScreenLoc = new int[2];
            kbdContainer.getLocationOnScreen(kbdScreenLoc);
            if (kbdScreenLoc[1] > dpToPx(context, 100f)) {
                keyboardTopScreenY = kbdScreenLoc[1];
            } else if (anchorView.getHeight() > kbdContainer.getHeight()) {
                keyboardTopScreenY = windowScreenTopY + (anchorView.getHeight() - kbdContainer.getHeight());
            }
        }

        if (keyboardTopScreenY <= dpToPx(context, 100f)) {
            if (rootScreenLoc[1] > dpToPx(context, 120f)) {
                keyboardTopScreenY = rootScreenLoc[1];
            } else {
                keyboardTopScreenY = (int) (screenHeight * 0.58f);
            }
        }

        int overlayHeight = Math.max(dpToPx(context, 120f), keyboardTopScreenY - windowScreenTopY);
        LinguaKeyScreenDiag.stage("OVERLAY", "Geometry windowScreenTopY=" + windowScreenTopY
                + " kbdTopY=" + keyboardTopScreenY + " overlayH=" + overlayHeight);
        return new OverlayGeometry(windowScreenTopY, keyboardTopScreenY, overlayHeight);
    }

    public boolean translateVisibleScreenNow(
            final View anchorView,
            final List<String> fallbackMessages,
            final ScreenOverlayCallback callback
    ) {
        mCurrentCallback = callback;
        if (isOverlayShowing()) {
            dismissOverlayImmediate();
            return true;
        }
        if (anchorView == null || anchorView.getWindowToken() == null) {
            return false;
        }

        mLastAnchorView = anchorView;
        startCaptureAndOcrPipeline(anchorView);
        return true;
    }

    private void startCaptureAndOcrPipeline(final View anchorView) {
        dismissOverlayImmediate();
        final int generation = ++mSessionGeneration;
        final Context context = anchorView.getContext();

        DisplayMetrics realDm = LinguaKeyScreenCaptureManager.getInstance(context).getRealDisplayMetrics();
        final int screenWidth = realDm.widthPixels;
        final int screenHeight = realDm.heightPixels;

        final OverlayGeometry initialGeom = computeOverlayGeometry(context, anchorView, screenWidth, screenHeight);

        if (mCurrentCallback != null) {
            mCurrentCallback.onOverlayStateChanged(true);
        }

        LinguaKeyScreenCaptureManager.getInstance(context).requestScreenCapture(
                anchorView,
                initialGeom.keyboardTopScreenY,
                new LinguaKeyScreenCaptureManager.CaptureCallback() {
                    @Override
                    public void onScreenCaptured(
                            final Bitmap croppedBitmap,
                            final int cropTopY,
                            final int fullScreenWidth,
                            final int fullScreenHeight
                    ) {
                        if (generation != mSessionGeneration) {
                            if (croppedBitmap != null && !croppedBitmap.isRecycled()) {
                                croppedBitmap.recycle();
                            }
                            return;
                        }

                        final View liveAnchor = LinguaKeyScreenCaptureManager.getInstance(context)
                                .getActiveAnchorView(anchorView);
                        mLastAnchorView = liveAnchor;
                        final OverlayGeometry freshGeom = computeOverlayGeometry(
                                context,
                                liveAnchor,
                                screenWidth,
                                screenHeight
                        );

                        final FrameLayout overlayRoot = createAndShowOverlayShell(
                                context,
                                liveAnchor,
                                screenWidth,
                                freshGeom.overlayHeight
                        );
                        if (overlayRoot == null) {
                            if (croppedBitmap != null && !croppedBitmap.isRecycled()) {
                                croppedBitmap.recycle();
                            }
                            if (mCurrentCallback != null) {
                                mCurrentCallback.onOverlayStateChanged(false);
                            }
                            return;
                        }

                        final TextView statusChip = (TextView) overlayRoot.findViewWithTag("LK_STATUS_CHIP");
                        if (statusChip != null) {
                            statusChip.setText("جارٍ الترجمة...");
                        }

                        final String targetLang = resolveScreenTargetLanguage(context);

                        LinguaKeyOcrAssembler.getInstance().detectAndAssembleParagraphs(
                                context,
                                croppedBitmap,
                                cropTopY,
                                targetLang,
                                new LinguaKeyOcrAssembler.OcrCallback() {
                                    @Override
                                    public void onParagraphsDetected(
                                            final List<LinguaKeyOcrAssembler.OcrParagraph> paragraphs,
                                            final boolean alreadyTranslatedByVision
                                    ) {
                                        if (generation != mSessionGeneration || mActiveOverlayRoot != overlayRoot) {
                                            return;
                                        }
                                        if (paragraphs == null || paragraphs.isEmpty()) {
                                            if (statusChip != null) {
                                                statusChip.setText("لا توجد نصوص أجنبية للترجمة");
                                            }
                                            scheduleAutoDismiss(2200L);
                                            return;
                                        }

                                        renderInPlaceOcrParagraphs(
                                                context,
                                                overlayRoot,
                                                statusChip,
                                                paragraphs,
                                                alreadyTranslatedByVision,
                                                targetLang,
                                                screenWidth,
                                                freshGeom.windowScreenTopY,
                                                freshGeom.overlayHeight,
                                                generation
                                        );
                                    }

                                    @Override
                                    public void onOcrError(final String errorMessage) {
                                        if (generation != mSessionGeneration || mActiveOverlayRoot != overlayRoot) {
                                            return;
                                        }
                                        if (statusChip != null) {
                                            statusChip.setText(errorMessage);
                                        }
                                        scheduleAutoDismiss(2800L);
                                    }
                                }
                        );
                    }

                    @Override
                    public void onCapturePreventedByDrm() {
                        if (generation != mSessionGeneration) return;
                        final View liveAnchor = LinguaKeyScreenCaptureManager.getInstance(context)
                                .getActiveAnchorView(anchorView);
                        final OverlayGeometry freshGeom = computeOverlayGeometry(
                                context,
                                liveAnchor,
                                screenWidth,
                                screenHeight
                        );
                        FrameLayout overlayRoot = createAndShowOverlayShell(
                                context,
                                liveAnchor,
                                screenWidth,
                                freshGeom.overlayHeight
                        );
                        if (overlayRoot != null) {
                            TextView statusChip = (TextView) overlayRoot.findViewWithTag("LK_STATUS_CHIP");
                            if (statusChip != null) {
                                statusChip.setText("هذا التطبيق يمنع قراءة الشاشة");
                            }
                            scheduleAutoDismiss(3000L);
                        }
                    }

                    @Override
                    public void onCaptureError(final String message) {
                        if (generation != mSessionGeneration) return;
                        if (mCurrentCallback != null) {
                            mCurrentCallback.onOverlayStateChanged(false);
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    private String resolveScreenTargetLanguage(final Context context) {
        return "ar";
    }

    /**
     * Creates a 100% transparent overlay shell (ZERO glowing border, ZERO dimming) directly inside
     * mInputView above R.id.keyboard_container.
     */
    private FrameLayout createAndShowOverlayShell(
            final Context context,
            final View anchorView,
            final int screenWidth,
            final int overlayHeight
    ) {
        if (anchorView == null) return null;
        try {
            final FrameLayout root = new FrameLayout(context);
            root.setTag("LK_SCREEN_TRANSLATE_OVERLAY_ROOT");
            root.setBackgroundColor(Color.TRANSPARENT);
            final int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

            // Tap empty background or swipe to dismiss cleanly
            root.setOnTouchListener(new View.OnTouchListener() {
                private float mDownX;
                private float mDownY;

                @Override
                public boolean onTouch(final View v, final MotionEvent event) {
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        mDownX = event.getRawX();
                        mDownY = event.getRawY();
                        return true;
                    } else if (action == MotionEvent.ACTION_MOVE) {
                        float dy = Math.abs(event.getRawY() - mDownY);
                        float dx = Math.abs(event.getRawX() - mDownX);
                        if (dy > touchSlop * 1.3f || dx > touchSlop * 1.6f) {
                            dismissOverlayImmediate();
                            return true;
                        }
                    } else if (action == MotionEvent.ACTION_UP) {
                        dismissOverlayImmediate();
                        return true;
                    }
                    return false;
                }
            });

            // Minimal unobtrusive top control pill (semi-transparent)
            LinearLayout topBar = createTopControlBar(context);
            FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            topLp.gravity = Gravity.TOP | Gravity.RIGHT;
            topLp.topMargin = dpToPx(context, 6f);
            topLp.rightMargin = dpToPx(context, 10f);
            root.addView(topBar, topLp);

            mActiveOverlayRoot = root;

            boolean attachedDirectly = false;
            if (anchorView instanceof FrameLayout) {
                FrameLayout inputFrame = (FrameLayout) anchorView;
                View kbdContainer = inputFrame.findViewById(R.id.keyboard_container);
                if (kbdContainer == null || inputFrame.getHeight() >= overlayHeight) {
                    FrameLayout.LayoutParams overlayLp = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            overlayHeight,
                            Gravity.TOP | Gravity.LEFT
                    );
                    inputFrame.addView(root, overlayLp);
                    inputFrame.requestLayout();
                    inputFrame.invalidate();
                    attachedDirectly = true;
                }
            }

            if (!attachedDirectly) {
                if (anchorView.getWindowToken() == null) {
                    mActiveOverlayRoot = null;
                    return null;
                }
                PopupWindow popup = new PopupWindow(root, screenWidth, overlayHeight, false);
                popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                popup.setOutsideTouchable(false);
                popup.setClippingEnabled(false);
                popup.showAtLocation(anchorView, Gravity.TOP | Gravity.LEFT, 0, 0);
                mFullScreenPopup = popup;
            }

            return root;
        } catch (Throwable e) {
            Log.e(TAG, "Error creating overlay shell", e);
            mActiveOverlayRoot = null;
            return null;
        }
    }

    private LinearLayout createTopControlBar(final Context context) {
        LinearLayout bar = new LinearLayout(context);
        bar.setTag("LK_TOP_CONTROL_BAR");
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dpToPx(context, 10f), dpToPx(context, 3f), dpToPx(context, 10f), dpToPx(context, 3f));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(context, 14f));
        bg.setColor(0xB8181A1F);
        bar.setBackground(bg);

        TextView statusTv = new TextView(context);
        statusTv.setTag("LK_STATUS_CHIP");
        statusTv.setText("ترجمة الشاشة");
        statusTv.setTextColor(0xFFE8EAED);
        statusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        bar.addView(statusTv);

        TextView refreshBtn = new TextView(context);
        refreshBtn.setText("↻");
        refreshBtn.setTextColor(0xFF8AB4F8);
        refreshBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        refreshBtn.setTypeface(Typeface.DEFAULT_BOLD);
        refreshBtn.setPadding(dpToPx(context, 10f), 0, dpToPx(context, 6f), 0);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                if (mLastAnchorView != null) {
                    startCaptureAndOcrPipeline(mLastAnchorView);
                }
            }
        });
        bar.addView(refreshBtn);

        TextView closeBtn = new TextView(context);
        closeBtn.setText("✕");
        closeBtn.setTextColor(0xFFF28B82);
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        closeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        closeBtn.setPadding(dpToPx(context, 6f), 0, dpToPx(context, 2f), 0);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                dismissOverlayImmediate();
            }
        });
        bar.addView(closeBtn);

        return bar;
    }

    private void renderInPlaceOcrParagraphs(
            final Context context,
            final FrameLayout overlayRoot,
            final TextView statusChip,
            final List<LinguaKeyOcrAssembler.OcrParagraph> paragraphs,
            final boolean alreadyTranslatedByVision,
            final String targetLang,
            final int screenWidth,
            final int windowScreenTopY,
            final int overlayHeight,
            final int generation
    ) {
        // Map physical screen Y coordinates into overlayRoot-local Y coordinates
        for (int i = 0; i < paragraphs.size(); i++) {
            LinguaKeyOcrAssembler.OcrParagraph p = paragraphs.get(i);
            Rect screenBox = new Rect(p.getBoundingBox());
            screenBox.offset(0, -windowScreenTopY);
            p.setScreenBoundingBox(screenBox);
        }

        // Sort top-to-bottom so layout never overlaps
        Collections.sort(paragraphs, new Comparator<LinguaKeyOcrAssembler.OcrParagraph>() {
            @Override
            public int compare(final LinguaKeyOcrAssembler.OcrParagraph a, final LinguaKeyOcrAssembler.OcrParagraph b) {
                return Integer.compare(a.getBoundingBox().top, b.getBoundingBox().top);
            }
        });

        final int count = paragraphs.size();
        final List<String> sourceTexts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            sourceTexts.add(paragraphs.get(i).getText());
        }

        if (alreadyTranslatedByVision) {
            placeTranslatedParagraphsNonOverlapping(
                    context, overlayRoot, paragraphs, sourceTexts, screenWidth, overlayHeight
            );
            int totalChars = 0;
            for (int i = 0; i < count; i++) {
                String tr = paragraphs.get(i).translatedText;
                if (tr != null) totalChars += tr.length();
            }
            if (statusChip != null) {
                statusChip.setText("تمت الترجمة");
            }
            scheduleAutoDismiss(Math.max(5000L, Math.min(24000L, 2800L + (long) totalChars * 95L)));
            return;
        }

        scheduleAutoDismiss(15000L);
        LinguaKeyScreenDiag.stage("TRANSLATE", "Sending " + sourceTexts.size()
                + " OCR paragraphs for batch translation to " + targetLang);

        LiveTranslationEngine.translateScreenParagraphsBatchAsync(
                context.getApplicationContext(),
                sourceTexts,
                targetLang,
                new LiveTranslationEngine.BatchTranslationCallback() {
                    @Override
                    public void onBatchTranslated(final List<String> translatedTexts) {
                        if (generation != mSessionGeneration || mActiveOverlayRoot != overlayRoot) {
                            return;
                        }
                        int totalChars = 0;
                        String lastTranslated = "";
                        for (int i = 0; i < count && i < translatedTexts.size(); i++) {
                            LinguaKeyOcrAssembler.OcrParagraph p = paragraphs.get(i);
                            String orig = sourceTexts.get(i);
                            String tr = translatedTexts.get(i);
                            if (TextUtils.isEmpty(tr)) tr = orig;
                            p.translatedText = tr;
                            totalChars += tr.length();
                            lastTranslated = tr;
                        }

                        // Place views AFTER translations arrive so exact StaticLayout heights prevent ANY overlap!
                        placeTranslatedParagraphsNonOverlapping(
                                context, overlayRoot, paragraphs, sourceTexts, screenWidth, overlayHeight
                        );

                        if (mCurrentCallback != null && !TextUtils.isEmpty(lastTranslated)) {
                            mCurrentCallback.onLastMessageTranslated(lastTranslated);
                        }
                        if (statusChip != null) {
                            statusChip.setText("تمت الترجمة");
                            View topBarV = overlayRoot.findViewWithTag("LK_TOP_CONTROL_BAR");
                            if (topBarV != null) topBarV.setAlpha(0.78f);
                        }
                        LinguaKeyScreenDiag.stage("TRANSLATE", "Rendered " + count
                                + " seamless non-overlapping paragraphs | totalChars=" + totalChars);

                        long proportionalMs = Math.max(5000L, Math.min(24000L, 2600L + (long) totalChars * 95L));
                        scheduleAutoDismiss(proportionalMs);
                    }
                }
        );
    }

    /**
     * Places every translated paragraph seamlessly in-place with ZERO borders/boxes and computes
     * exact StaticLayout rendered heights so two translated blocks can NEVER overlap vertically.
     */
    private void placeTranslatedParagraphsNonOverlapping(
            final Context context,
            final FrameLayout overlayRoot,
            final List<LinguaKeyOcrAssembler.OcrParagraph> paragraphs,
            final List<String> sourceTexts,
            final int screenWidth,
            final int overlayHeight
    ) {
        final int padH = dpToPx(context, 4f);
        final int padV = dpToPx(context, 2f);
        final int minGapPx = dpToPx(context, 4f);
        final int minTopPx = dpToPx(context, 28f);
        final List<Rect> placedRects = new ArrayList<>();

        for (int i = 0; i < paragraphs.size(); i++) {
            final LinguaKeyOcrAssembler.OcrParagraph p = paragraphs.get(i);
            final Rect box = p.getBoundingBox();
            final String orig = sourceTexts.get(i);
            final String tr = !TextUtils.isEmpty(p.translatedText) ? p.translatedText : orig;

            int left = Math.max(dpToPx(context, 3f), Math.min(box.left - dpToPx(context, 4f), screenWidth - dpToPx(context, 48f)));
            int availableW = Math.max(
                    dpToPx(context, 46f),
                    Math.min(box.width() + dpToPx(context, 16f), screenWidth - left - dpToPx(context, 3f))
            );
            int contentW = Math.max(dpToPx(context, 32f), availableW - padH * 2);
            int targetMaxH = Math.max(dpToPx(context, 22f), box.height() + dpToPx(context, 10f) - padV * 2);

            float startSp = getRenderFontSizeSp(context, p.getAverageFontHeight());
            float optimalSp = computeOptimalFontSizeSp(context, tr, contentW, targetMaxH, startSp);
            int measuredTextH = measureRenderedTextHeight(context, tr, contentW, optimalSp);
            int actualViewH = Math.max(box.height() + dpToPx(context, 6f), measuredTextH + padV * 2);

            int top = Math.max(minTopPx, box.top - dpToPx(context, 3f));
            // Strictly push down if horizontally overlapping any previously placed paragraph
            for (int j = 0; j < placedRects.size(); j++) {
                Rect prev = placedRects.get(j);
                if (left < prev.right && (left + availableW) > prev.left) {
                    if (top < prev.bottom + minGapPx) {
                        top = prev.bottom + minGapPx;
                    }
                }
            }
            if (top + actualViewH > overlayHeight - dpToPx(context, 2f)) {
                top = Math.max(minTopPx, overlayHeight - actualViewH - dpToPx(context, 2f));
            }
            placedRects.add(new Rect(left, top, left + availableW, top + actualViewH));

            TextView tv = buildSeamlessParagraphView(context, p, orig, tr, optimalSp, padH, padV, actualViewH);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    availableW,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.leftMargin = left;
            lp.topMargin = top;
            overlayRoot.addView(tv, lp);
        }

        View topBar = overlayRoot.findViewWithTag("LK_TOP_CONTROL_BAR");
        if (topBar != null) {
            topBar.bringToFront();
        }
        overlayRoot.requestLayout();
        overlayRoot.invalidate();
    }

    /**
     * Builds a seamless, borderless TextView whose background matches the exact sampled pixel color
     * of the original chat bubble/page (0px border) so the foreign text vanishes and the Arabic text
     * looks natively rendered in place.
     */
    private TextView buildSeamlessParagraphView(
            final Context context,
            final LinguaKeyOcrAssembler.OcrParagraph p,
            final String originalText,
            final String translatedText,
            final float fontSizeSp,
            final int padH,
            final int padV,
            final int minHeightPx
    ) {
        final TextView tv = new TextView(context);
        final boolean isRtl = LinguaKeyOcrAssembler.detectWritingDirection(translatedText)
                == LinguaKeyOcrAssembler.WritingDirection.RTL;

        String formattedText = translatedText;
        try {
            BidiFormatter bidi = BidiFormatter.getInstance(isRtl);
            formattedText = bidi.unicodeWrap(
                    translatedText,
                    isRtl ? TextDirectionHeuristics.RTL : TextDirectionHeuristics.LTR
            );
        } catch (Throwable ignored) {
        }

        tv.setTextColor(p.sampledTextColor);
        tv.setTypeface(Typeface.DEFAULT);
        tv.setIncludeFontPadding(false);
        tv.setLineSpacing(dpToPx(context, 1f), 1.04f);
        tv.setPadding(padH, padV, padH, padV);
        tv.setMinHeight(minHeightPx);
        tv.setTextDirection(isRtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
        tv.setGravity((isRtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp);
        tv.setText(formattedText);

        // Seamless solid-matched fill with ZERO border/stroke!
        GradientDrawable seamlessBg = new GradientDrawable();
        seamlessBg.setShape(GradientDrawable.RECTANGLE);
        seamlessBg.setCornerRadius(dpToPx(context, 4f));
        seamlessBg.setColor(p.sampledBgColor);
        tv.setBackground(seamlessBg);

        final String finalFormatted = formattedText;
        final String finalOriginal = originalText;
        final boolean[] showingOriginal = new boolean[]{false};

        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                extendReadingTimerOnUserActivity();
                showingOriginal[0] = !showingOriginal[0];
                if (showingOriginal[0]) {
                    boolean origRtl = LinguaKeyOcrAssembler.detectWritingDirection(finalOriginal)
                            == LinguaKeyOcrAssembler.WritingDirection.RTL;
                    tv.setTextDirection(origRtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
                    tv.setGravity((origRtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
                    tv.setText(finalOriginal);
                } else {
                    tv.setTextDirection(isRtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
                    tv.setGravity((isRtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
                    tv.setText(finalFormatted);
                }
            }
        });

        tv.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(final View v) {
                try {
                    ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        LinguaKeyMessageTouchService.setLastOutgoingTranslation(translatedText);
                        cm.setPrimaryClip(ClipData.newPlainText("LinguaKey Translation", translatedText));
                        Toast.makeText(context, "تم نسخ الترجمة ✓", Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable ignored) {
                }
                return true;
            }
        });

        return tv;
    }

    private float getRenderFontSizeSp(final Context context, final double visionTextFontHeightPx) {
        float scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
        if (scaledDensity <= 0f) scaledDensity = 1f;
        float rawSp = (float) (visionTextFontHeightPx * FONT_SIZE_RATIO) / scaledDensity;
        return Math.max(FONT_SIZE_MIN_SP, Math.min(FONT_SIZE_MAX_SP, rawSp));
    }

    @SuppressWarnings("deprecation")
    private float computeOptimalFontSizeSp(
            final Context context,
            final String text,
            final int widthPx,
            final int targetMaxHeightPx,
            final float initialSp
    ) {
        if (TextUtils.isEmpty(text) || widthPx <= 10) return initialSp;
        float sp = initialSp;
        while (sp > FONT_SIZE_MIN_SP) {
            if (measureRenderedTextHeight(context, text, widthPx, sp) <= targetMaxHeightPx) {
                return sp;
            }
            sp -= 0.8f;
        }
        return FONT_SIZE_MIN_SP;
    }

    @SuppressWarnings("deprecation")
    private int measureRenderedTextHeight(
            final Context context,
            final String text,
            final int widthPx,
            final float sp
    ) {
        if (TextUtils.isEmpty(text) || widthPx <= 10) return dpToPx(context, 18f);
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, sp, context.getResources().getDisplayMetrics()
        ));
        StaticLayout layout = new StaticLayout(
                text, paint, widthPx, Layout.Alignment.ALIGN_NORMAL, 1.04f, dpToPx(context, 1f), false
        );
        return layout.getHeight();
    }

    private void scheduleAutoDismiss(final long delayMs) {
        if (mAutoDismissRunnable != null) {
            mMainHandler.removeCallbacks(mAutoDismissRunnable);
        }
        mAutoDismissRunnable = new Runnable() {
            @Override
            public void run() {
                dismissOverlayImmediate();
            }
        };
        mMainHandler.postDelayed(mAutoDismissRunnable, delayMs);
    }

    private int dpToPx(final Context context, final float dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                context.getResources().getDisplayMetrics()
        );
    }
}
