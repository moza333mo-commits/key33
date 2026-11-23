package com.linguakey.keyboard.latin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import com.linguakey.keyboard.R;

import java.nio.ByteBuffer;

/**
 * Rock-Solid High-Speed Screen Capture Engine for LinguaKey (v8.5).
 *
 * Key Stability Improvements:
 * 1. Strictly obeys Android 14+ single-use MediaProjection token rules: consumes the Intent token
 *    once to build a live MediaProjection + VirtualDisplay, then reuses that live VirtualDisplay
 *    via pauseFrames()/resumeFrames() without ever calling getMediaProjection() twice on a used token.
 * 2. Automatic self-healing recovery: if the system revokes MediaProjection or VirtualDisplay throws,
 *    cleanly resets state and re-requests permission once (guarded against infinite loops).
 * 3. Watchdog timeout protection guarantees mCaptureOrPermissionInFlight NEVER stays stuck.
 * 4. Safe bitmap allocation & recycling with zero memory leaks.
 */
public final class LinguaKeyScreenCaptureManager {
    private static final String TAG = "LKScreenCapture";
    private static final long CAPTURE_FRAME_TIMEOUT_MS = 3500L;
    private static final long FGS_START_WATCHDOG_MS = 4500L;

    public interface CaptureCallback {
        void onScreenCaptured(Bitmap croppedBitmap, int cropTopY, int fullScreenWidth, int fullScreenHeight);
        void onCapturePreventedByDrm();
        void onCaptureError(String message);
    }

    private static LinguaKeyScreenCaptureManager sInstance;

    private final Context mContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private int mResultCode = Activity.RESULT_CANCELED;
    private Intent mUnusedProjectionToken = null;
    private boolean mForegroundServiceReady = false;

    private MediaProjection mMediaProjection;
    private MediaProjection.Callback mProjectionStopCallback;
    private ImageReader mImageReader;
    private VirtualDisplay mVirtualDisplay;
    private int mActiveDisplayWidth = 0;
    private int mActiveDisplayHeight = 0;

    private volatile boolean mCaptureOrPermissionInFlight = false;
    private boolean mAwaitingFrame = false;
    private int mBlankFrameRetryCount = 0;
    private boolean mRetriedPermissionOnce = false;
    private View mPendingAnchorView;
    private int mPendingKeyboardTopY;
    private CaptureCallback mPendingCallback;
    private Runnable mFrameTimeoutRunnable;
    private Runnable mRetryPollRunnable;
    private Runnable mFgsWatchdogRunnable;

    private LinguaKeyScreenCaptureManager(final Context context) {
        mContext = context.getApplicationContext();
    }

    public static synchronized LinguaKeyScreenCaptureManager getInstance(final Context context) {
        if (sInstance == null && context != null) {
            sInstance = new LinguaKeyScreenCaptureManager(context);
        }
        return sInstance;
    }

    public boolean hasActiveProjection() {
        return mMediaProjection != null && mVirtualDisplay != null && mImageReader != null;
    }

    public boolean hasUnusedToken() {
        return mResultCode == Activity.RESULT_OK && mUnusedProjectionToken != null;
    }

    public boolean isCaptureInProgress() {
        return mCaptureOrPermissionInFlight || mAwaitingFrame;
    }

    public void resetSessionCompletely() {
        cancelAllRunnables();
        clearResources();
        mUnusedProjectionToken = null;
        mResultCode = Activity.RESULT_CANCELED;
        mForegroundServiceReady = false;
        mCaptureOrPermissionInFlight = false;
        mAwaitingFrame = false;
        mRetriedPermissionOnce = false;
        mPendingCallback = null;
        LinguaKeyMediaProjectionService.stopService(mContext);
    }

    public void updateAnchorViewIfActive(final View anchorView) {
        if (anchorView != null) {
            mPendingAnchorView = anchorView;
        }
    }

    public View getActiveAnchorView(final View fallback) {
        if (mPendingAnchorView != null && mPendingAnchorView.getWindowToken() != null) {
            return mPendingAnchorView;
        }
        return fallback;
    }

    public void onPermissionGranted(final int resultCode, final Intent token) {
        mResultCode = resultCode;
        mUnusedProjectionToken = token;
        mCaptureOrPermissionInFlight = true;
        LinguaKeyScreenDiag.stage("PERMISSION", "MediaProjection permission granted resultCode=" + resultCode);
        armFgsWatchdog();
    }

    public void onPermissionDenied() {
        cancelAllRunnables();
        mCaptureOrPermissionInFlight = false;
        mAwaitingFrame = false;
        mRetriedPermissionOnce = false;
        CaptureCallback cb = mPendingCallback;
        mPendingCallback = null;
        if (cb != null) {
            cb.onCaptureError("تم إلغاء إذن قراءة الشاشة");
        }
    }

    public void onProjectionServiceFailed() {
        cancelAllRunnables();
        clearResources();
        mForegroundServiceReady = false;
        mUnusedProjectionToken = null;
        mResultCode = Activity.RESULT_CANCELED;
        mCaptureOrPermissionInFlight = false;
        mAwaitingFrame = false;
        mRetriedPermissionOnce = false;
        CaptureCallback cb = mPendingCallback;
        mPendingCallback = null;
        if (cb != null) {
            cb.onCaptureError("تعذر تشغيل خدمة التقاط الشاشة — حاول مرة أخرى");
        }
    }

    public void onForegroundServiceReady() {
        cancelFgsWatchdog();
        mForegroundServiceReady = true;
        LinguaKeyScreenDiag.stage("FGS", "LinguaKeyMediaProjectionService foreground ready");
        if (mPendingCallback != null && mPendingAnchorView != null) {
            final View anchor = mPendingAnchorView;
            final int kbdTop = mPendingKeyboardTopY;
            final CaptureCallback cb = mPendingCallback;
            // Wait 280ms for the system MediaProjection dialog to dismiss cleanly
            mMainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mPendingCallback == cb) {
                        startOrResumeCaptureInternal(getActiveAnchorView(anchor), kbdTop, cb);
                    }
                }
            }, 280L);
        } else {
            mCaptureOrPermissionInFlight = false;
        }
    }

    public void requestScreenCapture(
            final View anchorView,
            final int keyboardTopY,
            final CaptureCallback callback
    ) {
        cancelAllRunnables();
        mPendingAnchorView = anchorView;
        mPendingKeyboardTopY = keyboardTopY;
        mPendingCallback = callback;
        mCaptureOrPermissionInFlight = true;
        mBlankFrameRetryCount = 0;
        mRetriedPermissionOnce = false;

        DisplayMetrics realMetrics = getRealDisplayMetrics();
        if (mVirtualDisplay != null
                && (realMetrics.widthPixels != mActiveDisplayWidth
                || realMetrics.heightPixels != mActiveDisplayHeight)) {
            recreateVirtualDisplayForOrientation(realMetrics);
        }

        // Case 1: Live projection & VirtualDisplay are already active — instant capture!
        if (hasActiveProjection() && LinguaKeyMediaProjectionService.isRunning()) {
            startOrResumeCaptureInternal(anchorView, keyboardTopY, callback);
            return;
        }

        // Case 2: We have an unused fresh token and FGS is already running
        if (hasUnusedToken() && mForegroundServiceReady && LinguaKeyMediaProjectionService.isRunning()) {
            startOrResumeCaptureInternal(anchorView, keyboardTopY, callback);
            return;
        }

        // Case 3: We have an unused fresh token but FGS needs to start first
        if (hasUnusedToken()) {
            armFgsWatchdog();
            LinguaKeyMediaProjectionService.startService(mContext);
            return;
        }

        // Case 4: Need a fresh MediaProjection consent token (Android 14+ single-use safe)
        launchPermissionActivity(callback);
    }

    private void launchPermissionActivity(final CaptureCallback callback) {
        try {
            Intent permIntent = new Intent(mContext, ScreenCapturePermissionActivity.class);
            permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            mContext.startActivity(permIntent);
        } catch (Throwable e) {
            Log.e(TAG, "Unable to start ScreenCapturePermissionActivity", e);
            mCaptureOrPermissionInFlight = false;
            mPendingCallback = null;
            if (callback != null) {
                callback.onCaptureError("تعذر طلب إذن التقاط الشاشة");
            }
        }
    }

    private void armFgsWatchdog() {
        cancelFgsWatchdog();
        mFgsWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                if (!mForegroundServiceReady && mCaptureOrPermissionInFlight) {
                    Log.w(TAG, "FGS startup watchdog expired");
                    onProjectionServiceFailed();
                }
            }
        };
        mMainHandler.postDelayed(mFgsWatchdogRunnable, FGS_START_WATCHDOG_MS);
    }

    private void cancelFgsWatchdog() {
        if (mFgsWatchdogRunnable != null) {
            mMainHandler.removeCallbacks(mFgsWatchdogRunnable);
            mFgsWatchdogRunnable = null;
        }
    }

    private void startOrResumeCaptureInternal(
            final View anchorView,
            final int keyboardTopY,
            final CaptureCallback callback
    ) {
        cancelTimeoutAndPoll();
        mPendingAnchorView = anchorView;
        mPendingKeyboardTopY = keyboardTopY;
        mPendingCallback = callback;
        mCaptureOrPermissionInFlight = true;
        mBlankFrameRetryCount = 0;

        if (!hasActiveProjection()) {
            if (!initializeProjectionPipeline()) {
                return;
            }
        } else {
            try {
                Image stale = mImageReader.acquireLatestImage();
                if (stale != null) stale.close();
            } catch (Throwable ignored) {
            }
            if (!resumeFrames()) {
                return;
            }
        }

        mAwaitingFrame = true;

        if (anchorView != null) {
            anchorView.postInvalidate();
        }

        mRetryPollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!mAwaitingFrame || mImageReader == null) return;
                if (!tryAcquireAndDeliverFrame()) {
                    if (anchorView != null) anchorView.postInvalidate();
                    mMainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (mAwaitingFrame && mImageReader != null) {
                                tryAcquireAndDeliverFrame();
                            }
                        }
                    }, 110L);
                }
            }
        };
        mMainHandler.postDelayed(mRetryPollRunnable, 65L);

        mFrameTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (!mAwaitingFrame) return;
                if (tryAcquireAndDeliverFrame()) return;

                Log.w(TAG, "No capture frame within timeout — resetting projection cleanly");
                mAwaitingFrame = false;
                mCaptureOrPermissionInFlight = false;
                clearResources();
                mUnusedProjectionToken = null;
                mResultCode = Activity.RESULT_CANCELED;
                mForegroundServiceReady = false;
                LinguaKeyMediaProjectionService.stopService(mContext);

                CaptureCallback cb = mPendingCallback;
                mPendingCallback = null;
                if (cb != null) {
                    cb.onCaptureError("انتهت مهلة التقاط الشاشة — اضغط مجدداً للمحاولة");
                }
            }
        };
        mMainHandler.postDelayed(mFrameTimeoutRunnable, CAPTURE_FRAME_TIMEOUT_MS);
    }

    private boolean initializeProjectionPipeline() {
        clearResources();
        if (!hasUnusedToken()) {
            if (!mRetriedPermissionOnce) {
                mRetriedPermissionOnce = true;
                launchPermissionActivity(mPendingCallback);
            } else {
                mCaptureOrPermissionInFlight = false;
                CaptureCallback cb = mPendingCallback;
                mPendingCallback = null;
                if (cb != null) {
                    cb.onCaptureError("يرجى منح إذن التقاط الشاشة للمتابعة");
                }
            }
            return false;
        }

        try {
            DisplayMetrics realMetrics = getRealDisplayMetrics();
            final int width = Math.max(100, realMetrics.widthPixels);
            final int height = Math.max(100, realMetrics.heightPixels);
            final int dpi = Math.max(120, realMetrics.densityDpi);
            mActiveDisplayWidth = width;
            mActiveDisplayHeight = height;

            MediaProjectionManager mpm =
                    (MediaProjectionManager) mContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null) {
                throw new IllegalStateException("MediaProjectionManager unavailable");
            }

            // Consume the token ONCE (Android 14+ forbids calling getMediaProjection twice on the same token)
            final Intent tokenToConsume = mUnusedProjectionToken;
            final int codeToConsume = mResultCode;
            mUnusedProjectionToken = null;
            mResultCode = Activity.RESULT_CANCELED;

            mMediaProjection = mpm.getMediaProjection(codeToConsume, tokenToConsume);
            if (mMediaProjection == null) {
                if (!mRetriedPermissionOnce) {
                    mRetriedPermissionOnce = true;
                    launchPermissionActivity(mPendingCallback);
                } else {
                    mCaptureOrPermissionInFlight = false;
                    CaptureCallback cb = mPendingCallback;
                    mPendingCallback = null;
                    if (cb != null) cb.onCaptureError("تعذر تهيئة التقاط الشاشة");
                }
                return false;
            }

            mProjectionStopCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.w(TAG, "MediaProjection stopped by system");
                    cancelAllRunnables();
                    clearResources();
                    mUnusedProjectionToken = null;
                    mResultCode = Activity.RESULT_CANCELED;
                    mForegroundServiceReady = false;
                    mCaptureOrPermissionInFlight = false;
                    mAwaitingFrame = false;
                    LinguaKeyMediaProjectionService.stopService(mContext);
                }
            };
            mMediaProjection.registerCallback(mProjectionStopCallback, mMainHandler);

            mImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            mImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(final ImageReader reader) {
                    if (mAwaitingFrame) {
                        tryAcquireAndDeliverFrame();
                    } else {
                        try {
                            Image extra = reader.acquireLatestImage();
                            if (extra != null) extra.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }, mMainHandler);

            mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                    "LinguaKeyScreenTranslator",
                    width,
                    height,
                    dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mImageReader.getSurface(),
                    null,
                    mMainHandler
            );
            LinguaKeyScreenDiag.stage("CAPTURE", "VirtualDisplay created " + width + "x" + height + " dpi=" + dpi);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Failed to initialize MediaProjection pipeline", e);
            clearResources();
            mUnusedProjectionToken = null;
            mResultCode = Activity.RESULT_CANCELED;
            mForegroundServiceReady = false;
            LinguaKeyMediaProjectionService.stopService(mContext);

            if (!mRetriedPermissionOnce) {
                mRetriedPermissionOnce = true;
                launchPermissionActivity(mPendingCallback);
            } else {
                mCaptureOrPermissionInFlight = false;
                CaptureCallback cb = mPendingCallback;
                mPendingCallback = null;
                if (cb != null) {
                    cb.onCaptureError("تعذر تشغيل التقاط الشاشة — حاول مجدداً");
                }
            }
            return false;
        }
    }

    private void recreateVirtualDisplayForOrientation(final DisplayMetrics realMetrics) {
        if (mMediaProjection == null || mVirtualDisplay == null) return;
        try {
            if (mImageReader != null) {
                mImageReader.setOnImageAvailableListener(null, null);
                mImageReader.close();
                mImageReader = null;
            }
            mActiveDisplayWidth = Math.max(100, realMetrics.widthPixels);
            mActiveDisplayHeight = Math.max(100, realMetrics.heightPixels);
            mImageReader = ImageReader.newInstance(
                    mActiveDisplayWidth,
                    mActiveDisplayHeight,
                    PixelFormat.RGBA_8888,
                    2
            );
            mVirtualDisplay.resize(
                    mActiveDisplayWidth,
                    mActiveDisplayHeight,
                    Math.max(120, realMetrics.densityDpi)
            );
            mVirtualDisplay.setSurface(mImageReader.getSurface());
            mImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(final ImageReader reader) {
                    if (mAwaitingFrame) {
                        tryAcquireAndDeliverFrame();
                    } else {
                        try {
                            Image extra = reader.acquireLatestImage();
                            if (extra != null) extra.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }, mMainHandler);
        } catch (Throwable e) {
            Log.w(TAG, "VirtualDisplay resize failed, resetting", e);
            clearResources();
        }
    }

    private boolean tryAcquireAndDeliverFrame() {
        if (!mAwaitingFrame || mImageReader == null) return false;
        Image capturedImage = null;
        Bitmap rawBitmap = null;
        Bitmap cropped = null;
        try {
            capturedImage = mImageReader.acquireLatestImage();
            if (capturedImage == null) {
                return false;
            }

            final int width = capturedImage.getWidth();
            final int height = capturedImage.getHeight();
            if (width <= 0 || height <= 0) {
                return false;
            }
            Image.Plane[] planes = capturedImage.getPlanes();
            if (planes == null || planes.length == 0) {
                return false;
            }
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * width;

            int paddedWidth = width + (pixelStride > 0 ? (rowPadding / pixelStride) : 0);
            if (paddedWidth < width) paddedWidth = width;
            rawBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
            buffer.rewind();
            rawBitmap.copyPixelsFromBuffer(buffer);

            int kbdTopY = mPendingKeyboardTopY;
            if (mPendingAnchorView != null) {
                View kbdContainer = mPendingAnchorView.findViewById(R.id.keyboard_container);
                if (kbdContainer != null && kbdContainer.getHeight() > 0) {
                    int[] kbdLoc = new int[2];
                    kbdContainer.getLocationOnScreen(kbdLoc);
                    if (kbdLoc[1] > dpToPx(100)) {
                        kbdTopY = kbdLoc[1];
                    }
                }
            }
            if (kbdTopY <= dpToPx(100) || kbdTopY > height) {
                kbdTopY = (int) (height * 0.58f);
            }

            int statusBarH = getStatusBarHeight();
            int cropTopY = Math.min(height - dpToPx(80), Math.max(statusBarH, dpToPx(24)));
            if (cropTopY < 0) cropTopY = 0;
            int cropHeight = Math.max(dpToPx(80), kbdTopY - cropTopY - dpToPx(2));
            if (cropTopY + cropHeight > height) {
                cropHeight = Math.max(1, height - cropTopY);
            }

            cropped = Bitmap.createBitmap(rawBitmap, 0, cropTopY, width, cropHeight);
            if (rawBitmap != cropped) {
                rawBitmap.recycle();
                rawBitmap = null;
            }

            Bitmap cleanBitmap = removeAlphaChannel(cropped);
            if (cropped != cleanBitmap) {
                cropped.recycle();
                cropped = null;
            }

            if (isCapturePrevented(cleanBitmap)) {
                cleanBitmap.recycle();
                if (mBlankFrameRetryCount < 3) {
                    mBlankFrameRetryCount++;
                    if (mPendingAnchorView != null) {
                        mPendingAnchorView.postInvalidate();
                    }
                    mMainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (mAwaitingFrame && mImageReader != null) {
                                tryAcquireAndDeliverFrame();
                            }
                        }
                    }, 95L);
                    return false;
                }

                mAwaitingFrame = false;
                mCaptureOrPermissionInFlight = false;
                cancelTimeoutAndPoll();
                pauseFrames();
                final CaptureCallback cb = mPendingCallback;
                mPendingCallback = null;
                if (cb != null) {
                    cb.onCapturePreventedByDrm();
                }
                return true;
            }

            mAwaitingFrame = false;
            cancelTimeoutAndPoll();
            pauseFrames();

            final CaptureCallback cb = mPendingCallback;
            mPendingCallback = null;

            LinguaKeyScreenDiag.stage("CAPTURE", "Frame captured & cropped: " + cleanBitmap.getWidth() + "x" + cleanBitmap.getHeight() + " cropTopY=" + cropTopY + " kbdTopY=" + kbdTopY);
            deliverCapturedBitmapWhenWindowReady(cleanBitmap, cropTopY, width, height, cb, 0);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Error processing captured frame", e);
            if (rawBitmap != null && !rawBitmap.isRecycled()) rawBitmap.recycle();
            if (cropped != null && !cropped.isRecycled()) cropped.recycle();
            mAwaitingFrame = false;
            mCaptureOrPermissionInFlight = false;
            cancelTimeoutAndPoll();
            pauseFrames();
            CaptureCallback cb = mPendingCallback;
            mPendingCallback = null;
            if (cb != null) {
                cb.onCaptureError("حدث خطأ أثناء معالجة صورة الشاشة — حاول مرة أخرى");
            }
            return false;
        } finally {
            if (capturedImage != null) {
                try {
                    capturedImage.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void deliverCapturedBitmapWhenWindowReady(
            final Bitmap cleanBitmap,
            final int cropTopY,
            final int width,
            final int height,
            final CaptureCallback cb,
            final int attempt
    ) {
        if (cb == null) {
            mCaptureOrPermissionInFlight = false;
            if (!cleanBitmap.isRecycled()) cleanBitmap.recycle();
            return;
        }
        if (mPendingAnchorView != null && mPendingAnchorView.getWindowToken() == null && attempt < 6) {
            mMainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    deliverCapturedBitmapWhenWindowReady(cleanBitmap, cropTopY, width, height, cb, attempt + 1);
                }
            }, 100L);
            return;
        }
        mCaptureOrPermissionInFlight = false;
        cb.onScreenCaptured(cleanBitmap, cropTopY, width, height);
    }

    private boolean resumeFrames() {
        if (mVirtualDisplay != null && mImageReader != null) {
            try {
                mVirtualDisplay.setSurface(mImageReader.getSurface());
                return true;
            } catch (Throwable e) {
                Log.w(TAG, "resumeFrames failed, reinitializing", e);
                clearResources();
                return initializeProjectionPipeline();
            }
        }
        return false;
    }

    private void pauseFrames() {
        if (mVirtualDisplay != null) {
            try {
                mVirtualDisplay.setSurface(null);
            } catch (Throwable ignored) {
            }
        }
    }

    private Bitmap removeAlphaChannel(final Bitmap original) {
        Bitmap opaque = Bitmap.createBitmap(original.getWidth(), original.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(opaque);
        Paint paint = new Paint();
        canvas.drawBitmap(original, 0f, 0f, paint);
        return opaque;
    }

    private boolean isCapturePrevented(final Bitmap bitmap) {
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int checkW = (w * 3) / 5;
            int checkH = (h * 3) / 5;
            if (checkW <= 10 || checkH <= 10) return false;

            int startX = w / 5;
            int startY = h / 5;
            int firstPixel = bitmap.getPixel(startX, startY);
            int stepX = Math.max(4, checkW / 12);
            int stepY = Math.max(4, checkH / 12);

            for (int y = startY; y < startY + checkH; y += stepY) {
                for (int x = startX; x < startX + checkW; x += stepX) {
                    if (bitmap.getPixel(x, y) != firstPixel) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public DisplayMetrics getRealDisplayMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        try {
            WindowManager wm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                Display d = wm.getDefaultDisplay();
                if (d != null) {
                    d.getRealMetrics(dm);
                    if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                        return dm;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return mContext.getResources().getDisplayMetrics();
    }

    private int getStatusBarHeight() {
        int resId = mContext.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resId > 0 ? mContext.getResources().getDimensionPixelSize(resId) : dpToPx(24);
    }

    private int dpToPx(final int dp) {
        return Math.round(dp * mContext.getResources().getDisplayMetrics().density);
    }

    private void cancelAllRunnables() {
        cancelTimeoutAndPoll();
        cancelFgsWatchdog();
    }

    private void cancelTimeoutAndPoll() {
        if (mFrameTimeoutRunnable != null) {
            mMainHandler.removeCallbacks(mFrameTimeoutRunnable);
            mFrameTimeoutRunnable = null;
        }
        if (mRetryPollRunnable != null) {
            mMainHandler.removeCallbacks(mRetryPollRunnable);
            mRetryPollRunnable = null;
        }
    }

    public void clearResources() {
        if (mVirtualDisplay != null) {
            try {
                mVirtualDisplay.release();
            } catch (Throwable ignored) {
            }
            mVirtualDisplay = null;
        }
        if (mImageReader != null) {
            try {
                mImageReader.setOnImageAvailableListener(null, null);
                mImageReader.close();
            } catch (Throwable ignored) {
            }
            mImageReader = null;
        }
        if (mMediaProjection != null) {
            try {
                if (mProjectionStopCallback != null) {
                    mMediaProjection.unregisterCallback(mProjectionStopCallback);
                }
                mMediaProjection.stop();
            } catch (Throwable ignored) {
            }
            mMediaProjection = null;
            mProjectionStopCallback = null;
        }
    }
}
