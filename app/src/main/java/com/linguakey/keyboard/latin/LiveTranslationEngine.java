package com.linguakey.keyboard.latin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.view.inputmethod.InputConnection;

import com.linguakey.keyboard.compat.PreferenceManagerCompat;
import com.linguakey.keyboard.latin.offline.LinguaKeyOfflineLibraryRepository;
import com.linguakey.keyboard.latin.strategies.LinguaKeyStrategyRepository;
import com.linguakey.keyboard.latin.settings.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Core Real-Time Source-Strip to Chat-Box & Incoming Message Translation Engine for LinguaKey.
 *
 * Includes:
 * 1. Instant clear of the source phrase when the message is sent in the chat app (chat box becomes empty).
 * 2. Automatic clear of the source phrase 1.4 seconds after typing stops & translation completes,
 *    leaving the translated text safely in the chat box ready to send so the next phrase never merges with the old one!
 */
public final class LiveTranslationEngine {
    private static final String TAG = "LiveTranslationEngine";
    private static final long AUTO_CLEAR_SOURCE_AFTER_STOP_MS = 1400L;

    public interface InputConnectionProvider {
        InputConnection get();
    }

    public interface StandaloneTranslationCallback {
        void onTranslated(String translatedText);
    }

    public interface BatchTranslationCallback {
        void onBatchTranslated(List<String> translatedTexts);
    }

    public interface GeminiVisionScreenCallback {
        void onVisionParagraphsReady(List<LinguaKeyOcrAssembler.OcrParagraph> paragraphs);
        void onVisionError(String err);
    }

    public interface StatusListener {
        void onStateChanged(
                boolean translationActive,
                String sourceLang,
                String targetLang,
                String dialectMode,
                boolean isTranslating,
                String sourceBufferText,
                String currentTranslatedText,
                List<String> alternatives
        );
    }

    public static final class TranslationOutcome {
        public final String primary;
        public final List<String> alternatives;

        public TranslationOutcome(final String primary, final List<String> alternatives) {
            this.primary = primary;
            this.alternatives = alternatives != null ? alternatives : Collections.<String>emptyList();
        }
    }

    public static final String[] SUPPORTED_LANG_CODES = new String[]{
            "ar", "en", "es", "ko", "tr", "fr", "de", "ru", "zh", "ja", "it", "pt"
    };

    public static final String[] SUPPORTED_LANG_NAMES_AR = new String[]{
            "العربية",
            "English (الإنجليزية)",
            "Español (الإسبانية)",
            "한국어 (الكورية)",
            "Türkçe (التركية)",
            "Français (الفرنسية)",
            "Deutsch (الألمانية)",
            "Русский (الروسية)",
            "中文 (الصينية)",
            "日本語 (اليابانية)",
            "Italiano (الإيطالية)",
            "Português (البرتغالية)"
    };

    private static final Map<String, String> SUPPORTED_LANG_BADGES = new HashMap<>();
    private static final Map<String, String> SUPPORTED_LANG_ENGLISH_NAMES = new HashMap<>();

    static {
        SUPPORTED_LANG_BADGES.put("ar", "AR");
        SUPPORTED_LANG_BADGES.put("en", "EN");
        SUPPORTED_LANG_BADGES.put("es", "ES");
        SUPPORTED_LANG_BADGES.put("ko", "KO");
        SUPPORTED_LANG_BADGES.put("tr", "TR");
        SUPPORTED_LANG_BADGES.put("fr", "FR");
        SUPPORTED_LANG_BADGES.put("de", "DE");
        SUPPORTED_LANG_BADGES.put("ru", "RU");
        SUPPORTED_LANG_BADGES.put("zh", "ZH");
        SUPPORTED_LANG_BADGES.put("ja", "JA");
        SUPPORTED_LANG_BADGES.put("it", "IT");
        SUPPORTED_LANG_BADGES.put("pt", "PT");

        SUPPORTED_LANG_ENGLISH_NAMES.put("ar", "Arabic (natural Syrian Levantine or Modern Standard Arabic)");
        SUPPORTED_LANG_ENGLISH_NAMES.put("en", "Natural Conversational English");
        SUPPORTED_LANG_ENGLISH_NAMES.put("es", "Natural Conversational Spanish");
        SUPPORTED_LANG_ENGLISH_NAMES.put("ko", "Natural Polite Conversational Korean (해요체)");
        SUPPORTED_LANG_ENGLISH_NAMES.put("tr", "Natural Conversational Turkish");
        SUPPORTED_LANG_ENGLISH_NAMES.put("fr", "Natural Conversational French");
        SUPPORTED_LANG_ENGLISH_NAMES.put("de", "Natural Conversational German");
        SUPPORTED_LANG_ENGLISH_NAMES.put("ru", "Natural Conversational Russian");
        SUPPORTED_LANG_ENGLISH_NAMES.put("zh", "Natural Simplified Chinese");
        SUPPORTED_LANG_ENGLISH_NAMES.put("ja", "Natural Polite Japanese");
        SUPPORTED_LANG_ENGLISH_NAMES.put("it", "Natural Conversational Italian");
        SUPPORTED_LANG_ENGLISH_NAMES.put("pt", "Natural Conversational Portuguese");
    }

    private static final LruCache<String, TranslationOutcome> sTranslationCache = new LruCache<>(600);
    private static final ExecutorService sSharedWorkerExecutor = Executors.newFixedThreadPool(4);
    private static final Handler sStaticMainHandler = new Handler(Looper.getMainLooper());

    private final Context mContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mWorkerExecutor = sSharedWorkerExecutor;
    private final AtomicInteger mRequestCounter = new AtomicInteger(0);

    private StatusListener mStatusListener;
    private Runnable mDebounceRunnable;
    private Runnable mAutoClearSourceRunnable;

    private String mCurrentSourceText = "";
    private String mLastCommittedTranslation = "";
    private List<String> mCurrentAlternatives = Collections.emptyList();
    private boolean mIsCurrentlyTranslating = false;
    private volatile boolean mIsInternalReplacement = false;

    public LiveTranslationEngine(final Context context) {
        mContext = context;
    }

    public static void translateMessageToArabicAsync(
            final Context context,
            final String messageText,
            final StandaloneTranslationCallback callback
    ) {
        if (callback == null) return;
        final String clean = messageText != null ? messageText.trim() : "";
        if (clean.isEmpty()) {
            callback.onTranslated("");
            return;
        }
        final String finalTarget = "ar";
        final String cacheKey = "MSG_TOUCH_AR|auto|ar|" + clean;
        TranslationOutcome cached = sTranslationCache.get(cacheKey);
        if (cached != null && cached.primary != null && !cached.primary.isEmpty()) {
            callback.onTranslated(cached.primary);
            return;
        }
        sSharedWorkerExecutor.execute(new Runnable() {
            @Override
            public void run() {
                LiveTranslationEngine helper = new LiveTranslationEngine(context);
                TranslationOutcome outcome = helper.callFastFallbackTranslate(clean, "auto", finalTarget);
                if (outcome == null || outcome.primary == null || outcome.primary.trim().isEmpty()) {
                    SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(context);
                    String apiKey = LinguaKeySecurityManager.readGeminiApiKey(context);
                    boolean useProxy = prefs.getBoolean(LinguaKeySecurityManager.PREF_USE_BACKEND_PROXY, false);
                    String proxyUrl = prefs.getString(LinguaKeySecurityManager.PREF_BACKEND_PROXY_URL, "");
                    String modelName = Settings.readGeminiModel(prefs);
                    if (modelName == null || modelName.trim().isEmpty()) modelName = "gemini-2.5-flash";
                    if (!apiKey.isEmpty() || (useProxy && proxyUrl != null && !proxyUrl.trim().isEmpty())) {
                        outcome = helper.callGeminiStructuredApi(
                                clean,
                                "auto",
                                finalTarget,
                                LinguaKeySecurityManager.getDialectMode(context),
                                false,
                                apiKey,
                                useProxy,
                                proxyUrl != null ? proxyUrl.trim() : "",
                                modelName
                        );
                    }
                }
                final TranslationOutcome finalOutcome = outcome;
                sStaticMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (finalOutcome != null && finalOutcome.primary != null && !finalOutcome.primary.trim().isEmpty()) {
                            sTranslationCache.put(cacheKey, finalOutcome);
                            callback.onTranslated(finalOutcome.primary.trim());
                        } else {
                            callback.onTranslated("");
                        }
                    }
                });
            }
        });
    }

    public static void translateStandaloneMessageAsync(
            final Context context,
            final String messageText,
            final StandaloneTranslationCallback callback
    ) {
        translateMessageToArabicAsync(context, messageText, callback);
    }

    /**
     * Batch-translates all detected screen paragraphs in a single fast network round-trip
     * with LruCache deduplication so already-translated paragraphs resolve in 0ms.
     */
    public static void translateScreenParagraphsBatchAsync(
            final Context context,
            final List<String> sourceTexts,
            final String targetLang,
            final BatchTranslationCallback callback
    ) {
        if (callback == null) return;
        if (sourceTexts == null || sourceTexts.isEmpty()) {
            callback.onBatchTranslated(Collections.<String>emptyList());
            return;
        }
        final String resolvedTarget = (targetLang != null && !targetLang.trim().isEmpty())
                ? targetLang.trim()
                : "ar";
        final int count = sourceTexts.size();
        final List<String> results = new ArrayList<>(Collections.nCopies(count, ""));
        final List<Integer> uncachedIndices = new ArrayList<>();
        final List<String> uncachedSources = new ArrayList<>();

        final LinguaKeyOfflineLibraryRepository offlineRepo = LinguaKeyOfflineLibraryRepository.getInstance(context);
        final LinguaKeyStrategyRepository strategyRepo = LinguaKeyStrategyRepository.getInstance(context);

        for (int i = 0; i < count; i++) {
            String src = sourceTexts.get(i) != null ? sourceTexts.get(i).trim() : "";
            if (src.isEmpty()) continue;
            String cacheKey = "SCREEN_OCR|auto|" + resolvedTarget + "|" + src;
            TranslationOutcome cached = sTranslationCache.get(cacheKey);
            if (cached != null && cached.primary != null && !cached.primary.isEmpty()) {
                results.set(i, cached.primary);
                continue;
            }
            // Instant 0ms exact hit from User Libraries + Built-in Core + Strategies
            LinguaKeyOfflineLibraryRepository.OfflineTranslationHit exactOff =
                    offlineRepo.translateOffline(src, "auto", resolvedTarget);
            if (exactOff != null && exactOff.isExactMatch && exactOff.primary != null && !exactOff.primary.isEmpty()) {
                String polished = strategyRepo.applyLocalStrategies(exactOff.primary, resolvedTarget);
                results.set(i, polished);
                sTranslationCache.put(cacheKey, new TranslationOutcome(polished, exactOff.alternatives));
            } else {
                uncachedIndices.add(i);
                uncachedSources.add(src);
            }
        }

        if (uncachedIndices.isEmpty()) {
            callback.onBatchTranslated(results);
            return;
        }

        sSharedWorkerExecutor.execute(new Runnable() {
            @Override
            public void run() {
                LiveTranslationEngine helper = new LiveTranslationEngine(context);
                boolean batchSuccess = false;

                // 1. Prefer Gemini API for creative, natural screen translation with emoji preservation when configured
                List<String> geminiBatch = helper.callGeminiScreenBatchTranslate(uncachedSources, resolvedTarget);
                if (geminiBatch != null && geminiBatch.size() == uncachedSources.size()) {
                    batchSuccess = true;
                    for (int i = 0; i < geminiBatch.size(); i++) {
                        String tr = geminiBatch.get(i) != null ? geminiBatch.get(i).trim() : "";
                        int origIdx = uncachedIndices.get(i);
                        String origSrc = uncachedSources.get(i);
                        if (!tr.isEmpty()) {
                            tr = strategyRepo.applyLocalStrategies(tr, resolvedTarget);
                            results.set(origIdx, tr);
                            sTranslationCache.put(
                                    "SCREEN_OCR|auto|" + resolvedTarget + "|" + origSrc,
                                    new TranslationOutcome(tr, Collections.<String>emptyList())
                            );
                        } else {
                            results.set(origIdx, origSrc);
                        }
                    }
                }

                // 2. Fast fallback if Gemini API key is not set or network call failed
                if (!batchSuccess && uncachedSources.size() > 1) {
                    // Join all single-line paragraphs with '\n' for 1-request batch translation
                    StringBuilder joined = new StringBuilder();
                    for (int i = 0; i < uncachedSources.size(); i++) {
                        if (i > 0) joined.append("\n");
                        joined.append(uncachedSources.get(i).replace('\n', ' ').replace('\r', ' ').trim());
                    }
                    TranslationOutcome batchOut = helper.callFastFallbackTranslate(
                            joined.toString(),
                            "auto",
                            resolvedTarget
                    );
                    if (batchOut != null && batchOut.primary != null && !batchOut.primary.isEmpty()) {
                        String[] parts = batchOut.primary.split("\\r?\\n");
                        if (parts.length == uncachedSources.size()) {
                            batchSuccess = true;
                            for (int i = 0; i < parts.length; i++) {
                                String tr = parts[i].trim();
                                int origIdx = uncachedIndices.get(i);
                                String origSrc = uncachedSources.get(i);
                                if (!tr.isEmpty()) {
                                    tr = strategyRepo.applyLocalStrategies(tr, resolvedTarget);
                                    results.set(origIdx, tr);
                                    sTranslationCache.put(
                                            "SCREEN_OCR|auto|" + resolvedTarget + "|" + origSrc,
                                            new TranslationOutcome(tr, Collections.<String>emptyList())
                                    );
                                } else {
                                    results.set(origIdx, origSrc);
                                }
                            }
                        }
                    }
                }

                if (!batchSuccess) {
                    for (int i = 0; i < uncachedSources.size(); i++) {
                        String origSrc = uncachedSources.get(i);
                        int origIdx = uncachedIndices.get(i);
                        TranslationOutcome out = helper.callFastFallbackTranslate(origSrc, "auto", resolvedTarget);
                        if (out != null && out.primary != null && !out.primary.trim().isEmpty()) {
                            String tr = strategyRepo.applyLocalStrategies(out.primary.trim(), resolvedTarget);
                            results.set(origIdx, tr);
                            sTranslationCache.put(
                                    "SCREEN_OCR|auto|" + resolvedTarget + "|" + origSrc,
                                    new TranslationOutcome(tr, Collections.<String>emptyList())
                            );
                        } else {
                            LinguaKeyOfflineLibraryRepository.OfflineTranslationHit offHit =
                                    offlineRepo.translateOffline(origSrc, "auto", resolvedTarget);
                            if (offHit != null && offHit.primary != null && !offHit.primary.isEmpty()) {
                                String tr = strategyRepo.applyLocalStrategies(offHit.primary, resolvedTarget);
                                results.set(origIdx, tr);
                            } else {
                                results.set(origIdx, origSrc);
                            }
                        }
                    }
                }

                sStaticMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onBatchTranslated(results);
                    }
                });
            }
        });
    }

    /**
     * Multimodal Gemini Vision OCR + In-Place Bounding Box Translation Fallback
     * (Ported and adapted from screen-translator-master ImageTranslation.kt for non-Latin scripts).
     */
    /**
     * Translates a batch of OCR screen paragraphs using the user's configured Gemini API key (or proxy),
     * producing natural, idiomatic translations that fix minor OCR typos and preserve all emojis in place.
     */
    private String buildGlossaryPromptSection(
            final List<String> texts,
            final String srcLang,
            final String tgtLang
    ) {
        try {
            LinguaKeyOfflineLibraryRepository repo = LinguaKeyOfflineLibraryRepository.getInstance(mContext);
            if (!repo.hasEnabledLibraries()) return LinguaKeyStrategyRepository.getInstance(mContext).buildAiStrategyPromptSection();
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < texts.size(); i++) {
                if (i > 0) joined.append(' ');
                joined.append(texts.get(i));
            }
            List<String> hints = repo.findContextualGlossaryHints(joined.toString(), srcLang, tgtLang, 8);
            if (hints == null || hints.isEmpty()) return LinguaKeyStrategyRepository.getInstance(mContext).buildAiStrategyPromptSection();
            StringBuilder sb = new StringBuilder("Preferred user dictionary meanings (apply naturally where relevant): ");
            for (int i = 0; i < hints.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(hints.get(i));
            }
            sb.append(".\n");
            String stratPrompt = LinguaKeyStrategyRepository.getInstance(mContext).buildAiStrategyPromptSection();
            return sb.toString() + stratPrompt;
        } catch (Throwable ignored) {
            return "";
        }
    }

    List<String> callGeminiScreenBatchTranslate(final List<String> sources, final String targetLang) {
        if (sources == null || sources.isEmpty()) return null;
        HttpURLConnection conn = null;
        try {
            SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(mContext);
            String apiKey = LinguaKeySecurityManager.readGeminiApiKey(mContext);
            boolean useProxy = prefs.getBoolean(LinguaKeySecurityManager.PREF_USE_BACKEND_PROXY, false);
            String rawProxyUrl = prefs.getString(LinguaKeySecurityManager.PREF_BACKEND_PROXY_URL, "");
            String proxyUrl = rawProxyUrl != null ? rawProxyUrl.trim() : "";
            boolean hasGemini = (apiKey != null && !apiKey.trim().isEmpty()) || (useProxy && !proxyUrl.isEmpty());
            if (!hasGemini) return null;

            String modelName = Settings.readGeminiModel(prefs);
            if (modelName == null || modelName.trim().isEmpty()) {
                modelName = "gemini-2.5-flash";
            }

            String endpoint;
            if (useProxy && !proxyUrl.isEmpty()) {
                endpoint = proxyUrl;
            } else {
                endpoint = "https://generativelanguage.googleapis.com/v1beta/models/"
                        + URLEncoder.encode(modelName.trim(), "UTF-8")
                        + ":generateContent?key=" + URLEncoder.encode(apiKey.trim(), "UTF-8");
            }

            String targetDesc = SUPPORTED_LANG_ENGLISH_NAMES.containsKey(targetLang)
                    ? SUPPORTED_LANG_ENGLISH_NAMES.get(targetLang)
                    : targetLang;

            JSONArray inputArr = new JSONArray();
            for (int i = 0; i < sources.size(); i++) {
                inputArr.put(sources.get(i));
            }

            String prompt = "You are a professional, creative mobile chat and screen translator. "
                    + "Translate each item in the following JSON array into natural, fluent " + targetDesc + ". "
                    + "Rules: "
                    + "1) Preserve ALL emojis in their natural positions inside each translated message (and if OCR garbled a greeting emoji, restore a fitting natural expression). "
                    + "2) Fix obvious OCR typos or merged words (e.g. 'ilike espanish' -> natural translation). "
                    + "3) Keep each translation concise so it fits cleanly inside the original chat bubble. "
                    + "4) Return ONLY a JSON array of strings of the EXACT same length and order as the input array.\n"
                    + buildGlossaryPromptSection(sources, "auto", targetLang)
                    + "Input: " + inputArr.toString();

            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("text", prompt));
            JSONArray contents = new JSONArray();
            contents.put(new JSONObject().put("parts", parts));

            JSONObject genConfig = new JSONObject();
            genConfig.put("temperature", 0.25);
            genConfig.put("responseMimeType", "application/json");

            JSONObject payload = new JSONObject();
            payload.put("contents", contents);
            payload.put("generationConfig", genConfig);

            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);

            OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8);
            writer.write(payload.toString());
            writer.flush();
            writer.close();

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject resp = new JSONObject(sb.toString());
                JSONArray candidates = resp.optJSONArray("candidates");
                if (candidates != null && candidates.length() > 0) {
                    String rawJson = candidates.getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .optString("text", "").trim();
                    if (rawJson.startsWith("```")) {
                        rawJson = rawJson.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
                    }
                    JSONArray outArr = new JSONArray(rawJson);
                    if (outArr.length() == sources.size()) {
                        List<String> outList = new ArrayList<>(sources.size());
                        for (int i = 0; i < outArr.length(); i++) {
                            outList.add(outArr.optString(i, sources.get(i)).trim());
                        }
                        return outList;
                    }
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "Gemini batch screen translate fallback triggered", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    public static void detectAndTranslateScreenWithGeminiVisionAsync(
            final Context context,
            final Bitmap croppedBitmap,
            final int cropTopY,
            final String targetLang,
            final GeminiVisionScreenCallback callback
    ) {
        if (callback == null) return;
        if (croppedBitmap == null || croppedBitmap.isRecycled()) {
            callback.onVisionError("Empty bitmap");
            return;
        }
        final int bmpW = croppedBitmap.getWidth();
        final int bmpH = croppedBitmap.getHeight();
        final Bitmap safeCopy;
        try {
            safeCopy = croppedBitmap.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable e) {
            callback.onVisionError("Bitmap copy failed");
            return;
        }

        sSharedWorkerExecutor.execute(new Runnable() {
            @Override
            public void run() {
                List<LinguaKeyOcrAssembler.OcrParagraph> parsedList = new ArrayList<>();
                HttpURLConnection conn = null;
                try {
                    String apiKey = LinguaKeySecurityManager.readGeminiApiKey(context);
                    if (apiKey == null || apiKey.trim().isEmpty()) {
                        safeCopy.recycle();
                        sStaticMainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onVisionError("No Gemini API Key");
                            }
                        });
                        return;
                    }

                    SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(context);
                    String modelName = Settings.readGeminiModel(prefs);
                    if (modelName == null || modelName.trim().isEmpty()) {
                        modelName = "gemini-2.5-flash";
                    }

                    // Scale bitmap down slightly for fast upload while preserving sharp text
                    int scaledW = bmpW;
                    int scaledH = bmpH;
                    if (bmpH > 1080) {
                        scaledH = 1080;
                        scaledW = Math.max(100, (int) ((long) bmpW * 1080L / bmpH));
                    }
                    Bitmap uploadBmp = (scaledW != bmpW || scaledH != bmpH)
                            ? Bitmap.createScaledBitmap(safeCopy, scaledW, scaledH, true)
                            : safeCopy;

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    uploadBmp.compress(Bitmap.CompressFormat.JPEG, 76, baos);
                    if (uploadBmp != safeCopy) {
                        uploadBmp.recycle();
                    }
                    String base64Jpeg = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

                    String targetDesc = SUPPORTED_LANG_ENGLISH_NAMES.containsKey(targetLang)
                            ? SUPPORTED_LANG_ENGLISH_NAMES.get(targetLang)
                            : targetLang;

                    String prompt = "Read all visible text blocks or chat message bubbles in this phone screenshot. "
                            + "Return ONLY a JSON array of objects, where each object has: "
                            + "\"box_2d\": [ymin, xmin, ymax, xmax] (integers 0 to 1000 normalized to image height and width), "
                            + "\"source\": original text, "
                            + "\"translation\": natural translation into " + targetDesc + ". "
                            + "Preserve all emojis naturally in their exact positions inside each translation. Skip blocks that are already in " + targetDesc + " or pure timestamps.";

                    JSONObject inlineData = new JSONObject();
                    inlineData.put("mime_type", "image/jpeg");
                    inlineData.put("data", base64Jpeg);

                    JSONArray parts = new JSONArray();
                    parts.put(new JSONObject().put("inline_data", inlineData));
                    parts.put(new JSONObject().put("text", prompt));

                    JSONArray contents = new JSONArray();
                    contents.put(new JSONObject().put("parts", parts));

                    JSONObject genConfig = new JSONObject();
                    genConfig.put("temperature", 0.1);
                    genConfig.put("responseMimeType", "application/json");

                    JSONObject payload = new JSONObject();
                    payload.put("contents", contents);
                    payload.put("generationConfig", genConfig);

                    String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/"
                            + URLEncoder.encode(modelName.trim(), "UTF-8")
                            + ":generateContent?key=" + URLEncoder.encode(apiKey.trim(), "UTF-8");

                    conn = (HttpURLConnection) new URL(endpoint).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setConnectTimeout(7000);
                    conn.setReadTimeout(9000);
                    conn.setDoOutput(true);

                    OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8);
                    writer.write(payload.toString());
                    writer.flush();
                    writer.close();

                    if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        reader.close();

                        JSONObject resp = new JSONObject(sb.toString());
                        JSONArray candidates = resp.optJSONArray("candidates");
                        if (candidates != null && candidates.length() > 0) {
                            String rawJson = candidates.getJSONObject(0)
                                    .getJSONObject("content")
                                    .getJSONArray("parts")
                                    .getJSONObject(0)
                                    .optString("text", "[]").trim();
                            if (rawJson.startsWith("```")) {
                                rawJson = rawJson.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
                            }
                            JSONArray arr = new JSONArray(rawJson);
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject item = arr.optJSONObject(i);
                                if (item == null) continue;
                                JSONArray boxArr = item.optJSONArray("box_2d");
                                String src = item.optString("source", "").trim();
                                String tr = item.optString("translation", "").trim();
                                if (boxArr == null || boxArr.length() < 4 || tr.isEmpty()) continue;

                                int topPx = Math.max(0, Math.min(bmpH - 4, (boxArr.optInt(0, 0) * bmpH) / 1000));
                                int leftPx = Math.max(0, Math.min(bmpW - 4, (boxArr.optInt(1, 0) * bmpW) / 1000));
                                int bottomPx = Math.max(topPx + 12, Math.min(bmpH, (boxArr.optInt(2, 0) * bmpH) / 1000));
                                int rightPx = Math.max(leftPx + 20, Math.min(bmpW, (boxArr.optInt(3, 0) * bmpW) / 1000));

                                Rect localBox = new Rect(leftPx, topPx, rightPx, bottomPx);
                                int[] colors = LinguaKeyOcrAssembler.sampleBackgroundAndTextColor(safeCopy, localBox);

                                LinguaKeyOcrAssembler.WritingDirection dir =
                                        LinguaKeyOcrAssembler.detectWritingDirection(tr);
                                LinguaKeyOcrAssembler.OcrWord w =
                                        new LinguaKeyOcrAssembler.OcrWord(localBox, src.isEmpty() ? tr : src, dir);
                                LinguaKeyOcrAssembler.OcrLine l =
                                        new LinguaKeyOcrAssembler.OcrLine(w, dir);
                                LinguaKeyOcrAssembler.OcrParagraph p =
                                        new LinguaKeyOcrAssembler.OcrParagraph(l, dir);
                                p.sampledBgColor = colors[0];
                                p.sampledTextColor = colors[1];
                                p.translatedText = tr;
                                p.setScreenBoundingBox(new Rect(
                                        localBox.left,
                                        localBox.top + cropTopY,
                                        localBox.right,
                                        localBox.bottom + cropTopY
                                ));
                                parsedList.add(p);
                            }
                        }
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "Gemini Vision screen OCR error", e);
                } finally {
                    if (conn != null) conn.disconnect();
                    try {
                        safeCopy.recycle();
                    } catch (Throwable ignored) {
                    }
                }

                final List<LinguaKeyOcrAssembler.OcrParagraph> finalResult = parsedList;
                sStaticMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onVisionParagraphsReady(finalResult);
                    }
                });
            }
        });
    }

    public void setStatusListener(final StatusListener listener) {
        mStatusListener = listener;
        notifyStatus();
    }

    private static boolean isTextMostlyArabic(final String text) {
        if (text == null || text.isEmpty()) return false;
        int ar = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                if (c >= 0x0600 && c <= 0x06FF) ar++;
                else other++;
            }
        }
        return ar > other;
    }

    public static void clearTranslationCache() {
        sTranslationCache.evictAll();
    }

    public String getCurrentSourceText() {
        return mCurrentSourceText != null ? mCurrentSourceText : "";
    }

    public boolean isAutoTranslateEnabled() {
        return LinguaKeySecurityManager.isAutoTranslateEnabled(mContext);
    }

    public boolean toggleAutoTranslate(final InputConnectionProvider provider) {
        boolean next = !isAutoTranslateEnabled();
        LinguaKeySecurityManager.setAutoTranslateEnabled(mContext, next);
        if (!next) {
            cancelPendingTranslation();
            cancelAutoClearSourceTimer();
            mCurrentSourceText = "";
            mLastCommittedTranslation = "";
            mCurrentAlternatives = Collections.emptyList();
        } else if (provider != null) {
            InputConnection ic = provider.get();
            if (ic != null) {
                CharSequence beforeSeq = ic.getTextBeforeCursor(300, 0);
                CharSequence afterSeq = ic.getTextAfterCursor(100, 0);
                String before = beforeSeq != null ? beforeSeq.toString() : "";
                String after = afterSeq != null ? afterSeq.toString() : "";
                String existing = (before + after).trim();
                if (!existing.isEmpty() && existing.length() <= 200) {
                    mIsInternalReplacement = true;
                    try {
                        ic.beginBatchEdit();
                        ic.finishComposingText();
                        ic.deleteSurroundingText(before.length(), after.length());
                        ic.endBatchEdit();
                    } catch (Exception ignored) {
                    } finally {
                        mIsInternalReplacement = false;
                    }
                    mCurrentSourceText = existing;
                    mLastCommittedTranslation = "";
                    notifyStatus();
                    triggerLiveTranslationNow(existing, provider, false);
                    return true;
                }
            }
        }
        notifyStatus();
        return next;
    }

    public String[] swapDirection(final InputConnectionProvider provider) {
        String src = LinguaKeySecurityManager.getSourceLang(mContext);
        String tgt = LinguaKeySecurityManager.getTargetLang(mContext);
        LinguaKeySecurityManager.setSourceLang(mContext, tgt);
        LinguaKeySecurityManager.setTargetLang(mContext, src);
        notifyStatus();
        if (isAutoTranslateEnabled() && !mCurrentSourceText.trim().isEmpty()) {
            triggerLiveTranslationNow(mCurrentSourceText, provider, false);
        }
        return new String[]{tgt, src};
    }

    public String cycleDialectMode(final InputConnectionProvider provider) {
        String current = LinguaKeySecurityManager.getDialectMode(mContext);
        String next;
        if ("SYRIAN_NATURAL".equals(current)) {
            next = "FORMAL_PRO";
        } else if ("FORMAL_PRO".equals(current)) {
            next = "CASUAL_FRIENDLY";
        } else {
            next = "SYRIAN_NATURAL";
        }
        LinguaKeySecurityManager.setDialectMode(mContext, next);
        notifyStatus();
        if (isAutoTranslateEnabled() && !mCurrentSourceText.trim().isEmpty()) {
            triggerLiveTranslationNow(mCurrentSourceText, provider, false);
        }
        return next;
    }

    public String getDialectDisplayName(final String mode) {
        if ("FORMAL_PRO".equals(mode)) {
            return "رسمي احترافي 💼";
        } else if ("CASUAL_FRIENDLY".equals(mode)) {
            return "ودّي شبابي 💬";
        }
        return "شامي طبيعي 🇸🇾";
    }

    public String getBadge(final String code) {
        String b = SUPPORTED_LANG_BADGES.get(code);
        return b != null ? b : (code != null ? code.toUpperCase(Locale.US) : "EN");
    }

    public void cancelPendingTranslation() {
        if (mDebounceRunnable != null) {
            mMainHandler.removeCallbacks(mDebounceRunnable);
            mDebounceRunnable = null;
        }
        mRequestCounter.incrementAndGet();
        mIsCurrentlyTranslating = false;
    }

    private void cancelAutoClearSourceTimer() {
        if (mAutoClearSourceRunnable != null) {
            mMainHandler.removeCallbacks(mAutoClearSourceRunnable);
            mAutoClearSourceRunnable = null;
        }
    }

    private void scheduleAutoClearSourceAfterStop() {
        cancelAutoClearSourceTimer();
        final long timeoutMs = LinguaKeySecurityManager.getSourceClearTimeoutMs(mContext);
        if (timeoutMs <= 0L) {
            // User selected "عند إرسال الرسالة فقط (بدون إخفاء تلقائي)"
            return;
        }
        mAutoClearSourceRunnable = new Runnable() {
            @Override
            public void run() {
                if (mIsCurrentlyTranslating) {
                    return;
                }
                // Commit current translation in chat box and clear the source strip so the next phrase starts 100% fresh!
                mCurrentSourceText = "";
                mLastCommittedTranslation = "";
                mCurrentAlternatives = Collections.emptyList();
                notifyStatus();
            }
        };
        mMainHandler.postDelayed(mAutoClearSourceRunnable, timeoutMs);
    }

    public void onStartNewField() {
        cancelPendingTranslation();
        cancelAutoClearSourceTimer();
        mCurrentSourceText = "";
        mLastCommittedTranslation = "";
        mCurrentAlternatives = Collections.emptyList();
        notifyStatus();
    }

    /**
     * Called right before inserting a new character into the source buffer.
     * If the user sent the previous message in the chat app (so the chat input box is now empty),
     * immediately resets the session and returns true so the old source phrase is wiped first!
     */
    public synchronized boolean syncWithChatBoxBeforeKeystroke(final InputConnectionProvider provider) {
        cancelAutoClearSourceTimer();
        if (mCurrentSourceText.isEmpty() && mLastCommittedTranslation.isEmpty()) {
            return false;
        }
        InputConnection ic = provider != null ? provider.get() : null;
        if (ic == null) {
            return false;
        }
        try {
            CharSequence beforeSeq = ic.getTextBeforeCursor(60, 0);
            CharSequence afterSeq = ic.getTextAfterCursor(60, 0);
            boolean isBeforeEmpty = (beforeSeq == null || beforeSeq.length() == 0);
            boolean isAfterEmpty = (afterSeq == null || afterSeq.length() == 0);
            if (isBeforeEmpty && isAfterEmpty && !mLastCommittedTranslation.isEmpty()) {
                cancelPendingTranslation();
                mCurrentSourceText = "";
                mLastCommittedTranslation = "";
                mCurrentAlternatives = Collections.emptyList();
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public void onExternalSelectionUpdated(
            final int newSelStart,
            final int newSelEnd,
            final InputConnectionProvider provider
    ) {
        if (mIsInternalReplacement) return;
        if (!isAutoTranslateEnabled()) return;
        if (newSelStart == 0 && newSelEnd == 0 && (!mLastCommittedTranslation.isEmpty() || !mCurrentSourceText.isEmpty())) {
            InputConnection ic = provider != null ? provider.get() : null;
            if (ic != null) {
                CharSequence before = ic.getTextBeforeCursor(20, 0);
                CharSequence after = ic.getTextAfterCursor(20, 0);
                boolean isBeforeEmpty = (before == null || before.length() == 0);
                boolean isAfterEmpty = (after == null || after.length() == 0);
                if (isBeforeEmpty && isAfterEmpty) {
                    cancelPendingTranslation();
                    cancelAutoClearSourceTimer();
                    mCurrentSourceText = "";
                    mLastCommittedTranslation = "";
                    mCurrentAlternatives = Collections.emptyList();
                    notifyStatus();
                }
            }
        }
    }

    public void onSessionCommittedByEnter() {
        cancelPendingTranslation();
        cancelAutoClearSourceTimer();
        mCurrentSourceText = "";
        mLastCommittedTranslation = "";
        mCurrentAlternatives = Collections.emptyList();
        notifyStatus();
    }

    public void clearSessionAndChatTranslation(final InputConnectionProvider provider) {
        cancelPendingTranslation();
        cancelAutoClearSourceTimer();
        mCurrentSourceText = "";
        mCurrentAlternatives = Collections.emptyList();
        clearCommittedTranslationFromChatBox(provider);
        notifyStatus();
    }

    public void onSourceBufferChanged(
            final String newSourceText,
            final InputConnectionProvider provider,
            final boolean isBoundaryChar
    ) {
        mCurrentSourceText = newSourceText != null ? newSourceText : "";
        cancelAutoClearSourceTimer();
        if (mDebounceRunnable != null) {
            mMainHandler.removeCallbacks(mDebounceRunnable);
            mDebounceRunnable = null;
        }

        final String trimmed = mCurrentSourceText.trim();
        if (trimmed.isEmpty()) {
            cancelPendingTranslation();
            mCurrentAlternatives = Collections.emptyList();
            clearCommittedTranslationFromChatBox(provider);
            notifyStatus();
            return;
        }

        final String srcLang = LinguaKeySecurityManager.getSourceLang(mContext);
        final String tgtLang = LinguaKeySecurityManager.getTargetLang(mContext);
        final String dialectMode = LinguaKeySecurityManager.getDialectMode(mContext);
        final String cacheKey = srcLang + "|" + tgtLang + "|" + dialectMode + "|false|" + trimmed;

        TranslationOutcome cached = sTranslationCache.get(cacheKey);
        if (cached != null && cached.primary != null && !cached.primary.isEmpty()) {
            mRequestCounter.incrementAndGet();
            mIsCurrentlyTranslating = false;
            mCurrentAlternatives = cached.alternatives;
            syncTranslationToChatBox(cached.primary, provider);
            notifyStatus();
            scheduleAutoClearSourceAfterStop();
            return;
        }

        long configuredDebounce = LinguaKeySecurityManager.getDebounceMs(mContext);
        long delayMs = isBoundaryChar ? 40L : Math.max(85L, Math.min(260L, configuredDebounce));
        mDebounceRunnable = new Runnable() {
            @Override
            public void run() {
                triggerLiveTranslationNow(mCurrentSourceText, provider, false);
            }
        };
        mMainHandler.postDelayed(mDebounceRunnable, delayMs);
    }

    public void triggerLiveTranslationNow(
            final String rawSourceText,
            final InputConnectionProvider provider,
            final boolean isPolishMode
    ) {
        final String candidate = rawSourceText != null ? rawSourceText.trim() : "";
        if (candidate.isEmpty()) {
            clearCommittedTranslationFromChatBox(provider);
            notifyStatus();
            return;
        }

        String configuredSrc = isPolishMode ? "auto" : LinguaKeySecurityManager.getSourceLang(mContext);
        String configuredTgt = LinguaKeySecurityManager.getTargetLang(mContext);
        // Smart Auto-Direction Detection: if user types pure Latin while AR->EN is set (or pure Arabic while EN->AR is set),
        // automatically flip direction for this phrase so it always translates intelligently!
        if (!isPolishMode && candidate.length() >= 2) {
            boolean inputIsArabic = isTextMostlyArabic(candidate);
            if ("ar".equalsIgnoreCase(configuredSrc) && !inputIsArabic && !"ar".equalsIgnoreCase(configuredTgt)) {
                configuredSrc = configuredTgt;
                configuredTgt = "ar";
            } else if (!"ar".equalsIgnoreCase(configuredSrc) && !"auto".equalsIgnoreCase(configuredSrc)
                    && inputIsArabic && "ar".equalsIgnoreCase(configuredTgt)) {
                configuredTgt = configuredSrc;
                configuredSrc = "ar";
            }
        }
        final String srcLang = configuredSrc;
        final String tgtLang = configuredTgt;
        final String dialectMode = LinguaKeySecurityManager.getDialectMode(mContext);
        final String cacheKey = srcLang + "|" + tgtLang + "|" + dialectMode + "|" + isPolishMode + "|" + candidate;

        TranslationOutcome cached = sTranslationCache.get(cacheKey);
        if (cached != null && cached.primary != null && !cached.primary.isEmpty()) {
            mIsCurrentlyTranslating = false;
            mCurrentAlternatives = cached.alternatives;
            syncTranslationToChatBox(cached.primary, provider);
            notifyStatus();
            scheduleAutoClearSourceAfterStop();
            return;
        }

        SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(mContext);
        final String apiKey = LinguaKeySecurityManager.readGeminiApiKey(mContext);
        final boolean useProxy = prefs.getBoolean(LinguaKeySecurityManager.PREF_USE_BACKEND_PROXY, false);
        String rawProxyUrl = prefs.getString(LinguaKeySecurityManager.PREF_BACKEND_PROXY_URL, "");
        final String proxyUrl = rawProxyUrl != null ? rawProxyUrl.trim() : "";
        final boolean hasGeminiConfigured = !apiKey.isEmpty() || (useProxy && !proxyUrl.isEmpty());

        // Smart Hybrid Offline + Manual Libraries + Active Strategies lookup:
        // Instant 0ms response whenever:
        //  (a) Exact match from User Library or Built-in Core, OR
        //  (b) 100% compositional coverage from User Libraries + Built-in Core when Gemini is not configured!
        final LinguaKeyOfflineLibraryRepository.OfflineTranslationHit offlineHit =
                LinguaKeyOfflineLibraryRepository.getInstance(mContext).translateOffline(candidate, srcLang, tgtLang);
        if (offlineHit != null && offlineHit.primary != null && !offlineHit.primary.isEmpty()
                && (offlineHit.isExactMatch || (offlineHit.coverageRatio >= 1.0f && !hasGeminiConfigured))) {
            mIsCurrentlyTranslating = false;
            mCurrentAlternatives = offlineHit.alternatives;
            sTranslationCache.put(cacheKey, new TranslationOutcome(offlineHit.primary, offlineHit.alternatives));
            syncTranslationToChatBox(offlineHit.primary, provider);
            notifyStatus();
            scheduleAutoClearSourceAfterStop();
            return;
        }

        final int currentReqId = mRequestCounter.incrementAndGet();
        mIsCurrentlyTranslating = true;
        notifyStatus();

        String configuredModel = Settings.readGeminiModel(prefs);
        if (configuredModel == null || configuredModel.trim().isEmpty()) {
            configuredModel = "gemini-2.5-flash";
        }
        final String modelName = configuredModel;

        mWorkerExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (currentReqId != mRequestCounter.get()) return;
                final TranslationOutcome fastOutcome = callFastFallbackTranslate(candidate, srcLang, tgtLang);
                if (fastOutcome != null && fastOutcome.primary != null && !fastOutcome.primary.trim().isEmpty()) {
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (currentReqId != mRequestCounter.get() || !isAutoTranslateEnabled()) return;
                            String fastPrimary = fastOutcome.primary.trim();
                            if (!hasGeminiConfigured) {
                                sTranslationCache.put(cacheKey, fastOutcome);
                                mIsCurrentlyTranslating = false;
                            }
                            syncTranslationToChatBox(fastPrimary, provider);
                            notifyStatus();
                            if (!hasGeminiConfigured) {
                                scheduleAutoClearSourceAfterStop();
                            }
                        }
                    });
                } else if (offlineHit != null && offlineHit.primary != null && !offlineHit.primary.isEmpty()) {
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (currentReqId != mRequestCounter.get() || !isAutoTranslateEnabled()) return;
                            mIsCurrentlyTranslating = false;
                            mCurrentAlternatives = offlineHit.alternatives;
                            syncTranslationToChatBox(offlineHit.primary, provider);
                            notifyStatus();
                            scheduleAutoClearSourceAfterStop();
                        }
                    });
                } else if (!hasGeminiConfigured) {
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (currentReqId != mRequestCounter.get()) return;
                            mIsCurrentlyTranslating = false;
                            notifyStatus();
                        }
                    });
                }
            }
        });

        if (hasGeminiConfigured) {
            mWorkerExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    if (currentReqId != mRequestCounter.get()) return;
                    final TranslationOutcome geminiOutcome = callGeminiStructuredApi(
                            candidate,
                            srcLang,
                            tgtLang,
                            dialectMode,
                            isPolishMode,
                            apiKey,
                            useProxy,
                            proxyUrl,
                            modelName
                    );
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (currentReqId != mRequestCounter.get() || !isAutoTranslateEnabled()) return;
                            mIsCurrentlyTranslating = false;
                            if (geminiOutcome != null && geminiOutcome.primary != null && !geminiOutcome.primary.trim().isEmpty()) {
                                sTranslationCache.put(cacheKey, geminiOutcome);
                                mCurrentAlternatives = geminiOutcome.alternatives;
                                syncTranslationToChatBox(geminiOutcome.primary.trim(), provider);
                            }
                            notifyStatus();
                            scheduleAutoClearSourceAfterStop();
                        }
                    });
                }
            });
        }
    }

    private synchronized void syncTranslationToChatBox(
            final String newTranslation,
            final InputConnectionProvider provider
    ) {
        if (provider == null || newTranslation == null) return;
        String strategyProcessed = LinguaKeyStrategyRepository.getInstance(mContext)
                .applyLocalStrategies(newTranslation.trim(), LinguaKeySecurityManager.getTargetLang(mContext));
        final String cleanTranslation = strategyProcessed != null ? strategyProcessed.trim() : newTranslation.trim();
        if (cleanTranslation.isEmpty()) return;
        if (cleanTranslation.equals(mLastCommittedTranslation)) return;

        final InputConnection ic = provider.get();
        if (ic == null) return;

        mIsInternalReplacement = true;
        try {
            ic.beginBatchEdit();
            ic.finishComposingText();

            CharSequence beforeSeq = ic.getTextBeforeCursor(1000, 0);
            CharSequence afterSeq = ic.getTextAfterCursor(500, 0);
            String before = beforeSeq != null ? beforeSeq.toString() : "";
            String after = afterSeq != null ? afterSeq.toString() : "";

            String committedSegment = cleanTranslation;
            if (!mLastCommittedTranslation.isEmpty() && before.endsWith(mLastCommittedTranslation)) {
                ic.deleteSurroundingText(mLastCommittedTranslation.length(), 0);
                if (mLastCommittedTranslation.startsWith(" ")) {
                    committedSegment = " " + cleanTranslation;
                }
                ic.commitText(committedSegment, 1);
            } else if (!mLastCommittedTranslation.isEmpty() && before.contains(mLastCommittedTranslation)) {
                int idx = before.lastIndexOf(mLastCommittedTranslation);
                int deleteLen = before.length() - idx;
                ic.deleteSurroundingText(deleteLen, 0);
                if (mLastCommittedTranslation.startsWith(" ")) {
                    committedSegment = " " + cleanTranslation;
                }
                ic.commitText(committedSegment, 1);
            } else if (!mLastCommittedTranslation.isEmpty()) {
                ic.deleteSurroundingText(before.length(), after.length());
                ic.commitText(committedSegment, 1);
            } else {
                // Starting a new phrase: if chat box already has unsent text from an earlier sentence, separate with a space
                if (!before.isEmpty() && !Character.isWhitespace(before.charAt(before.length() - 1))) {
                    committedSegment = " " + cleanTranslation;
                }
                ic.commitText(committedSegment, 1);
            }

            ic.endBatchEdit();
            mLastCommittedTranslation = committedSegment;
            LinguaKeyMessageTouchService.setLastOutgoingTranslation(cleanTranslation);
        } catch (Exception e) {
            Log.e(TAG, "Error syncing translation to chat box", e);
        } finally {
            mIsInternalReplacement = false;
        }
    }

    private synchronized void clearCommittedTranslationFromChatBox(final InputConnectionProvider provider) {
        if (mLastCommittedTranslation.isEmpty() || provider == null) {
            mLastCommittedTranslation = "";
            return;
        }
        final InputConnection ic = provider.get();
        if (ic == null) {
            mLastCommittedTranslation = "";
            return;
        }

        mIsInternalReplacement = true;
        try {
            ic.beginBatchEdit();
            ic.finishComposingText();

            CharSequence beforeSeq = ic.getTextBeforeCursor(1000, 0);
            CharSequence afterSeq = ic.getTextAfterCursor(500, 0);
            String before = beforeSeq != null ? beforeSeq.toString() : "";
            String after = afterSeq != null ? afterSeq.toString() : "";

            if (before.endsWith(mLastCommittedTranslation)) {
                ic.deleteSurroundingText(mLastCommittedTranslation.length(), 0);
            } else if (before.contains(mLastCommittedTranslation)) {
                int idx = before.lastIndexOf(mLastCommittedTranslation);
                ic.deleteSurroundingText(before.length() - idx, 0);
            } else {
                ic.deleteSurroundingText(before.length(), after.length());
            }

            ic.endBatchEdit();
        } catch (Exception e) {
            Log.e(TAG, "Error clearing translation from chat box", e);
        } finally {
            mLastCommittedTranslation = "";
            mIsInternalReplacement = false;
        }
    }

    public void selectAlternativeInChatBox(final int index, final InputConnectionProvider provider) {
        if (index < 0 || index >= mCurrentAlternatives.size()) return;
        String chosen = mCurrentAlternatives.get(index).trim();
        if (chosen.isEmpty()) return;

        String previousPrimary = mLastCommittedTranslation.trim();
        List<String> updatedAlts = new ArrayList<>(mCurrentAlternatives);
        if (!previousPrimary.isEmpty()) {
            updatedAlts.set(index, previousPrimary);
        }
        mCurrentAlternatives = updatedAlts;
        syncTranslationToChatBox(chosen, provider);
        notifyStatus();
        scheduleAutoClearSourceAfterStop();
    }

    TranslationOutcome callGeminiStructuredApi(
            final String text,
            final String sourceLang,
            final String targetLang,
            final String dialectMode,
            final boolean isPolishMode,
            final String apiKey,
            final boolean useProxy,
            final String proxyUrl,
            final String modelName
    ) {
        HttpURLConnection conn = null;
        try {
            String endpoint;
            if (useProxy && !proxyUrl.isEmpty()) {
                endpoint = proxyUrl;
            } else {
                endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + modelName.trim() + ":generateContent?key=" + apiKey;
            }

            String targetDesc = SUPPORTED_LANG_ENGLISH_NAMES.get(targetLang);
            if (targetDesc == null) targetDesc = targetLang;
            String sourceDesc = "auto".equals(sourceLang)
                    ? "the source language"
                    : (SUPPORTED_LANG_ENGLISH_NAMES.get(sourceLang) != null ? SUPPORTED_LANG_ENGLISH_NAMES.get(sourceLang) : sourceLang);

            String toneInstruction;
            if ("SYRIAN_NATURAL".equals(dialectMode)) {
                toneInstruction = "Understand Syrian/Levantine Arabic idioms deeply (e.g., شو الأخبار، كيفك، تكرم عينك، على راسي، يلا، ماشي الحال، دغري، هلق، بدي، شلونك، يسعد أوقاتك، يعطيك العافية) and translate the true intended meaning and warm conversational tone, NEVER literal word-for-word translation.";
            } else if ("FORMAL_PRO".equals(dialectMode)) {
                toneInstruction = "Use a polite, professional, articulate tone suitable for business or formal messaging while keeping the exact meaning.";
            } else {
                toneInstruction = "Use a friendly, natural, native casual messaging tone suitable for WhatsApp and HelloTalk.";
            }

            String taskInstruction = isPolishMode
                    ? ("Polish, fix grammar/spelling, and make the following text sound 100% natural in " + targetDesc + ".")
                    : ("Translate the following user message from " + sourceDesc + " into " + targetDesc + ".");

            String libHints = buildGlossaryPromptSection(Collections.singletonList(text), sourceLang, targetLang);
            String prompt = "You are LinguaKey, an ultra-fast smart keyboard translation engine.\n" + libHints
                    + taskInstruction + "\n"
                    + toneInstruction + "\n"
                    + "Preserve punctuation, emojis, and speaker emotion.\n"
                    + "Return ONLY a valid JSON object with this exact schema (no markdown fences, no extra text):\n"
                    + "{\"primary\":\"best natural translation\",\"alt1\":\"alternative phrasing 1\",\"alt2\":\"alternative phrasing 2\"}\n\n"
                    + "User text:\n"
                    + text;

            JSONObject part = new JSONObject();
            part.put("text", prompt);
            JSONArray parts = new JSONArray();
            parts.put(part);
            JSONObject content = new JSONObject();
            content.put("parts", parts);
            JSONArray contents = new JSONArray();
            contents.put(content);

            JSONObject genConfig = new JSONObject();
            genConfig.put("temperature", 0.2);
            genConfig.put("maxOutputTokens", 220);
            genConfig.put("responseMimeType", "application/json");

            JSONObject payload = new JSONObject();
            payload.put("contents", contents);
            payload.put("generationConfig", genConfig);

            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(7000);
            conn.setDoOutput(true);

            OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8);
            writer.write(payload.toString());
            writer.flush();
            writer.close();

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject root = new JSONObject(sb.toString());
                JSONArray candidates = root.optJSONArray("candidates");
                if (candidates != null && candidates.length() > 0) {
                    String textOut = candidates.getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text")
                            .trim();
                    return parseGeminiJsonResponse(textOut);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Gemini translation error", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    private TranslationOutcome parseGeminiJsonResponse(final String rawText) {
        String cleaned = rawText.trim();
        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7).trim();
        if (cleaned.startsWith("```")) cleaned = cleaned.substring(3).trim();
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3).trim();

        try {
            JSONObject json = new JSONObject(cleaned);
            String primary = json.optString("primary", "").trim();
            String alt1 = json.optString("alt1", "").trim();
            String alt2 = json.optString("alt2", "").trim();
            if (!primary.isEmpty()) {
                List<String> alts = new ArrayList<>();
                if (!alt1.isEmpty() && !alt1.equals(primary)) {
                    alts.add(alt1);
                }
                if (!alt2.isEmpty() && !alt2.equals(primary) && !alt2.equals(alt1)) {
                    alts.add(alt2);
                }
                return new TranslationOutcome(primary, alts);
            }
        } catch (Exception ignored) {
            if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() > 2) {
                cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
            }
            if (!cleaned.isEmpty()) {
                return new TranslationOutcome(cleaned, Collections.<String>emptyList());
            }
        }
        return null;
    }

    TranslationOutcome callFastFallbackTranslate(
            final String text,
            final String sourceLang,
            final String targetLang
    ) {
        HttpURLConnection conn = null;
        try {
            String sl = (sourceLang == null || sourceLang.trim().isEmpty()) ? "auto" : sourceLang;
            String encoded = URLEncoder.encode(text, "UTF-8");
            String urlStr = "https://translate.googleapis.com/translate_a/single?client=dict-chrome-ex&sl="
                    + sl + "&tl=" + targetLang + "&dt=t&q=" + encoded;
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
                reader.close();

                JSONArray root = new JSONArray(body.toString());
                JSONArray sentences = root.getJSONArray(0);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < sentences.length(); i++) {
                    JSONArray seg = sentences.getJSONArray(i);
                    if (seg.length() > 0 && !seg.isNull(0)) {
                        sb.append(seg.getString(0));
                    }
                }
                String result = sb.toString().trim();
                if (!result.isEmpty()) {
                    return new TranslationOutcome(result, Collections.<String>emptyList());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Fallback translation error", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    public void notifyStatus() {
        final StatusListener listener = mStatusListener;
        if (listener == null) return;
        final boolean active = isAutoTranslateEnabled();
        final String src = LinguaKeySecurityManager.getSourceLang(mContext);
        final String tgt = LinguaKeySecurityManager.getTargetLang(mContext);
        final String dialect = LinguaKeySecurityManager.getDialectMode(mContext);
        final boolean translating = mIsCurrentlyTranslating;
        final String srcBuf = mCurrentSourceText;
        final String curTrans = mLastCommittedTranslation;
        final List<String> alts = mCurrentAlternatives;

        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener.onStateChanged(active, src, tgt, dialect, translating, srcBuf, curTrans, alts);
        } else {
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    listener.onStateChanged(active, src, tgt, dialect, translating, srcBuf, curTrans, alts);
                }
            });
        }
    }
}
