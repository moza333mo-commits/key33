package com.linguakey.keyboard.latin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Transparent, zero-UI Activity for acquiring the MediaProjection screen capture token once per session.
 * Ported and adapted from screen-translator-master's ScreenCapturePermissionRequesterActivity.kt.
 *
 * - Uses MediaProjectionConfig.createConfigForDefaultDisplay() on Android 14+ (API 34+) with automatic
 *   fallback for custom ROMs so the user gets a single-tap full-screen share confirmation.
 * - Finishes immediately with zero transition animation so keyboard focus returns to the active app,
 *   and automatically triggers the pending screen capture & OCR translation as soon as the foreground
 *   mediaProjection service starts.
 */
public final class ScreenCapturePermissionActivity extends Activity {
    private static final String TAG = "LKCapturePermAct";
    private static final int REQ_CODE_SCREEN_CAPTURE = 4901;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);

        try {
            final MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null) {
                LinguaKeyScreenCaptureManager.getInstance(getApplicationContext()).onPermissionDenied();
                finishQuietly();
                return;
            }

            Intent captureIntent = null;
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    Class<?> configCls = Class.forName("android.media.projection.MediaProjectionConfig");
                    Method createDefault = configCls.getMethod("createConfigForDefaultDisplay");
                    Object configObj = createDefault.invoke(null);
                    Method createIntent = MediaProjectionManager.class.getMethod("createScreenCaptureIntent", configCls);
                    captureIntent = (Intent) createIntent.invoke(mpm, configObj);
                } catch (Throwable ignored) {
                    captureIntent = mpm.createScreenCaptureIntent();
                }
            } else {
                captureIntent = mpm.createScreenCaptureIntent();
            }

            startActivityForResult(captureIntent, REQ_CODE_SCREEN_CAPTURE);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to launch screen capture permission intent", e);
            LinguaKeyScreenCaptureManager.getInstance(getApplicationContext()).onPermissionDenied();
            finishQuietly();
        }
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CODE_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Intent clonedToken = (Intent) data.clone();
                LinguaKeyScreenCaptureManager.getInstance(getApplicationContext())
                        .onPermissionGranted(resultCode, clonedToken);
                LinguaKeyMediaProjectionService.startService(getApplicationContext());
            } else {
                LinguaKeyScreenCaptureManager.getInstance(getApplicationContext()).onPermissionDenied();
            }
            finishQuietly();
        }
    }

    private void finishQuietly() {
        finish();
        overridePendingTransition(0, 0);
    }
}
