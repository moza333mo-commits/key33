package com.linguakey.keyboard.latin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.common.MlKitException;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * High-Precision Screen OCR & Geometric Paragraph Assembler for LinguaKey.
 *
 * Ported and enhanced from screen-translator-master:
 * - MlKitVisionKit.kt & MlKitOcrAdapter.kt (Bundled Google ML Kit Text Recognition with automatic closed-client recovery)
 * - ParagraphAssembler.kt & AssemblyParams.kt (Word -> Line -> Paragraph geometric clustering + column splitting)
 * - ReadingOrder.kt & WritingDirection.kt (RTL & LTR reading order sorting via Character.getDirectionality)
 * - Enhanced with Chat-Bubble Background Boundary Detection & Perimeter Color Sampling so WhatsApp/Telegram
 *   bubbles never merge across bubble boundaries and each translated block matches the original background color.
 */
public final class LinguaKeyOcrAssembler {
    private static final String TAG = "LKOcrAssembler";

    // Empirical geometric constants ported directly from AssemblyParams.kt (reference defaults)
    private static final double WORD_AXIS_FONT_HEIGHT_SIMILARITY_MIN_RATIO = 0.40;
    private static final double WORD_WRITE_DIR_DISTANCE_FONT_HEIGHT_RATIO_LIMIT = 1.50;
    private static final double LINE_FONT_HEIGHT_SIMILARITY_MIN_RATIO = 0.68;
    private static final double LINE_WRITE_DIR_OVERLAP_MIN_RATIO = 0.30;
    private static final double LINE_FONT_HEIGHT_SPACING_AFFINITY_LIMIT = 0.70;
    private static final double LINE_AXIS_HEIGHT_SIMILARITY_MIN_RATIO = 0.87;
    private static final double LINE_WRITE_DIR_DISTANCE_FONT_HEIGHT_RATIO_LIMIT = 0.91;
    private static final double LINE_FILL_MINIMUM_RATIO = 0.22;
    private static final double LINE_INDENT_LIMIT = 0.0;
    private static final double LINE_INDENT_FILL_GUARD = 0.95;

    public enum WritingDirection {
        LTR,
        RTL
    }

    public static final class OcrWord {
        public final Rect boundingBox;
        public final String text;
        public final WritingDirection direction;
        public final double fontHeight;

        public OcrWord(final Rect boundingBox, final String text, final WritingDirection direction) {
            this.boundingBox = new Rect(boundingBox);
            this.text = text != null ? text.trim() : "";
            this.direction = direction;
            this.fontHeight = Math.max(1.0, boundingBox.height());
        }
    }

    public static final class OcrLine {
        public final List<OcrWord> words = new ArrayList<>();
        public final WritingDirection direction;
        private Rect mCachedBox;

        public OcrLine(final OcrWord firstWord, final WritingDirection direction) {
            this.direction = direction;
            if (firstWord != null) {
                this.words.add(firstWord);
            }
        }

        public void addWord(final OcrWord newWord) {
            int left = 0;
            int right = words.size();
            if (direction == WritingDirection.RTL) {
                while (left < right) {
                    int mid = (left + right) / 2;
                    if (words.get(mid).boundingBox.left > newWord.boundingBox.left) {
                        left = mid + 1;
                    } else {
                        right = mid;
                    }
                }
            } else {
                while (left < right) {
                    int mid = (left + right) / 2;
                    if (words.get(mid).boundingBox.left < newWord.boundingBox.left) {
                        left = mid + 1;
                    } else {
                        right = mid;
                    }
                }
            }
            words.add(left, newWord);
            mCachedBox = null;
        }

        public Rect getBoundingBox() {
            if (mCachedBox == null) {
                if (words.isEmpty()) {
                    mCachedBox = new Rect();
                } else {
                    Rect u = new Rect(words.get(0).boundingBox);
                    for (int i = 1; i < words.size(); i++) {
                        u.union(words.get(i).boundingBox);
                    }
                    mCachedBox = u;
                }
            }
            return mCachedBox;
        }

        public double getFontHeight() {
            return Math.max(1.0, getBoundingBox().height());
        }

        public int getStartPosition() {
            Rect b = getBoundingBox();
            return direction == WritingDirection.RTL ? b.right : b.left;
        }

        public String getText() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < words.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(words.get(i).text);
            }
            return sb.toString().trim();
        }
    }

    public static final class OcrParagraph {
        public final List<OcrLine> lines = new ArrayList<>();
        public final WritingDirection direction;
        public boolean hasParallelLines = false;
        private Rect mScreenBoundingBox;
        private String mOverrideCleanedText = null;
        public int sampledBgColor = 0xF51F2229;
        public int sampledTextColor = Color.WHITE;
        public String translatedText = null;

        public OcrParagraph(final OcrLine firstLine, final WritingDirection direction) {
            this.direction = direction;
            if (firstLine != null) {
                this.lines.add(firstLine);
            }
        }

        public OcrParagraph(final List<OcrLine> initialLines, final WritingDirection direction) {
            this.direction = direction;
            if (initialLines != null) {
                this.lines.addAll(initialLines);
            }
        }

        public Rect getBoundingBox() {
            if (mScreenBoundingBox != null) {
                return mScreenBoundingBox;
            }
            if (lines.isEmpty()) {
                return new Rect();
            }
            Rect u = new Rect(lines.get(0).getBoundingBox());
            for (int i = 1; i < lines.size(); i++) {
                u.union(lines.get(i).getBoundingBox());
            }
            return u;
        }

        public void setScreenBoundingBox(final Rect rect) {
            mScreenBoundingBox = new Rect(rect);
        }

        public double getAverageFontHeight() {
            if (lines.isEmpty()) return 16.0;
            double sum = 0.0;
            for (int i = 0; i < lines.size(); i++) {
                sum += lines.get(i).getFontHeight();
            }
            return sum / lines.size();
        }

        public void setCleanedText(final String cleaned) {
            mOverrideCleanedText = cleaned;
        }

        public String getText() {
            if (!TextUtils.isEmpty(mOverrideCleanedText)) {
                return mOverrideCleanedText;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                String lt = lines.get(i).getText();
                if (lt.isEmpty()) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(lt);
            }
            return sb.toString().trim();
        }
    }

    public interface OcrCallback {
        void onParagraphsDetected(List<OcrParagraph> paragraphs, boolean alreadyTranslatedByVision);
        void onOcrError(String errorMessage);
    }

    private static LinguaKeyOcrAssembler sInstance;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private TextRecognizer mRecognizer;

    private LinguaKeyOcrAssembler() {
        initRecognizer();
    }

    public static synchronized LinguaKeyOcrAssembler getInstance() {
        if (sInstance == null) {
            sInstance = new LinguaKeyOcrAssembler();
        }
        return sInstance;
    }

    private void initRecognizer() {
        try {
            mRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        } catch (Throwable e) {
            Log.e(TAG, "Error creating ML Kit TextRecognizer", e);
        }
    }

    /**
     * Runs bundled ML Kit OCR on the cropped screen bitmap, assembles words into lines and paragraphs
     * using screen-translator-master's ParagraphAssembler geometry + background bubble boundary checks,
     * maps coordinates to physical screen coordinates (+cropTopY), and samples background colors.
     */
    public void detectAndAssembleParagraphs(
            final Context context,
            final Bitmap croppedBitmap,
            final int cropTopY,
            final String targetLang,
            final OcrCallback callback
    ) {
        if (croppedBitmap == null || croppedBitmap.isRecycled()) {
            if (callback != null) callback.onOcrError("صورة الشاشة غير صالحة");
            return;
        }

        if (mRecognizer == null) {
            initRecognizer();
        }
        if (mRecognizer == null) {
            tryGeminiVisionFallbackOrFail(context, croppedBitmap, cropTopY, targetLang, callback);
            return;
        }

        final InputImage inputImage;
        try {
            inputImage = InputImage.fromBitmap(croppedBitmap, 0);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to create InputImage from bitmap", e);
            tryGeminiVisionFallbackOrFail(context, croppedBitmap, cropTopY, targetLang, callback);
            return;
        }

        mRecognizer.process(inputImage)
                .addOnSuccessListener(new OnSuccessListener<Text>() {
                    @Override
                    public void onSuccess(final Text visionText) {
                        processMlKitTextResult(context, visionText, croppedBitmap, cropTopY, targetLang, callback);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(final Exception e) {
                        if (e instanceof MlKitException
                                && e.getMessage() != null
                                && e.getMessage().contains("closed")) {
                            Log.w(TAG, "TextRecognizer was closed, reinitializing...");
                            initRecognizer();
                            if (mRecognizer != null) {
                                mRecognizer.process(inputImage)
                                        .addOnSuccessListener(new OnSuccessListener<Text>() {
                                            @Override
                                            public void onSuccess(final Text visionText) {
                                                processMlKitTextResult(context, visionText, croppedBitmap, cropTopY, targetLang, callback);
                                            }
                                        })
                                        .addOnFailureListener(new OnFailureListener() {
                                            @Override
                                            public void onFailure(final Exception ex) {
                                                tryGeminiVisionFallbackOrFail(context, croppedBitmap, cropTopY, targetLang, callback);
                                            }
                                        });
                                return;
                            }
                        }
                        LinguaKeyScreenDiag.error("OCR", "ML Kit OCR failed, trying Gemini Vision fallback", e);
                        tryGeminiVisionFallbackOrFail(context, croppedBitmap, cropTopY, targetLang, callback);
                    }
                });
    }

    private void processMlKitTextResult(
            final Context context,
            final Text visionText,
            final Bitmap croppedBitmap,
            final int cropTopY,
            final String targetLang,
            final OcrCallback callback
    ) {
        try {
            int rawBlocks = (visionText != null && visionText.getTextBlocks() != null) ? visionText.getTextBlocks().size() : 0;
            List<OcrParagraph> assembled = assembleFromMlKitText(visionText, croppedBitmap, cropTopY, targetLang);
            LinguaKeyScreenDiag.stage("OCR", "ML Kit rawBlocks=" + rawBlocks + " assembledParagraphs=" + assembled.size() + " cropTopY=" + cropTopY);
            if (assembled.isEmpty() && hasGeminiApiKey(context)) {
                // If screen contains non-Latin script (e.g. Arabic/Russian/CJK) not read by Latin ML Kit, use Gemini Vision OCR
                tryGeminiVisionFallbackOrFail(context, croppedBitmap, cropTopY, targetLang, callback);
                return;
            }
            recycleQuietly(croppedBitmap);
            if (callback != null) {
                callback.onParagraphsDetected(assembled, false);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Error assembling OCR paragraphs", e);
            recycleQuietly(croppedBitmap);
            if (callback != null) {
                callback.onOcrError("حدث خطأ أثناء تحليل نصوص الشاشة");
            }
        }
    }

    private void tryGeminiVisionFallbackOrFail(
            final Context context,
            final Bitmap croppedBitmap,
            final int cropTopY,
            final String targetLang,
            final OcrCallback callback
    ) {
        if (!hasGeminiApiKey(context)) {
            recycleQuietly(croppedBitmap);
            if (callback != null) {
                callback.onParagraphsDetected(new ArrayList<OcrParagraph>(), false);
            }
            return;
        }

        LiveTranslationEngine.detectAndTranslateScreenWithGeminiVisionAsync(
                context,
                croppedBitmap,
                cropTopY,
                targetLang,
                new LiveTranslationEngine.GeminiVisionScreenCallback() {
                    @Override
                    public void onVisionParagraphsReady(final List<OcrParagraph> paragraphs) {
                        recycleQuietly(croppedBitmap);
                        if (callback != null) {
                            callback.onParagraphsDetected(paragraphs, true);
                        }
                    }

                    @Override
                    public void onVisionError(final String err) {
                        recycleQuietly(croppedBitmap);
                        if (callback != null) {
                            callback.onParagraphsDetected(new ArrayList<OcrParagraph>(), false);
                        }
                    }
                }
        );
    }

    private boolean hasGeminiApiKey(final Context context) {
        String key = LinguaKeySecurityManager.getGeminiApiKey(context);
        return key != null && !key.trim().isEmpty();
    }

    private void recycleQuietly(final Bitmap bmp) {
        if (bmp != null && !bmp.isRecycled()) {
            try {
                bmp.recycle();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Full geometric assembly pipeline ported from screen-translator-master's ParagraphAssembler.kt:
     * 1. Extracts all words with valid clamped bounding boxes.
     * 2. Determines dominant writing direction (LTR vs RTL).
     * 3. Groups words into visual lines (groupWordsIntoLines).
     * 4. Groups lines into coherent paragraphs (groupLinesIntoParagraphs) with chat-bubble background guard.
     * 5. Splits parallel multi-column paragraphs (detectAndSplitParagraphs + correctDetectAndSplitParagraphs).
     * 6. Samples exact background color & contrasting text color from the screenshot and offsets Y by cropTopY.
     */
    private List<OcrParagraph> assembleFromMlKitText(
            final Text visionText,
            final Bitmap bitmap,
            final int cropTopY,
            final String targetLang
    ) {
        if (visionText == null || visionText.getTextBlocks() == null || visionText.getTextBlocks().isEmpty()) {
            return Collections.emptyList();
        }

        final int bmpW = bitmap.getWidth();
        final int bmpH = bitmap.getHeight();
        final WritingDirection globalDir = detectWritingDirection(visionText.getText());

        // 1. Collect raw lines from ML Kit blocks and sort in reading order
        List<Text.Line> rawLines = new ArrayList<>();
        for (Text.TextBlock block : visionText.getTextBlocks()) {
            if (block == null || block.getLines() == null) continue;
            for (Text.Line line : block.getLines()) {
                if (line != null && line.getBoundingBox() != null && !TextUtils.isEmpty(line.getText())) {
                    rawLines.add(line);
                }
            }
        }

        Collections.sort(rawLines, new Comparator<Text.Line>() {
            @Override
            public int compare(final Text.Line a, final Text.Line b) {
                Rect ra = a.getBoundingBox();
                Rect rb = b.getBoundingBox();
                int topCmp = Integer.compare(ra.top, rb.top);
                if (topCmp != 0) return topCmp;
                return globalDir == WritingDirection.RTL
                        ? Integer.compare(rb.right, ra.right)
                        : Integer.compare(ra.left, rb.left);
            }
        });

        // 2. Flatten lines into clamped OcrWords
        List<OcrWord> words = new ArrayList<>();
        for (Text.Line line : rawLines) {
            List<Text.Element> elements = line.getElements();
            if (elements == null || elements.isEmpty()) {
                Rect clamped = clampToBitmap(line.getBoundingBox(), bmpW, bmpH);
                if (clamped.width() > 2 && clamped.height() > 2) {
                    words.add(new OcrWord(clamped, line.getText(), globalDir));
                }
                continue;
            }
            for (Text.Element el : elements) {
                if (el == null || el.getBoundingBox() == null || TextUtils.isEmpty(el.getText())) continue;
                String elText = el.getText().trim();
                if (elText.matches("^\\d{1,2}:\\d{2}$")) continue;
                Rect clamped = clampToBitmap(el.getBoundingBox(), bmpW, bmpH);
                if (clamped.width() > 1 && clamped.height() > 2) {
                    words.add(new OcrWord(clamped, elText, globalDir));
                }
            }
        }

        if (words.isEmpty()) {
            return Collections.emptyList();
        }

        // 3. Group words into visual lines (ported from ParagraphAssembler.groupWordsIntoLines)
        List<OcrLine> groupedLines = groupWordsIntoLines(words, globalDir);

        // 4. Group lines into paragraphs (ported from ParagraphAssembler.groupLinesIntoParagraphs + bubble color guard)
        List<OcrParagraph> rawParagraphs = groupLinesIntoParagraphs(groupedLines, globalDir, bitmap);

        // 5. Split parallel multi-column paragraphs (ported from ParagraphAssembler.splitColumns)
        List<OcrParagraph> splitParagraphs = splitColumns(rawParagraphs, globalDir);

        // 6. Sort final paragraphs top-to-bottom, filter out noise/timestamps/already-target-lang,
        //    sample background colors from bitmap, and offset coordinates by cropTopY
        Collections.sort(splitParagraphs, new Comparator<OcrParagraph>() {
            @Override
            public int compare(final OcrParagraph a, final OcrParagraph b) {
                Rect ra = a.getBoundingBox();
                Rect rb = b.getBoundingBox();
                int topCmp = Integer.compare(ra.top, rb.top);
                if (topCmp != 0) return topCmp;
                return Integer.compare(ra.left, rb.left);
            }
        });

        List<OcrParagraph> finalParagraphs = new ArrayList<>();
        for (int i = 0; i < splitParagraphs.size(); i++) {
            OcrParagraph p = splitParagraphs.get(i);
            String cleanedText = cleanOcrParagraphText(p.getText());
            if (!shouldTranslateParagraph(cleanedText, targetLang)) {
                continue;
            }
            p.setCleanedText(cleanedText);
            Rect localBox = p.getBoundingBox();
            if (localBox.width() < 10 || localBox.height() < 8) {
                continue;
            }

            // Sample background & contrasting text color from the captured bitmap BEFORE offsetting by cropTopY
            int[] colors = sampleBackgroundAndTextColor(bitmap, localBox);
            p.sampledBgColor = colors[0];
            p.sampledTextColor = colors[1];

            // Convert from cropped bitmap coordinates to physical full-screen coordinates
            Rect screenBox = new Rect(
                    localBox.left,
                    localBox.top + cropTopY,
                    localBox.right,
                    localBox.bottom + cropTopY
            );
            p.setScreenBoundingBox(screenBox);
            finalParagraphs.add(p);
        }

        // Cap at 18 blocks per screen to maintain instant rendering and zero clutter
        if (finalParagraphs.size() > 18) {
            return new ArrayList<>(finalParagraphs.subList(0, 18));
        }
        return finalParagraphs;
    }

    private List<OcrLine> groupWordsIntoLines(final List<OcrWord> words, final WritingDirection dir) {
        List<OcrWord> sortedWords = new ArrayList<>(words);
        Collections.sort(sortedWords, new Comparator<OcrWord>() {
            @Override
            public int compare(final OcrWord a, final OcrWord b) {
                int topCmp = Integer.compare(a.boundingBox.top, b.boundingBox.top);
                if (topCmp != 0) return topCmp;
                return dir == WritingDirection.RTL
                        ? Integer.compare(b.boundingBox.right, a.boundingBox.right)
                        : Integer.compare(a.boundingBox.left, b.boundingBox.left);
            }
        });

        List<OcrLine> lines = new ArrayList<>();
        for (int i = 0; i < sortedWords.size(); i++) {
            OcrWord word = sortedWords.get(i);
            boolean addedToLine = false;

            for (int j = 0; j < lines.size(); j++) {
                OcrLine line = lines.get(j);
                OcrWord closestWord = findClosestWordInLine(line, word);
                if (closestWord == null) continue;

                double avgFontHeight = (word.fontHeight + closestWord.fontHeight) * 0.5;
                int axisDistance = Math.abs(word.boundingBox.centerY() - closestWord.boundingBox.centerY());
                if (axisDistance > avgFontHeight) {
                    continue;
                }

                double writeDirDistance = horizontalDistance(word.boundingBox, closestWord.boundingBox);
                double distanceRatio = writeDirDistance / avgFontHeight;

                if (distanceRatio <= WORD_WRITE_DIR_DISTANCE_FONT_HEIGHT_RATIO_LIMIT) {
                    double axisSimilarity = avgFontHeight / (avgFontHeight + axisDistance);
                    double heightSimilarity = divideByLarger(word.fontHeight, closestWord.fontHeight);
                    if (axisSimilarity * heightSimilarity >= WORD_AXIS_FONT_HEIGHT_SIMILARITY_MIN_RATIO) {
                        line.addWord(word);
                        addedToLine = true;
                        break;
                    }
                }
            }

            if (!addedToLine) {
                lines.add(0, new OcrLine(word, dir));
            }
        }
        return lines;
    }

    private List<OcrParagraph> groupLinesIntoParagraphs(
            final List<OcrLine> lines,
            final WritingDirection dir,
            final Bitmap bitmap
    ) {
        List<OcrLine> sortedLines = new ArrayList<>(lines);
        Collections.sort(sortedLines, new Comparator<OcrLine>() {
            @Override
            public int compare(final OcrLine a, final OcrLine b) {
                Rect ra = a.getBoundingBox();
                Rect rb = b.getBoundingBox();
                int topCmp = Integer.compare(ra.top, rb.top);
                if (topCmp != 0) return topCmp;
                return dir == WritingDirection.RTL
                        ? Integer.compare(rb.right, ra.right)
                        : Integer.compare(ra.left, rb.left);
            }
        });

        List<OcrParagraph> paragraphs = new ArrayList<>();
        for (int i = 0; i < sortedLines.size(); i++) {
            OcrLine line = sortedLines.get(i);
            boolean addedToParagraph = false;

            for (int j = 0; j < paragraphs.size(); j++) {
                OcrParagraph paragraph = paragraphs.get(j);
                Rect pBox = paragraph.getBoundingBox();
                Rect lBox = line.getBoundingBox();

                boolean horizOverlap = isHorizontalOverlap(lBox, pBox);
                boolean vertOverlap = isVerticalOverlap(lBox, pBox);

                if (horizOverlap && vertOverlap) {
                    if (!hasChatBubbleBoundaryBetween(bitmap, pBox, lBox)) {
                        paragraph.lines.add(line);
                        addedToParagraph = true;
                        break;
                    }
                }

                if (paragraph.lines.isEmpty()) continue;
                OcrLine closestLine = paragraph.lines.get(paragraph.lines.size() - 1);
                Rect cBox = closestLine.getBoundingBox();

                int lineSpacing = verticalDistance(cBox, lBox);
                if (lineSpacing > closestLine.getFontHeight() * 1.85) {
                    continue;
                }

                // Do not merge across distinct chat bubble background boundaries
                if (hasChatBubbleBoundaryBetween(bitmap, cBox, lBox)) {
                    continue;
                }

                // Column fill ratio check (ported from ParagraphAssembler.kt LINE_FILL_MINIMUM_RATIO)
                int columnWidth = 0;
                for (int k = 0; k < paragraph.lines.size(); k++) {
                    columnWidth = Math.max(columnWidth, paragraph.lines.get(k).getBoundingBox().width());
                }
                if (columnWidth > 0 && ((double) cBox.width() / columnWidth) < LINE_FILL_MINIMUM_RATIO) {
                    continue;
                }

                // Indentation check (ported from ParagraphAssembler.kt LINE_INDENT_LIMIT)
                if (LINE_INDENT_LIMIT > 0 && columnWidth > 0) {
                    int paragraphHead = dir == WritingDirection.RTL ? pBox.right : pBox.left;
                    int lineHead = dir == WritingDirection.RTL ? lBox.right : lBox.left;
                    int indent = dir == WritingDirection.RTL ? (paragraphHead - lineHead) : (lineHead - paragraphHead);
                    double filled = (double) cBox.width() / columnWidth;
                    if (indent > line.getFontHeight() * LINE_INDENT_LIMIT && filled < LINE_INDENT_FILL_GUARD) {
                        continue;
                    }
                }

                double avgFontHeight = (line.getFontHeight() + closestLine.getFontHeight()) * 0.5;
                double fontHeightSim = divideByLarger(line.getFontHeight(), closestLine.getFontHeight());

                if (fontHeightSim >= LINE_FONT_HEIGHT_SIMILARITY_MIN_RATIO) {
                    if (horizOverlap) {
                        double overlapRatio = horizontalOverlapRatio(lBox, pBox);
                        if (overlapRatio >= LINE_WRITE_DIR_OVERLAP_MIN_RATIO) {
                            double spacingRatio = Math.max(0.01, (double) lineSpacing / avgFontHeight);
                            double lineSpacingAffinity = Math.min(1.0, 1.0 / spacingRatio);
                            if (fontHeightSim * lineSpacingAffinity >= LINE_FONT_HEIGHT_SPACING_AFFINITY_LIMIT) {
                                paragraph.lines.add(line);
                                addedToParagraph = true;
                                break;
                            }
                        }
                    } else if (vertOverlap) {
                        int axisDist = Math.abs(lBox.centerY() - cBox.centerY());
                        double axisSim = avgFontHeight / (avgFontHeight + axisDist);
                        if (axisSim * fontHeightSim >= LINE_AXIS_HEIGHT_SIMILARITY_MIN_RATIO) {
                            double writeDist = horizontalDistance(lBox, cBox);
                            if ((writeDist / avgFontHeight) <= LINE_WRITE_DIR_DISTANCE_FONT_HEIGHT_RATIO_LIMIT) {
                                if (dir == WritingDirection.LTR) {
                                    if (cBox.right < lBox.right) {
                                        paragraph.lines.add(line);
                                    } else {
                                        paragraph.lines.add(paragraph.lines.size() - 1, line);
                                    }
                                } else {
                                    if (lBox.left < cBox.left) {
                                        paragraph.lines.add(line);
                                    } else {
                                        paragraph.lines.add(paragraph.lines.size() - 1, line);
                                    }
                                }
                                paragraph.hasParallelLines = true;
                                addedToParagraph = true;
                                break;
                            }
                        }
                    }
                }
            }

            if (!addedToParagraph) {
                paragraphs.add(0, new OcrParagraph(line, dir));
            }
        }
        return paragraphs;
    }

    private List<OcrParagraph> splitColumns(final List<OcrParagraph> paragraphs, final WritingDirection dir) {
        List<OcrParagraph> result = new ArrayList<>();
        for (int i = 0; i < paragraphs.size(); i++) {
            OcrParagraph p = paragraphs.get(i);
            List<OcrParagraph> split = detectAndSplitParagraphs(p, dir);
            List<OcrParagraph> corrected = correctDetectAndSplitParagraphs(split, dir);
            result.addAll(corrected);
        }
        return result;
    }

    private List<OcrParagraph> detectAndSplitParagraphs(final OcrParagraph paragraph, final WritingDirection dir) {
        if (!paragraph.hasParallelLines || paragraph.lines.size() <= 1 || areAllInLine(paragraph.lines)) {
            return Collections.singletonList(paragraph);
        }
        final List<OcrLine> lines = paragraph.lines;
        final Set<OcrLine> visited = new HashSet<>();
        final List<List<OcrLine>> clusters = new ArrayList<>();
        final double distanceLimit = paragraph.getAverageFontHeight();

        for (int i = 0; i < lines.size(); i++) {
            OcrLine seed = lines.get(i);
            if (!visited.contains(seed)) {
                List<OcrLine> cluster = new ArrayList<>();
                expandLineCluster(seed, lines, cluster, visited, distanceLimit);
                clusters.add(cluster);
            }
        }

        List<OcrParagraph> out = new ArrayList<>();
        for (int i = 0; i < clusters.size(); i++) {
            out.add(new OcrParagraph(clusters.get(i), dir));
        }
        return out;
    }

    private void expandLineCluster(
            final OcrLine current,
            final List<OcrLine> allLines,
            final List<OcrLine> cluster,
            final Set<OcrLine> visited,
            final double distanceLimit
    ) {
        cluster.add(current);
        visited.add(current);
        for (int i = 0; i < allLines.size(); i++) {
            OcrLine candidate = allLines.get(i);
            if (candidate != current
                    && !visited.contains(candidate)
                    && Math.abs(current.getStartPosition() - candidate.getStartPosition()) <= distanceLimit) {
                expandLineCluster(candidate, allLines, cluster, visited, distanceLimit);
            }
        }
    }

    private List<OcrParagraph> correctDetectAndSplitParagraphs(
            final List<OcrParagraph> paragraphs,
            final WritingDirection dir
    ) {
        if (paragraphs.size() <= 1) return paragraphs;
        final Set<OcrParagraph> visited = new HashSet<>();
        final List<List<OcrParagraph>> clusters = new ArrayList<>();

        for (int i = 0; i < paragraphs.size(); i++) {
            OcrParagraph seed = paragraphs.get(i);
            if (!visited.contains(seed)) {
                List<OcrParagraph> cluster = new ArrayList<>();
                expandParagraphCluster(seed, paragraphs, cluster, visited);
                clusters.add(cluster);
            }
        }

        List<OcrParagraph> merged = new ArrayList<>();
        for (int i = 0; i < clusters.size(); i++) {
            List<OcrParagraph> group = clusters.get(i);
            List<OcrLine> allLines = new ArrayList<>();
            for (int j = 0; j < group.size(); j++) {
                allLines.addAll(group.get(j).lines);
            }
            Collections.sort(allLines, new Comparator<OcrLine>() {
                @Override
                public int compare(final OcrLine a, final OcrLine b) {
                    Rect ra = a.getBoundingBox();
                    Rect rb = b.getBoundingBox();
                    int topCmp = Integer.compare(ra.top, rb.top);
                    if (topCmp != 0) return topCmp;
                    return dir == WritingDirection.RTL
                            ? Integer.compare(rb.right, ra.right)
                            : Integer.compare(ra.left, rb.left);
                }
            });
            merged.add(new OcrParagraph(allLines, dir));
        }
        return merged;
    }

    private void expandParagraphCluster(
            final OcrParagraph current,
            final List<OcrParagraph> all,
            final List<OcrParagraph> cluster,
            final Set<OcrParagraph> visited
    ) {
        cluster.add(current);
        visited.add(current);
        Rect curBox = current.getBoundingBox();
        for (int i = 0; i < all.size(); i++) {
            OcrParagraph candidate = all.get(i);
            if (candidate != current
                    && !visited.contains(candidate)
                    && isHorizontalOverlap(curBox, candidate.getBoundingBox())) {
                expandParagraphCluster(candidate, all, cluster, visited);
            }
        }
    }

    private boolean areAllInLine(final List<OcrLine> lines) {
        for (int i = 0; i < lines.size(); i++) {
            for (int j = i + 1; j < lines.size(); j++) {
                if (!isVerticalOverlap(lines.get(i).getBoundingBox(), lines.get(j).getBoundingBox())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Checks whether the vertical gap between boxA and boxB crosses a chat bubble boundary
     * by comparing the background colors of boxA, boxB, and the gap between them.
     */
    private boolean hasChatBubbleBoundaryBetween(final Bitmap bmp, final Rect boxA, final Rect boxB) {
        if (bmp == null || bmp.isRecycled()) return false;
        try {
            int bgA = samplePerimeterBackgroundColor(bmp, boxA);
            int bgB = samplePerimeterBackgroundColor(bmp, boxB);
            if (colorDistance(bgA, bgB) > 42) {
                return true;
            }
            int topGap = Math.min(boxA.bottom, boxB.bottom);
            int bottomGap = Math.max(boxA.top, boxB.top);
            if (bottomGap - topGap >= 14) {
                int midY = Math.max(0, Math.min(bmp.getHeight() - 1, (topGap + bottomGap) / 2));
                int edgeX = Math.max(0, Math.min(bmp.getWidth() - 1, Math.min(boxA.left, boxB.left) + 2));
                int gapColor = bmp.getPixel(edgeX, midY);
                if (colorDistance(bgA, gapColor) > 45 && colorDistance(bgB, gapColor) > 45) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Samples 12 perimeter pixels around the given bounding box to determine the exact background
     * color of the chat bubble / webpage card and returns [backgroundColorWithAlpha, highContrastTextColor].
     */
    public static int[] sampleBackgroundAndTextColor(final Bitmap bmp, final Rect box) {
        int bgRgb = samplePerimeterBackgroundColor(bmp, box);
        int r = Color.red(bgRgb);
        int g = Color.green(bgRgb);
        int b = Color.blue(bgRgb);
        double luminance = 0.299 * r + 0.587 * g + 0.114 * b;

        int fillBg = Color.argb(255, r, g, b);
        int textColor = (luminance < 148.0) ? Color.WHITE : 0xFF141619;
        return new int[]{fillBg, textColor};
    }

    private static int samplePerimeterBackgroundColor(final Bitmap bmp, final Rect box) {
        if (bmp == null || bmp.isRecycled()) return 0xFF1F2229;
        try {
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            // Sample 3px OUTSIDE the text bounding box to capture pure chat-bubble background pixels (never text/emoji pixels)
            int left = Math.max(0, Math.min(w - 1, box.left - 3));
            int right = Math.max(0, Math.min(w - 1, box.right + 2));
            int top = Math.max(0, Math.min(h - 1, box.top - 3));
            int bottom = Math.max(0, Math.min(h - 1, box.bottom + 2));

            int[] reds = new int[12];
            int[] greens = new int[12];
            int[] blues = new int[12];
            int idx = 0;

            for (int step = 0; step < 4; step++) {
                int x = left + ((right - left) * step) / 3;
                int pTop = bmp.getPixel(x, top);
                reds[idx] = Color.red(pTop);
                greens[idx] = Color.green(pTop);
                blues[idx] = Color.blue(pTop);
                idx++;

                int pBot = bmp.getPixel(x, bottom);
                reds[idx] = Color.red(pBot);
                greens[idx] = Color.green(pBot);
                blues[idx] = Color.blue(pBot);
                idx++;
            }
            int midY = (top + bottom) / 2;
            int pLeft = bmp.getPixel(left, midY);
            reds[idx] = Color.red(pLeft);
            greens[idx] = Color.green(pLeft);
            blues[idx] = Color.blue(pLeft);
            idx++;

            int pRight = bmp.getPixel(right, midY);
            reds[idx] = Color.red(pRight);
            greens[idx] = Color.green(pRight);
            blues[idx] = Color.blue(pRight);
            idx++;

            Arrays.sort(reds, 0, idx);
            Arrays.sort(greens, 0, idx);
            Arrays.sort(blues, 0, idx);
            int mid = idx / 2;
            return Color.rgb(reds[mid], greens[mid], blues[mid]);
        } catch (Throwable ignored) {
            return 0xFF1F2229;
        }
    }

    private static int colorDistance(final int c1, final int c2) {
        return Math.abs(Color.red(c1) - Color.red(c2))
                + Math.abs(Color.green(c1) - Color.green(c2))
                + Math.abs(Color.blue(c1) - Color.blue(c2));
    }

    /**
     * Determines writing direction (RTL vs LTR) using Unicode character directionality.
     * Ported from screen-translator-master's ReadingOrder.isRightToLeft().
     */
    public static WritingDirection detectWritingDirection(final String text) {
        if (TextUtils.isEmpty(text)) return WritingDirection.LTR;
        int rtl = 0;
        int letters = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetter(c)) continue;
            letters++;
            byte dir = Character.getDirectionality(c);
            if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                    || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                rtl++;
            }
        }
        return (letters > 0 && rtl * 2 > letters) ? WritingDirection.RTL : WritingDirection.LTR;
    }

    private String cleanOcrParagraphText(final String raw) {
        if (raw == null) return "";
        // Strip trailing standalone chat timestamps like "02:14" or "12:35 PM"
        return raw.replaceAll("\\s+\\d{1,2}:\\d{2}(\\s*[AaPp][Mm])?\\s*$", "").replaceAll("^[|\\[\\]_~]+|[|\\[\\]_~]+$", "").trim();
    }

    private boolean shouldTranslateParagraph(final String text, final String targetLang) {
        if (TextUtils.isEmpty(text) || text.length() < 2) return false;
        // Skip standalone chat timestamp / date divider headers like "07:18", "أمس 07:18", "15:25"
        if (text.matches(".*\\b\\d{1,2}:\\d{2}\\b.*")) {
            String withoutTime = text.replaceAll("\\b\\d{1,2}:\\d{2}\\b", "").replaceAll("[^\\p{L}]", "").trim();
            if (withoutTime.length() < 5) {
                return false;
            }
        }
        int totalLetters = 0;
        int arabicLetters = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                totalLetters++;
                if (c >= 0x0600 && c <= 0x06FF) {
                    arabicLetters++;
                }
            }
        }
        if (totalLetters < 2) return false;
        // If target language is Arabic, skip blocks that are already >60% Arabic
        if ("ar".equalsIgnoreCase(targetLang) && arabicLetters * 5 > totalLetters * 3) {
            return false;
        }
        return true;
    }

    private OcrWord findClosestWordInLine(final OcrLine line, final OcrWord target) {
        OcrWord best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < line.words.size(); i++) {
            OcrWord w = line.words.get(i);
            int d = horizontalDistance(w.boundingBox, target.boundingBox);
            if (d < bestDist) {
                bestDist = d;
                best = w;
            }
        }
        return best;
    }

    private static Rect clampToBitmap(final Rect box, final int bmpW, final int bmpH) {
        return new Rect(
                Math.max(0, box.left),
                Math.max(0, box.top),
                Math.min(bmpW, box.right),
                Math.min(bmpH, box.bottom)
        );
    }

    private static boolean isHorizontalOverlap(final Rect a, final Rect b) {
        return a.left <= b.right && a.right >= b.left;
    }

    private static boolean isVerticalOverlap(final Rect a, final Rect b) {
        return a.top <= b.bottom && a.bottom >= b.top;
    }

    private static double horizontalOverlapRatio(final Rect a, final Rect b) {
        int start = Math.max(a.left, b.left);
        int end = Math.min(a.right, b.right);
        int len = end - start;
        int minW = Math.min(a.width(), b.width());
        if (minW <= 0) return 0.0;
        return (double) len / minW;
    }

    private static int horizontalDistance(final Rect a, final Rect b) {
        if (a.right < b.left) return b.left - a.right;
        if (b.right < a.left) return a.left - b.right;
        return 0;
    }

    private static int verticalDistance(final Rect a, final Rect b) {
        if (a.bottom < b.top) return b.top - a.bottom;
        if (b.bottom < a.top) return a.top - b.bottom;
        return 0;
    }

    private static double divideByLarger(final double a, final double b) {
        double max = Math.max(a, b);
        if (max <= 0.0) return 0.0;
        return Math.min(a, b) / max;
    }
}
