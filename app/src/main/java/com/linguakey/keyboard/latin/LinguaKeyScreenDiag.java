package com.linguakey.keyboard.latin;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * Centralized stage-by-stage diagnostic logger for LinguaKey Screen Translation.
 * Emits structured Logcat entries under tag "LK_SCREEN_DIAG" AND retains the last 60 events
 * in an in-memory ring buffer so every stage (TAP -> PERMISSION -> FGS -> CAPTURE -> OCR -> TRANSLATE -> OVERLAY)
 * can be inspected and verified accurately.
 */
public final class LinguaKeyScreenDiag {
    public static final String TAG = "LK_SCREEN_DIAG";
    private static final int MAX_ENTRIES = 60;
    private static final LinkedList<String> sRingBuffer = new LinkedList<>();

    private LinguaKeyScreenDiag() {
    }

    public static void stage(final String stageName, final String message) {
        String entry = formatEntry("I", stageName, message);
        Log.i(TAG, entry);
        append(entry);
    }

    public static void warn(final String stageName, final String message) {
        String entry = formatEntry("W", stageName, message);
        Log.w(TAG, entry);
        append(entry);
    }

    public static void error(final String stageName, final String message, final Throwable t) {
        String suffix = (t != null && t.getMessage() != null) ? (" | ex=" + t.getClass().getSimpleName() + ": " + t.getMessage()) : "";
        String entry = formatEntry("E", stageName, message + suffix);
        if (t != null) {
            Log.e(TAG, entry, t);
        } else {
            Log.e(TAG, entry);
        }
        append(entry);
    }

    public static synchronized List<String> getRecentLogs() {
        return new ArrayList<>(sRingBuffer);
    }

    public static synchronized String getRecentLogsText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sRingBuffer.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(sRingBuffer.get(i));
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        sRingBuffer.clear();
    }

    private static synchronized void append(final String entry) {
        sRingBuffer.addLast(entry);
        while (sRingBuffer.size() > MAX_ENTRIES) {
            sRingBuffer.removeFirst();
        }
    }

    private static String formatEntry(final String level, final String stage, final String message) {
        String ts;
        try {
            ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        } catch (Throwable ignored) {
            ts = "00:00:00.000";
        }
        return "[" + ts + "][" + level + "][" + stage + "] " + (message != null ? message : "");
    }
}
