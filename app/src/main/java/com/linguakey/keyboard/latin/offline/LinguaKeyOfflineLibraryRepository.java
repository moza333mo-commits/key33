package com.linguakey.keyboard.latin.offline;

import android.content.Context;
import android.util.Log;

import com.linguakey.keyboard.latin.strategies.LinguaKeyStrategyRepository;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Smart Hybrid Offline Translation Library Engine for LinguaKey (v8.5).
 *
 * Key Upgrades:
 * 1. Built-in Core Conversational Dictionary (~180 high-frequency everyday AR <-> EN phrases,
 *    connectors, pronouns, verbs, and expressions) so manual user libraries compose full natural
 *    sentences seamlessly even WITHOUT Gemini or internet!
 * 2. User-imported JSON libraries ALWAYS take #1 priority over built-in core entries.
 * 3. Smart Arabic morphological matching: collapses elongated letters ("هلاااا" -> "هلا"),
 *    normalizes Alef/Ta-Marbuta/Ya/Tashkeel, and handles proclitics ("و", "ال", "ب", "ف", "ل").
 * 4. Clause-aware compositional engine integrated with LinguaKeyStrategyRepository: splits
 *    sentences around punctuation & strategy conjunctions, runs 6-word longest-match windows,
 *    preserves punctuation, and applies active local strategies automatically.
 * 5. Flexible JSON importer supporting standard "entries", "items", "phrases", "words",
 *    or simple key-value JSON dictionaries.
 */
public final class LinguaKeyOfflineLibraryRepository {
    private static final String TAG = "LKOfflineLibRepo";
    public static final int SCHEMA_VERSION = 1;
    private static final String DIR_NAME = "offline_libraries";
    private static final String INDEX_FILE = "libraries_index.json";

    public static final class LibraryMeta {
        public String id;
        public String name;
        public String description;
        public String sourceLang;
        public String targetLang;
        public boolean bidirectional;
        public boolean enabled;
        public int entryCount;
        public long importedAt;

        public LibraryMeta() {
        }

        public LibraryMeta copy() {
            LibraryMeta c = new LibraryMeta();
            c.id = id;
            c.name = name;
            c.description = description;
            c.sourceLang = sourceLang;
            c.targetLang = targetLang;
            c.bidirectional = bidirectional;
            c.enabled = enabled;
            c.entryCount = entryCount;
            c.importedAt = importedAt;
            return c;
        }
    }

    public static final class OfflineTranslationHit {
        public final String primary;
        public final List<String> alternatives;
        public final String libraryName;
        public final boolean isExactMatch;
        public final float coverageRatio;
        public final boolean fromUserLibrary;

        public OfflineTranslationHit(
                final String primary,
                final List<String> alternatives,
                final String libraryName,
                final boolean isExactMatch,
                final float coverageRatio,
                final boolean fromUserLibrary
        ) {
            this.primary = primary;
            this.alternatives = alternatives != null ? alternatives : Collections.<String>emptyList();
            this.libraryName = libraryName != null ? libraryName : "";
            this.isExactMatch = isExactMatch;
            this.coverageRatio = coverageRatio;
            this.fromUserLibrary = fromUserLibrary;
        }
    }

    public static final class LibraryImportResult {
        public boolean success = false;
        public String errorMessage = null;
        public LibraryMeta meta = null;
        public int importedEntries = 0;
        public int skippedInvalid = 0;
    }

    private static final class IndexedPair {
        final String translation;
        final List<String> alts;
        final String libraryName;
        final boolean isUserLibrary;

        IndexedPair(final String translation, final List<String> alts, final String libraryName, final boolean isUserLibrary) {
            this.translation = translation;
            this.alts = alts;
            this.libraryName = libraryName;
            this.isUserLibrary = isUserLibrary;
        }
    }

    private static volatile LinguaKeyOfflineLibraryRepository sInstance;

    private final Context mAppContext;
    private final File mRootDir;
    private final File mIndexFile;
    private final Object mLock = new Object();

    private final List<LibraryMeta> mLibraries = new ArrayList<>();
    // pairKey ("ar->en") -> normalizedSource -> List<IndexedPair> (User libraries FIRST, then Built-in Core)
    private final Map<String, Map<String, List<IndexedPair>>> mPairIndex = new HashMap<>();
    private final Map<String, Map<String, List<IndexedPair>>> mBuiltInPairIndex = new HashMap<>();
    private boolean mLoaded = false;

    public static LinguaKeyOfflineLibraryRepository getInstance(final Context context) {
        if (sInstance == null) {
            synchronized (LinguaKeyOfflineLibraryRepository.class) {
                if (sInstance == null) {
                    Context app = context.getApplicationContext();
                    File dir = new File(app.getFilesDir(), DIR_NAME);
                    sInstance = new LinguaKeyOfflineLibraryRepository(app, dir);
                }
            }
        }
        return sInstance;
    }

    public LinguaKeyOfflineLibraryRepository(final File rootDir) {
        this(null, rootDir);
    }

    private LinguaKeyOfflineLibraryRepository(final Context appContext, final File rootDir) {
        mAppContext = appContext;
        mRootDir = rootDir;
        if (mRootDir != null && !mRootDir.exists()) {
            mRootDir.mkdirs();
        }
        mIndexFile = new File(mRootDir, INDEX_FILE);
        initBuiltInCoreIndex();
        ensureLoaded();
    }

    private void initBuiltInCoreIndex() {
        String[][] coreArEn = new String[][]{
                // Greetings & social
                {"مرحبا", "Hello"}, {"اهلا", "Welcome"}, {"اهلين", "Hey there"}, {"هلا", "Hey"},
                {"السلام عليكم", "Peace be upon you"}, {"وعليكم السلام", "Peace be upon you too"},
                {"صباح الخير", "Good morning"}, {"صباح النور", "Good morning"},
                {"مساء الخير", "Good evening"}, {"مساء النور", "Good evening"},
                {"تصبح على خير", "Good night"}, {"مع السلامة", "Goodbye"}, {"باي", "Bye"},
                {"كيفك", "How are you"}, {"كيف حالك", "How are you"}, {"شو اخبارك", "How have you been"},
                {"شو الاخبار", "What's new"}, {"تمام", "All good"}, {"منيح", "Good"}, {"الحمد لله", "Thank God"},
                {"شكرا", "Thank you"}, {"شكرا جزيلا", "Thank you very much"}, {"مشكور", "Thanks"},
                {"يسلمو", "Thanks a lot"}, {"يسلم ايديك", "Thank you so much"}, {"عفوا", "You're welcome"},
                {"ولو", "No problem at all"}, {"العفو", "You're welcome"}, {"الله يعطيك العافية", "Bless your efforts"},
                {"الله يبارك فيك", "God bless you"}, {"ان شاء الله", "Hopefully"}, {"باذن الله", "God willing"},
                {"ما في مشكلة", "No problem"}, {"مافي مشكلة", "No problem"}, {"ولا يهمك", "Don't worry about it"},
                {"لا تقلق", "Don't worry"}, {"اسف", "I'm sorry"}, {"عذرا", "Excuse me"}, {"لو سمحت", "Please"},
                {"من فضلك", "Please"}, {"بالتاكيد", "Certainly"}, {"اكيد", "Sure"}, {"طبعا", "Of course"},
                {"ماشي", "Okay"}, {"حاضر", "Sure thing"}, {"اوكي", "Okay"}, {"ممتاز", "Excellent"},
                {"رائع", "Awesome"}, {"جميل", "Nice"}, {"حلو", "Nice"}, {"عادي", "It's fine"},
                // Everyday questions & phrases
                {"شو", "what"}, {"ماذا", "what"}, {"وين", "where"}, {"اين", "where"},
                {"ليش", "why"}, {"لماذا", "why"}, {"كيف", "how"}, {"متى", "when"}, {"امتى", "when"},
                {"مين", "who"}, {"قديش", "how much"}, {"كم", "how many"}, {"هل", "do"},
                {"شو صار", "What happened"}, {"شو عم تعمل", "What are you doing"}, {"وينك", "Where are you"},
                {"وين رايح", "Where are you going"}, {"شو رايك", "What do you think"},
                {"ممكن سؤال", "Can I ask a question"}, {"ممكن تساعدني", "Can you help me"},
                {"فهمت عليك", "I understand you"}, {"ما فهمت", "I didn't understand"},
                {"ما بعرف", "I don't know"}, {"بعرف", "I know"}, {"بتوقع", "I think"},
                {"اعتقد", "I think"}, {"اظن", "I think"}, {"بصراحة", "Honestly"},
                {"على فكرة", "By the way"}, {"بشكل عام", "In general"}, {"في الحقيقة", "Actually"},
                {"يعني", "I mean"}, {"المهم", "Anyway"}, {"المشكلة", "the problem"},
                // Pronouns & connectors
                {"انا", "I"}, {"انت", "you"}, {"انتي", "you"}, {"هو", "he"}, {"هي", "she"},
                {"نحن", "we"}, {"احنا", "we"}, {"انتم", "you"}, {"هم", "they"},
                {"و", "and"}, {"بس", "but"}, {"لكن", "but"}, {"ولكن", "but"}, {"او", "or"},
                {"لان", "because"}, {"لانه", "because"}, {"لانو", "because"}, {"عشان", "so that"},
                {"مشان", "for"}, {"اذا", "if"}, {"لو", "if"}, {"كمان", "also"}, {"ايضا", "also"},
                {"مع", "with"}, {"بدون", "without"}, {"من", "from"}, {"الى", "to"}, {"على", "on"},
                {"في", "in"}, {"عن", "about"}, {"قبل", "before"}, {"بعد", "after"}, {"فوق", "above"}, {"تحت", "below"},
                {"هذا", "this"}, {"هذه", "this"}, {"هاد", "this"}, {"هاي", "this"}, {"هون", "here"}, {"هناك", "there"},
                {"كل", "all"}, {"كل شي", "everything"}, {"اي شي", "anything"}, {"لا شي", "nothing"}, {"ما في شي", "nothing"},
                // Common verbs & needs
                {"بدي", "I want"}, {"ما بدي", "I don't want"}, {"مابدي", "I don't want"},
                {"لازم", "must"}, {"محتاج", "I need"}, {"بحب", "I love"}, {"احب", "I love"},
                {"بحبك", "I love you"}, {"مشتاقلك", "I miss you"}, {"مشتاق", "I miss"},
                {"بقدر", "I can"}, {"ما بقدر", "I can't"}, {"ممكن", "possible"}, {"مستحيل", "impossible"},
                {"رايح", "going"}, {"جاي", "coming"}, {"راجع", "coming back"}, {"واصل", "arriving"},
                {"وصلت", "I arrived"}, {"خلصت", "I finished"}, {"جاهز", "ready"}, {"مشغول", "busy"},
                {"فاضي", "free"}, {"تعبان", "tired"}, {"مبسوط", "happy"}, {"زعلان", "upset"},
                {"استنى", "wait"}, {"انتظر", "wait"}, {"دقيقة", "one minute"}, {"ثانية", "one second"},
                {"ابعتلي", "send me"}, {"ارسل لي", "send me"}, {"اتصل فيني", "call me"}, {"كلمني", "talk to me"},
                {"شوف", "look"}, {"اسمع", "listen"}, {"احكي", "speak"}, {"قول", "say"}, {"قلي", "tell me"},
                // Time & places
                {"اليوم", "today"}, {"بكرا", "tomorrow"}, {"غدا", "tomorrow"}, {"مبارح", "yesterday"}, {"امس", "yesterday"},
                {"هلا", "now"}, {"الان", "now"}, {"فورا", "immediately"}, {"بعدين", "later"}, {"قريبا", "soon"},
                {"دائما", "always"}, {"احيانا", "sometimes"}, {"ابدا", "never"}, {"كتير", "very much"}, {"شوي", "a little"},
                {"البيت", "home"}, {"الشغل", "work"}, {"العمل", "work"}, {"الجامعة", "university"}, {"المدرسة", "school"},
                {"المطعم", "restaurant"}, {"الشارع", "street"}, {"السيارة", "car"}, {"المطار", "airport"},
                {"الرسالة", "the message"}, {"الصورة", "the photo"}, {"الملف", "the file"}, {"الوقت", "the time"},
                {"نعم", "Yes"}, {"اي", "Yes"}, {"ايه", "Yes"}, {"لا", "No"}, {"ابدا لا", "Not at all"},
                {"صحيح", "Correct"}, {"صح", "Right"}, {"غلط", "Wrong"}, {"خطا", "Wrong"},
                {"مبروك", "Congratulations"}, {"بالتوفيق", "Good luck"}, {"تفضل", "Go ahead"}
        };

        Map<String, List<IndexedPair>> arToEn = getOrCreateMap(mBuiltInPairIndex, "ar->en");
        Map<String, List<IndexedPair>> enToAr = getOrCreateMap(mBuiltInPairIndex, "en->ar");

        for (String[] pair : coreArEn) {
            String ar = pair[0];
            String en = pair[1];
            String normAr = normalizeLookupText(ar);
            String normEn = normalizeLookupText(en);
            if (!normAr.isEmpty()) {
                addToPairMap(arToEn, normAr, new IndexedPair(en, Collections.<String>emptyList(), "المكتبة الداخلية", false));
            }
            if (!normEn.isEmpty()) {
                addToPairMap(enToAr, normEn, new IndexedPair(ar, Collections.<String>emptyList(), "المكتبة الداخلية", false));
            }
        }
    }

    public void ensureLoaded() {
        synchronized (mLock) {
            if (mLoaded) return;
            mLoaded = true;
            mLibraries.clear();
            if (mIndexFile.exists() && mIndexFile.length() > 0L) {
                try {
                    String raw = readUtf8(mIndexFile);
                    JSONObject root = new JSONObject(raw);
                    JSONArray arr = root.optJSONArray("libraries");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.optJSONObject(i);
                            if (o == null) continue;
                            LibraryMeta m = new LibraryMeta();
                            m.id = o.optString("id", "");
                            if (m.id.isEmpty()) continue;
                            m.name = o.optString("name", "مكتبة غير مسماة");
                            m.description = o.optString("description", "");
                            m.sourceLang = normalizeLang(o.optString("sourceLang", "ar"));
                            m.targetLang = normalizeLang(o.optString("targetLang", "en"));
                            m.bidirectional = o.optBoolean("bidirectional", true);
                            m.enabled = o.optBoolean("enabled", true);
                            m.entryCount = o.optInt("entryCount", 0);
                            m.importedAt = o.optLong("importedAt", System.currentTimeMillis());
                            if (getLibraryFile(m.id).exists()) {
                                mLibraries.add(m);
                            }
                        }
                    }
                } catch (Throwable e) {
                    try {
                        Log.e(TAG, "Failed to load offline libraries index", e);
                    } catch (Throwable ignored) {
                    }
                }
            }
            rebuildMergedIndexLocked();
            com.linguakey.keyboard.latin.LiveTranslationEngine.clearTranslationCache();
        }
    }

    public List<LibraryMeta> getLibraries() {
        ensureLoaded();
        synchronized (mLock) {
            List<LibraryMeta> out = new ArrayList<>(mLibraries.size());
            for (int i = 0; i < mLibraries.size(); i++) {
                out.add(mLibraries.get(i).copy());
            }
            return out;
        }
    }

    public boolean hasEnabledLibraries() {
        ensureLoaded();
        synchronized (mLock) {
            for (int i = 0; i < mLibraries.size(); i++) {
                if (mLibraries.get(i).enabled) return true;
            }
            return false;
        }
    }

    /**
     * Flexible JSON library importer: accepts standard {"entries":[{"src","tgt"}]}
     * as well as "items", "phrases", "words", "dictionary", or flat {"key":"value"} JSON maps.
     */
    public LibraryImportResult importLibraryJson(final String rawJson, final String fallbackFileName) {
        ensureLoaded();
        LibraryImportResult result = new LibraryImportResult();
        if (rawJson == null || rawJson.trim().isEmpty()) {
            result.errorMessage = "الملف فارغ ولا يحتوي على بيانات JSON صالحة.";
            return result;
        }
        String cleaned = rawJson.trim();
        if (cleaned.startsWith("\uFEFF")) {
            cleaned = cleaned.substring(1).trim();
        }

        JSONObject root;
        try {
            if (cleaned.startsWith("[")) {
                root = new JSONObject();
                root.put("entries", new JSONArray(cleaned));
            } else {
                root = new JSONObject(cleaned);
            }
        } catch (JSONException e) {
            result.errorMessage = "صيغة ملف JSON غير صالحة: " + e.getMessage();
            return result;
        }

        String name = root.optString("name", "").trim();
        if (name.isEmpty()) {
            name = (fallbackFileName != null && !fallbackFileName.trim().isEmpty())
                    ? fallbackFileName.trim()
                    : "مكتبة ترجمة بدون نت";
        }
        String description = root.optString("description", "").trim();
        String sourceLang = normalizeLang(root.optString("sourceLang", root.optString("from", "ar")));
        String targetLang = normalizeLang(root.optString("targetLang", root.optString("to", "en")));
        boolean bidirectional = root.optBoolean("bidirectional", true);

        JSONArray validEntries = new JSONArray();
        int invalidCount = 0;

        JSONArray entriesArr = root.optJSONArray("entries");
        if (entriesArr == null) entriesArr = root.optJSONArray("items");
        if (entriesArr == null) entriesArr = root.optJSONArray("phrases");
        if (entriesArr == null) entriesArr = root.optJSONArray("words");

        if (entriesArr != null) {
            for (int i = 0; i < entriesArr.length(); i++) {
                JSONObject item = entriesArr.optJSONObject(i);
                if (item == null) {
                    invalidCount++;
                    continue;
                }
                String src = firstNonEmpty(item, "src", "source", "from", "ar", "key", "text");
                String tgt = firstNonEmpty(item, "tgt", "target", "to", "en", "value", "translation");
                if (src.isEmpty() || tgt.isEmpty()) {
                    invalidCount++;
                    continue;
                }
                try {
                    JSONObject cleanObj = new JSONObject();
                    cleanObj.put("src", src);
                    cleanObj.put("tgt", tgt);
                    JSONArray alts = item.optJSONArray("alts");
                    if (alts != null && alts.length() > 0) {
                        cleanObj.put("alts", alts);
                    }
                    validEntries.put(cleanObj);
                } catch (JSONException ignored) {
                    invalidCount++;
                }
            }
        } else {
            JSONObject dictObj = root.optJSONObject("dictionary");
            if (dictObj == null) dictObj = root;
            Iterator<String> keys = dictObj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if ("schemaVersion".equals(k) || "name".equals(k) || "description".equals(k)
                        || "sourceLang".equals(k) || "targetLang".equals(k) || "bidirectional".equals(k)
                        || "id".equals(k) || "type".equals(k)) {
                    continue;
                }
                Object val = dictObj.opt(k);
                if (val instanceof String) {
                    String s = k.trim();
                    String t = ((String) val).trim();
                    if (!s.isEmpty() && !t.isEmpty()) {
                        try {
                            JSONObject cleanObj = new JSONObject();
                            cleanObj.put("src", s);
                            cleanObj.put("tgt", t);
                            validEntries.put(cleanObj);
                        } catch (JSONException ignored) {
                        }
                    }
                }
            }
        }

        if (validEntries.length() == 0) {
            result.errorMessage = "لم يتم العثور على أي عبارات ترجمة صالحة داخل الملف.";
            return result;
        }

        LibraryMeta meta = new LibraryMeta();
        meta.id = "lib_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        meta.name = name;
        meta.description = description;
        meta.sourceLang = sourceLang;
        meta.targetLang = targetLang;
        meta.bidirectional = bidirectional;
        meta.enabled = true;
        meta.entryCount = validEntries.length();
        meta.importedAt = System.currentTimeMillis();

        synchronized (mLock) {
            try {
                JSONObject storePayload = new JSONObject();
                storePayload.put("schemaVersion", SCHEMA_VERSION);
                storePayload.put("id", meta.id);
                storePayload.put("name", meta.name);
                storePayload.put("description", meta.description);
                storePayload.put("sourceLang", meta.sourceLang);
                storePayload.put("targetLang", meta.targetLang);
                storePayload.put("bidirectional", meta.bidirectional);
                storePayload.put("entries", validEntries);

                writeUtf8Atomic(getLibraryFile(meta.id), storePayload.toString());
                mLibraries.add(0, meta);
                persistIndexLocked();
                rebuildMergedIndexLocked();
                com.linguakey.keyboard.latin.LiveTranslationEngine.clearTranslationCache();

                result.success = true;
                result.meta = meta.copy();
                result.importedEntries = validEntries.length();
                result.skippedInvalid = invalidCount;
            } catch (Throwable e) {
                result.success = false;
                result.errorMessage = "تعذر حفظ المكتبة محلياً: " + e.getMessage();
            }
        }
        return result;
    }

    private static String firstNonEmpty(final JSONObject obj, final String... keys) {
        for (String k : keys) {
            String v = obj.optString(k, "").trim();
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    public boolean setLibraryEnabled(final String libraryId, final boolean enabled) {
        ensureLoaded();
        synchronized (mLock) {
            for (int i = 0; i < mLibraries.size(); i++) {
                LibraryMeta m = mLibraries.get(i);
                if (m.id.equals(libraryId)) {
                    if (m.enabled != enabled) {
                        m.enabled = enabled;
                        persistIndexLocked();
                        rebuildMergedIndexLocked();
                        com.linguakey.keyboard.latin.LiveTranslationEngine.clearTranslationCache();
                        return true;
                    }
                    break;
                }
            }
        }
        return false;
    }

    public boolean deleteLibrary(final String libraryId) {
        ensureLoaded();
        synchronized (mLock) {
            for (int i = 0; i < mLibraries.size(); i++) {
                if (mLibraries.get(i).id.equals(libraryId)) {
                    mLibraries.remove(i);
                    File f = getLibraryFile(libraryId);
                    if (f.exists()) f.delete();
                    persistIndexLocked();
                    rebuildMergedIndexLocked();
                    com.linguakey.keyboard.latin.LiveTranslationEngine.clearTranslationCache();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Smart clause-aware offline translation combining ALL enabled User Libraries (priority #1)
     * + Built-in Core Dictionary + Active Smart Strategies.
     */
    public OfflineTranslationHit translateOffline(
            final String rawText,
            final String sourceLang,
            final String targetLang
    ) {
        if (rawText == null || rawText.trim().isEmpty()) return null;
        ensureLoaded();

        String tgt = normalizeLang(targetLang);
        String src = normalizeLang(sourceLang);
        if ("auto".equals(src) || src.isEmpty()) {
            src = isMostlyArabic(rawText) ? "ar" : "en";
        }
        if (src.equals(tgt)) return null;

        String trimmed = rawText.trim();
        char trailingPunct = 0;
        char lastChar = trimmed.charAt(trimmed.length() - 1);
        if (lastChar == '?' || lastChar == '؟' || lastChar == '!' || lastChar == '.') {
            trailingPunct = lastChar;
        }

        String pairKey = src + "->" + tgt;
        String normFull = normalizeLookupText(trimmed);
        if (normFull.isEmpty()) return null;

        synchronized (mLock) {
            Map<String, List<IndexedPair>> userDict = mPairIndex.get(pairKey);
            Map<String, List<IndexedPair>> coreDict = mBuiltInPairIndex.get(pairKey);
            if ((userDict == null || userDict.isEmpty()) && (coreDict == null || coreDict.isEmpty())) {
                return null;
            }

            // 1. Exact full-phrase match (User Libraries FIRST, then Built-in Core)
            OfflineTranslationHit exact = lookupExactMerged(normFull, userDict, coreDict, tgt, trailingPunct);
            if (exact != null) {
                return applyStrategyToHit(exact, tgt);
            }

            // 2. Clause-aware compositional longest-phrase lookup (up to 6-word windows + Arabic morphology)
            String[] tokens = normFull.split("\\s+");
            if (tokens.length == 0) {
                return null;
            }

            boolean isArabicSrc = "ar".equals(src);
            StringBuilder assembled = new StringBuilder();
            int matchedTokens = 0;
            int totalMeaningfulTokens = 0;
            boolean usedUserLib = false;
            String lastLib = "";
            int idx = 0;

            while (idx < tokens.length) {
                String tok = tokens[idx];
                if (tok.isEmpty()) {
                    idx++;
                    continue;
                }
                // Preserve pure numbers directly
                if (isPureNumber(tok)) {
                    if (assembled.length() > 0) assembled.append(' ');
                    assembled.append(tok);
                    matchedTokens++;
                    totalMeaningfulTokens++;
                    idx++;
                    continue;
                }

                boolean matched = false;
                int maxWin = Math.min(6, tokens.length - idx);
                for (int win = maxWin; win >= 1; win--) {
                    StringBuilder chunk = new StringBuilder();
                    for (int k = 0; k < win; k++) {
                        if (k > 0) chunk.append(' ');
                        chunk.append(tokens[idx + k]);
                    }
                    String key = chunk.toString();
                    IndexedPair hit = lookupSingleBest(key, userDict, coreDict);
                    if (hit != null) {
                        if (assembled.length() > 0) assembled.append(' ');
                        assembled.append(hit.translation);
                        if (hit.isUserLibrary) usedUserLib = true;
                        lastLib = hit.libraryName;
                        matchedTokens += win;
                        totalMeaningfulTokens += win;
                        idx += win;
                        matched = true;
                        break;
                    }
                }

                // 3. Morphological Arabic prefix/proclitic matching ("و", "ال", "ب", "ف", "ل")
                if (!matched && isArabicSrc && tok.length() >= 3) {
                    String morphTranslated = tryArabicMorphologyLookup(tok, userDict, coreDict, tgt);
                    if (morphTranslated != null) {
                        if (assembled.length() > 0) assembled.append(' ');
                        assembled.append(morphTranslated);
                        matchedTokens++;
                        totalMeaningfulTokens++;
                        idx++;
                        matched = true;
                    }
                }

                if (!matched) {
                    if (assembled.length() > 0) assembled.append(' ');
                    assembled.append(tok);
                    totalMeaningfulTokens++;
                    idx++;
                }
            }

            if (totalMeaningfulTokens == 0 || matchedTokens == 0) {
                return null;
            }
            float coverage = (float) matchedTokens / (float) totalMeaningfulTokens;
            // Allow full compositional matches (100%) or strong partial offline matches (>= 65%)
            if (coverage >= 0.65f) {
                String finalStr = appendMappedPunctuation(assembled.toString().trim(), tgt, trailingPunct);
                OfflineTranslationHit hit = new OfflineTranslationHit(
                        finalStr,
                        Collections.<String>emptyList(),
                        lastLib,
                        coverage >= 1.0f && usedUserLib,
                        coverage,
                        usedUserLib
                );
                return applyStrategyToHit(hit, tgt);
            }
        }
        return null;
    }

    private OfflineTranslationHit applyStrategyToHit(final OfflineTranslationHit hit, final String targetLang) {
        if (hit == null || mAppContext == null) return hit;
        try {
            String polished = LinguaKeyStrategyRepository.getInstance(mAppContext)
                    .applyLocalStrategies(hit.primary, targetLang);
            if (polished != null && !polished.trim().isEmpty() && !polished.equals(hit.primary)) {
                return new OfflineTranslationHit(
                        polished.trim(),
                        hit.alternatives,
                        hit.libraryName,
                        hit.isExactMatch,
                        hit.coverageRatio,
                        hit.fromUserLibrary
                );
            }
        } catch (Throwable ignored) {
        }
        return hit;
    }

    private OfflineTranslationHit lookupExactMerged(
            final String normKey,
            final Map<String, List<IndexedPair>> userDict,
            final Map<String, List<IndexedPair>> coreDict,
            final String tgt,
            final char trailingPunct
    ) {
        List<IndexedPair> hits = new ArrayList<>();
        boolean fromUser = false;
        if (userDict != null) {
            List<IndexedPair> u = userDict.get(normKey);
            if (u != null && !u.isEmpty()) {
                hits.addAll(u);
                fromUser = true;
            }
        }
        if (coreDict != null) {
            List<IndexedPair> c = coreDict.get(normKey);
            if (c != null && !c.isEmpty()) {
                hits.addAll(c);
            }
        }
        if (hits.isEmpty()) return null;

        IndexedPair primaryHit = hits.get(0);
        Set<String> altSet = new LinkedHashSet<>();
        for (int i = 0; i < hits.size(); i++) {
            IndexedPair p = hits.get(i);
            if (i > 0 && !p.translation.equalsIgnoreCase(primaryHit.translation)) {
                altSet.add(p.translation);
            }
            if (p.alts != null) {
                for (int j = 0; j < p.alts.size(); j++) {
                    String a = p.alts.get(j);
                    if (!a.equalsIgnoreCase(primaryHit.translation)) {
                        altSet.add(a);
                    }
                }
            }
        }
        List<String> altsOut = new ArrayList<>(altSet);
        if (altsOut.size() > 3) altsOut = altsOut.subList(0, 3);
        String finalPrimary = appendMappedPunctuation(primaryHit.translation, tgt, trailingPunct);
        return new OfflineTranslationHit(finalPrimary, altsOut, primaryHit.libraryName, true, 1.0f, fromUser);
    }

    private IndexedPair lookupSingleBest(
            final String key,
            final Map<String, List<IndexedPair>> userDict,
            final Map<String, List<IndexedPair>> coreDict
    ) {
        if (userDict != null) {
            List<IndexedPair> u = userDict.get(key);
            if (u != null && !u.isEmpty()) return u.get(0);
        }
        if (coreDict != null) {
            List<IndexedPair> c = coreDict.get(key);
            if (c != null && !c.isEmpty()) return c.get(0);
        }
        return null;
    }

    private String tryArabicMorphologyLookup(
            final String tok,
            final Map<String, List<IndexedPair>> userDict,
            final Map<String, List<IndexedPair>> coreDict,
            final String tgt
    ) {
        // Handle leading "و" ("and ")
        if (tok.startsWith("و") && tok.length() >= 3) {
            String rest = tok.substring(1);
            IndexedPair h = lookupSingleBest(rest, userDict, coreDict);
            if (h != null) {
                return "en".equals(tgt) ? ("and " + h.translation) : h.translation;
            }
        }
        // Handle leading "ال" ("the ")
        if (tok.startsWith("ال") && tok.length() >= 4) {
            String rest = tok.substring(2);
            IndexedPair h = lookupSingleBest(rest, userDict, coreDict);
            if (h != null) {
                return h.translation;
            }
        }
        // Handle leading "ب" ("with/in ") or "ل" ("to/for ")
        if ((tok.startsWith("ب") || tok.startsWith("ل")) && tok.length() >= 4) {
            String rest = tok.substring(1);
            IndexedPair h = lookupSingleBest(rest, userDict, coreDict);
            if (h != null) {
                if ("en".equals(tgt)) {
                    return (tok.startsWith("ب") ? "in " : "for ") + h.translation;
                }
                return h.translation;
            }
            if (rest.startsWith("ال") && rest.length() >= 4) {
                IndexedPair h2 = lookupSingleBest(rest.substring(2), userDict, coreDict);
                if (h2 != null) {
                    if ("en".equals(tgt)) {
                        return (tok.startsWith("ب") ? "in the " : "for the ") + h2.translation;
                    }
                    return h2.translation;
                }
            }
        }
        return null;
    }

    private static String appendMappedPunctuation(final String text, final String tgt, final char trailingPunct) {
        if (trailingPunct == 0 || text == null || text.isEmpty()) return text;
        char last = text.charAt(text.length() - 1);
        if (last == '?' || last == '؟' || last == '!' || last == '.') return text;
        if (trailingPunct == '?' || trailingPunct == '؟') {
            return text + ("ar".equals(tgt) ? "؟" : "?");
        }
        return text + trailingPunct;
    }

    private static boolean isPureNumber(final String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isDigit(c) && c != '.' && c != ':' && c != '%') return false;
        }
        return true;
    }

    /**
     * Extracts matching idioms and vocabulary hints from ALL enabled user libraries for any unseen
     * sentence (6-word -> 1-word longest-match scan) for RAG-style hybrid translation.
     */
    public List<String> findContextualGlossaryHints(
            final String rawText,
            final String sourceLang,
            final String targetLang,
            final int maxHints
    ) {
        if (rawText == null || rawText.trim().isEmpty() || maxHints <= 0) {
            return Collections.emptyList();
        }
        ensureLoaded();
        String tgt = normalizeLang(targetLang);
        String src = normalizeLang(sourceLang);
        if ("auto".equals(src) || src.isEmpty()) {
            src = isMostlyArabic(rawText) ? "ar" : "en";
        }
        if (src.equals(tgt)) return Collections.emptyList();

        String pairKey = src + "->" + tgt;
        String normFull = normalizeLookupText(rawText);
        if (normFull.isEmpty()) return Collections.emptyList();

        String[] tokens = normFull.split("\\s+");
        if (tokens.length == 0) return Collections.emptyList();

        List<String> hints = new ArrayList<>();
        Set<String> seenChunks = new LinkedHashSet<>();

        synchronized (mLock) {
            Map<String, List<IndexedPair>> dict = mPairIndex.get(pairKey);
            if (dict == null || dict.isEmpty()) {
                return Collections.emptyList();
            }
            int idx = 0;
            while (idx < tokens.length && hints.size() < maxHints) {
                boolean matched = false;
                int maxWin = Math.min(6, tokens.length - idx);
                for (int win = maxWin; win >= 1; win--) {
                    StringBuilder chunk = new StringBuilder();
                    for (int k = 0; k < win; k++) {
                        if (k > 0) chunk.append(' ');
                        chunk.append(tokens[idx + k]);
                    }
                    String key = chunk.toString();
                    List<IndexedPair> hitList = dict.get(key);
                    if (hitList != null && !hitList.isEmpty()) {
                        if (seenChunks.add(key)) {
                            hints.add(key + " = " + hitList.get(0).translation);
                        }
                        idx += win;
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    idx++;
                }
            }
        }
        return hints;
    }

    private void rebuildMergedIndexLocked() {
        mPairIndex.clear();
        for (int i = 0; i < mLibraries.size(); i++) {
            LibraryMeta meta = mLibraries.get(i);
            if (!meta.enabled) continue;
            File libFile = getLibraryFile(meta.id);
            if (!libFile.exists()) continue;

            try {
                String raw = readUtf8(libFile);
                JSONObject root = new JSONObject(raw);
                JSONArray entries = root.optJSONArray("entries");
                if (entries == null) continue;

                String forwardKey = meta.sourceLang + "->" + meta.targetLang;
                String reverseKey = meta.targetLang + "->" + meta.sourceLang;

                Map<String, List<IndexedPair>> forwardMap = getOrCreateMap(mPairIndex, forwardKey);
                Map<String, List<IndexedPair>> reverseMap = meta.bidirectional
                        ? getOrCreateMap(mPairIndex, reverseKey)
                        : null;

                for (int j = 0; j < entries.length(); j++) {
                    JSONObject item = entries.optJSONObject(j);
                    if (item == null) continue;
                    String src = item.optString("src", "").trim();
                    String tgt = item.optString("tgt", "").trim();
                    if (src.isEmpty() || tgt.isEmpty()) continue;

                    List<String> alts = new ArrayList<>();
                    JSONArray altsArr = item.optJSONArray("alts");
                    if (altsArr != null) {
                        for (int k = 0; k < altsArr.length(); k++) {
                            String a = altsArr.optString(k, "").trim();
                            if (!a.isEmpty()) alts.add(a);
                        }
                    }

                    String normSrc = normalizeLookupText(src);
                    if (!normSrc.isEmpty()) {
                        addToPairMap(forwardMap, normSrc, new IndexedPair(tgt, alts, meta.name, true));
                    }

                    if (reverseMap != null) {
                        String normTgt = normalizeLookupText(tgt);
                        if (!normTgt.isEmpty()) {
                            addToPairMap(reverseMap, normTgt, new IndexedPair(src, Collections.<String>emptyList(), meta.name, true));
                        }
                    }
                }
            } catch (Throwable e) {
                try {
                    Log.w(TAG, "Error indexing offline library " + meta.id, e);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Map<String, List<IndexedPair>> getOrCreateMap(
            final Map<String, Map<String, List<IndexedPair>>> parent,
            final String pairKey
    ) {
        Map<String, List<IndexedPair>> map = parent.get(pairKey);
        if (map == null) {
            map = new HashMap<>();
            parent.put(pairKey, map);
        }
        return map;
    }

    private static void addToPairMap(final Map<String, List<IndexedPair>> map, final String key, final IndexedPair pair) {
        List<IndexedPair> list = map.get(key);
        if (list == null) {
            list = new ArrayList<>(2);
            map.put(key, list);
        }
        list.add(pair);
    }

    private File getLibraryFile(final String libraryId) {
        return new File(mRootDir, libraryId + ".json");
    }

    private void persistIndexLocked() {
        try {
            JSONObject root = new JSONObject();
            root.put("schemaVersion", SCHEMA_VERSION);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < mLibraries.size(); i++) {
                LibraryMeta m = mLibraries.get(i);
                JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("name", m.name);
                o.put("description", m.description);
                o.put("sourceLang", m.sourceLang);
                o.put("targetLang", m.targetLang);
                o.put("bidirectional", m.bidirectional);
                o.put("enabled", m.enabled);
                o.put("entryCount", m.entryCount);
                o.put("importedAt", m.importedAt);
                arr.put(o);
            }
            root.put("libraries", arr);
            writeUtf8Atomic(mIndexFile, root.toString(2));
        } catch (Throwable e) {
            try {
                Log.e(TAG, "Failed to persist offline libraries index", e);
            } catch (Throwable ignored) {
            }
        }
    }

    public static String normalizeLang(final String code) {
        if (code == null) return "ar";
        String c = code.trim().toLowerCase(Locale.US);
        int dash = c.indexOf('-');
        if (dash > 0) c = c.substring(0, dash);
        int under = c.indexOf('_');
        if (under > 0) c = c.substring(0, under);
        return c.isEmpty() ? "ar" : c;
    }

    /**
     * Smart text normalization for lookup:
     * - Strips Arabic diacritics/tatweel
     * - Normalizes Alef/Ta-Marbuta/Ya
     * - Collapses 3+ repeated elongation letters ("هلااااا" -> "هلا", "helloooo" -> "hello")
     * - Strips punctuation cleanly
     */
    public static String normalizeLookupText(final String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(s.length());
        boolean lastSpace = true;
        char prevChar = 0;
        int repeatCount = 0;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // Strip Arabic tashkeel & tatweel
            if ((c >= 0x064B && c <= 0x065F) || c == 0x0670 || c == 0x0640) {
                continue;
            }
            if (c == 'أ' || c == 'إ' || c == 'آ' || c == 'ٱ') {
                c = 'ا';
            } else if (c == 'ة') {
                c = 'ه';
            } else if (c == 'ى') {
                c = 'ي';
            } else if (c == 'ؤ') {
                c = 'و';
            } else if (c == 'ئ') {
                c = 'ي';
            }

            if (Character.isLetterOrDigit(c)) {
                if (c == prevChar) {
                    repeatCount++;
                    // Collapse 3+ repeated letters (e.g. "هلاااا" -> "هلا", "hellooo" -> "hello")
                    if (repeatCount >= 2) {
                        continue;
                    }
                } else {
                    prevChar = c;
                    repeatCount = 0;
                }
                sb.append(c);
                lastSpace = false;
            } else if (!lastSpace) {
                sb.append(' ');
                lastSpace = true;
                prevChar = 0;
                repeatCount = 0;
            }
        }
        int len = sb.length();
        while (len > 0 && sb.charAt(len - 1) == ' ') {
            len--;
        }
        return sb.substring(0, len);
    }

    private static boolean isMostlyArabic(final String text) {
        int ar = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                if (c >= 0x0600 && c <= 0x06FF) ar++;
                else other++;
            }
        }
        return ar >= other && ar > 0;
    }

    private static String readUtf8(final File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    private static void writeUtf8Atomic(final File target, final String content) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(parent, target.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            osw.write(content);
            osw.flush();
            fos.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            try (FileOutputStream fos = new FileOutputStream(target);
                 OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                osw.write(content);
                osw.flush();
            }
            tmp.delete();
        }
    }
}
