package com.linguakey.keyboard.latin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.linguakey.keyboard.R;

/**
 * Hardened Foreground Service with foregroundServiceType="mediaProjection".
 * Ensures zero stuck states on Android 10–15 (API 29–35) by always notifying
 * LinguaKeyScreenCaptureManager on both startup success and startup failure.
 */
public final class LinguaKeyMediaProjectionService extends Service {
    private static final String TAG = "LKProjectionService";
    private static final String CHANNEL_ID = "linguakey_screen_translate_channel";
    private static final int NOTIFICATION_ID = 7401;
    public static final String ACTION_START = "com.linguakey.keyboard.action.START_PROJECTION";
    public static final String ACTION_STOP = "com.linguakey.keyboard.action.STOP_PROJECTION";

    private static volatile boolean sIsRunning = false;

    public static boolean isRunning() {
        return sIsRunning;
    }

    public static void startService(final Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(context.getApplicationContext(), LinguaKeyMediaProjectionService.class);
            intent.setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getApplicationContext().startForegroundService(intent);
            } else {
                context.getApplicationContext().startService(intent);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to start LinguaKeyMediaProjectionService", e);
            sIsRunning = false;
            LinguaKeyScreenCaptureManager.getInstance(context.getApplicationContext())
                    .onProjectionServiceFailed();
        }
    }

    public static void stopService(final Context context) {
        sIsRunning = false;
        if (context == null) return;
        try {
            Intent intent = new Intent(context.getApplicationContext(), LinguaKeyMediaProjectionService.class);
            context.getApplicationContext().stopService(intent);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            sIsRunning = false;
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            ensureNotificationChannel();
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                );
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            sIsRunning = true;
            LinguaKeyScreenCaptureManager.getInstance(getApplicationContext())
                    .onForegroundServiceReady();
        } catch (Throwable e) {
            Log.e(TAG, "Error starting foreground mediaProjection service", e);
            sIsRunning = false;
            LinguaKeyScreenCaptureManager.getInstance(getApplicationContext())
                    .onProjectionServiceFailed();
            stopSelf();
            return START_NOT_STICKY;
        }

        return START_NOT_STICKY;
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "ترجمة الشاشة الفورية",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("خدمة التقاط وترجمة نصوص الشاشة من داخل لوحة المفاتيح");
                channel.setShowBadge(false);
                channel.setSound(null, null);
                nm.createNotificationChannel(channel);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(R.drawable.ic_translate)
                .setContentTitle("LinguaKey — ترجمة الشاشة جاهزة")
                .setContentText("اضغط أيقونة ترجمة الشاشة في الكيبورد لترجمة أي محتوى فوراً")
                .setOngoing(true);
        return builder.build();
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        sIsRunning = false;
        stopForegroundCompat();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }
}
