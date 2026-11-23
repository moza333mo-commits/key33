package com.linguakey.keyboard.latin;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.linguakey.keyboard.compat.PreferenceManagerCompat;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Hardware-backed AES-256-GCM encryption for securing the Gemini API Key inside AndroidKeyStore,
 * plus fast access to LinguaKey translation preferences.
 */
public final class LinguaKeySecurityManager {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "LinguaKey_Gemini_AES256_Key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;

    private static final String PREF_ENCRYPTED_GEMINI_KEY = "pref_encrypted_gemini_api_key_v2";
    private static final String PREF_LEGACY_GEMINI_KEY = "pref_gemini_key";

    public static final String PREF_LIVE_AUTO_TRANSLATE = "pref_linguakey_live_auto_translate";
    public static final String PREF_LIVE_SOURCE_LANG = "pref_linguakey_live_source_lang";
    public static final String PREF_LIVE_TARGET_LANG = "pref_linguakey_live_target_lang";
    public static final String PREF_LIVE_DIALECT_MODE = "pref_linguakey_live_dialect_mode";
    public static final String PREF_LIVE_DEBOUNCE_MS = "pref_linguakey_live_debounce_ms";
    public static final String PREF_SOURCE_CLEAR_TIMEOUT_MS = "pref_linguakey_source_clear_timeout_ms";
    public static final String PREF_BACKEND_PROXY_URL = "pref_linguakey_backend_proxy_url";
    public static final String PREF_USE_BACKEND_PROXY = "pref_linguakey_use_backend_proxy";

    private LinguaKeySecurityManager() {
        // Utility class
    }

    private static synchronized SecretKey getOrCreateSecretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
        );
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build();
        keyGenerator.init(spec);
        return keyGenerator.generateKey();
    }

    public static void saveGeminiApiKey(final Context context, final String plainApiKey) {
        if (context == null) return;
        SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(context);
        String clean = (plainApiKey != null) ? plainApiKey.trim() : "";
        if (clean.isEmpty()) {
            prefs.edit()
                    .remove(PREF_ENCRYPTED_GEMINI_KEY)
                    .putString(PREF_LEGACY_GEMINI_KEY, "")
                    .apply();
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey());
            byte[] iv = cipher.getIV();
            byte[] encryptedBytes = cipher.doFinal(clean.getBytes(StandardCharsets.UTF_8));
            String payload = Base64.encodeToString(iv, Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(encryptedBytes, Base64.NO_WRAP);
            prefs.edit()
                    .putString(PREF_ENCRYPTED_GEMINI_KEY, payload)
                    .putString(PREF_LEGACY_GEMINI_KEY, clean)
                    .apply();
        } catch (Exception e) {
            prefs.edit().putString(PREF_LEGACY_GEMINI_KEY, clean).apply();
        }
    }

    public static String getGeminiApiKey(final Context context) {
        return readGeminiApiKey(context);
    }

    public static String readGeminiApiKey(final Context context) {
        if (context == null) return "";
        SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(context);
        String encryptedPayload = prefs.getString(PREF_ENCRYPTED_GEMINI_KEY, null);
        if (encryptedPayload != null && !encryptedPayload.trim().isEmpty() && encryptedPayload.contains(":")) {
            try {
                String[] parts = encryptedPayload.split(":", 2);
                byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
                byte[] cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP);
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), spec);
                String decoded = new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8).trim();
                if (!decoded.isEmpty()) {
                    return decoded;
                }
            } catch (Exception ignored) {
                // Fallback to legacy plain preference if KeyStore was invalidated
            }
        }
        String legacy = prefs.getString(PREF_LEGACY_GEMINI_KEY, "");
        if (legacy == null) legacy = "";
        legacy = legacy.trim();
        if (!legacy.isEmpty() && (encryptedPayload == null || encryptedPayload.trim().isEmpty())) {
            saveGeminiApiKey(context, legacy);
        }
        return legacy;
    }

    public static boolean isAutoTranslateEnabled(final Context context) {
        SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(context);
        return prefs.getBoolean(PREF_LIVE_AUTO_TRANSLATE, true);
    }

    public static void setAutoTranslateEnabled(final Context context, final boolean enabled) {
        SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(context);
        prefs.edit().putBoolean(PREF_LIVE_AUTO_TRANSLATE, enabled).apply();
    }

    public static String getSourceLang(final Context context) {
        SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(context);
        String v = prefs.getString(PREF_LIVE_SOURCE_LANG, "ar");
        return (v != null && !v.isEmpty()) ? v : "ar";
    }

    public static void setSourceLang(final Context context, final String code) {
        SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(context);
        prefs.edit()
                .putString(PREF_LIVE_SOURCE_LANG, code)
                .putString("pref_translate_source_lang", code)
                .apply();
    }

    public static String getTargetLang(final Context context) {
        SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(context);
        String v = prefs.getString(PREF_LIVE_TARGET_LANG, "en");
        return (v != null && !v.isEmpty()) ? v : "en";
    }

    public static void setTargetLang(final Context context, final String code) {
        SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(context);
        prefs.edit()
                .putString(PREF_LIVE_TARGET_LANG, code)
                .putString("pref_translate_target_lang", code)
                .apply();
    }

    public static String getDialectMode(final Context context) {
        SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(context);
        String v = prefs.getString(PREF_LIVE_DIALECT_MODE, "SYRIAN_NATURAL");
        return (v != null && !v.isEmpty()) ? v : "SYRIAN_NATURAL";
    }

    public static void setDialectMode(final Context context, final String mode) {
        SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(context);
        prefs.edit().putString(PREF_LIVE_DIALECT_MODE, mode).apply();
    }

    public static long getDebounceMs(final Context context) {
        SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(context);
        String raw = prefs.getString(PREF_LIVE_DEBOUNCE_MS, "400");
        try {
            long val = Long.parseLong(raw != null ? raw.trim() : "400");
            return Math.max(200L, Math.min(2000L, val));
        } catch (NumberFormatException e) {
            return 400L;
        }
    }

    public static long getSourceClearTimeoutMs(final Context context) {
        SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(context);
        String raw = prefs.getString(PREF_SOURCE_CLEAR_TIMEOUT_MS, "0");
        try {
            long val = Long.parseLong(raw != null ? raw.trim() : "0");
            if (val <= 0L) {
                return 0L;
            }
            return Math.max(500L, Math.min(30000L, val));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
