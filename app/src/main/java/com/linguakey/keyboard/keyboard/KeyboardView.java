/*
 * Copyright (C) 2010 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
 * Copyright (C) 2021 wittmane
 * Copyright (C) 2026 LinguaKey Performance Engine
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linguakey.keyboard.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.HashSet;

import com.linguakey.keyboard.R;
import com.linguakey.keyboard.compat.PreferenceManagerCompat;
import com.linguakey.keyboard.keyboard.internal.KeyDrawParams;
import com.linguakey.keyboard.keyboard.internal.KeyVisualAttributes;
import com.linguakey.keyboard.latin.common.Constants;
import com.linguakey.keyboard.latin.settings.Settings;
import com.linguakey.keyboard.latin.utils.TypefaceUtils;

/**
 * High-performance, GPU-friendly virtual {@link Keyboard} renderer.
 * Uses a static offscreen keyboard bitmap buffer so only pressed/pulsing keys
 * are redrawn on each frame, delivering locked 60/120fps responsiveness with
 * a subtle zero-allocation tactile key pulse animation.
 */
public class KeyboardView extends View {
    // XML attributes
    private final KeyVisualAttributes mKeyVisualAttributes;
    private final int mDefaultKeyLabelFlags;
    private final float mKeyHintLetterPadding;
    private final float mKeyShiftedLetterHintPadding;
    private final float mKeyTextShadowRadius;
    private final float mVerticalCorrection;
    private final Drawable mKeyBackground;
    private final Drawable mFunctionalKeyBackground;
    private final Drawable mSpacebarBackground;
    private final Rect mKeyBackgroundPadding = new Rect();
    private static final float KET_TEXT_SHADOW_RADIUS_DISABLED = -1.0f;
    protected int mCustomColor = 0;
    protected KeyboardTheme mTheme;

    // Personalization customizations
    private String mKeyShapeSetting = "default";
    private boolean mKeyBgImageActive = false;
    private Bitmap mCustomKeyBgBitmap = null;
    private int mKeyNormalColor = 0;
    private int mKeyPressedColor = 0;
    private boolean mColorsResolved = false;
    private String mKeySizeMode = "uniform";
    private int mKeySizeScale = 100;
    private int mKeyWidthScale = 100;
    private int mKeyHeightScale = 100;
    private int mKeyGapX = 1; // dp
    private int mKeyGapY = 2; // dp
    private boolean mKbdBgImageActive = false;
    private String mKbdBgImageStyle = "stretch";
    private Bitmap mCustomKbdBgBitmap = null;

    // The maximum key label width in proportion to the key width.
    private static final float MAX_LABEL_RATIO = 0.90f;

    // Main keyboard
    private Keyboard mKeyboard;
    private final KeyDrawParams mKeyDrawParams = new KeyDrawParams();

    // Drawing & Static Buffer Caching
    private boolean mInvalidateAllKeys;
    private final HashSet<Key> mInvalidatedKeys = new HashSet<>();
    private final Rect mClipRect = new Rect();
    private final Rect mTempSrcRect = new Rect();
    private final Rect mTempDstRect = new Rect();
    private final RectF mTempRectF = new RectF();
    private final Rect mPulseDirtyRect = new Rect();

    /** Cached bitmap of all unpressed keys for O(1) per-frame GPU blit */
    private Bitmap mStaticKeyboardBuffer;
    private final Canvas mStaticKeyboardCanvas = new Canvas();
    private boolean mStaticBufferDirty = true;

    /** Legacy software fallback buffer */
    private Bitmap mOffscreenBuffer;
    private final Canvas mOffscreenCanvas = new Canvas();

    private final Paint mPaint = new Paint();
    private final Paint.FontMetrics mFontMetrics = new Paint.FontMetrics();
    private final Paint mKeyFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mKeyStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mKeyOverlayPaint = new Paint();
    private final Paint mMeasurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBgClearPaint = new Paint();
    private final Paint mPulseGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float mCachedDensity = -1f;
    private float mCachedCornerRadius = -1f;
    private boolean mCachedIsDarkTheme = true;
    private int mAppliedCustomBgColor = Integer.MIN_VALUE;
    private int mResolvedKeyboardBgColor = Color.BLACK;

    // Zero-allocation tactile key pulse engine (max 6 concurrent pulsing keys)
    private static final int MAX_PULSING_KEYS = 6;
    private static final long PULSE_DURATION_MS = 95L;
    private static final float PRESSED_SCALE = 0.955f;
    private static final float PULSE_PEAK_SCALE = 1.032f;

    private static final class PulseEntry {
        Key key;
        long startTimeMs;
        boolean active;
    }

    private final PulseEntry[] mPulsePool = new PulseEntry[MAX_PULSING_KEYS];
    private final Key[] mActivePressedKeys = new Key[8];
    private int mActivePressedCount = 0;

    public KeyboardView(final Context context, final AttributeSet attrs) {
        this(context, attrs, R.attr.keyboardViewStyle);
    }

    public KeyboardView(final Context context, final AttributeSet attrs, final int defStyle) {
        super(context, attrs, defStyle);

        for (int i = 0; i < MAX_PULSING_KEYS; i++) {
            mPulsePool[i] = new PulseEntry();
        }

        final TypedArray keyboardViewAttr = context.obtainStyledAttributes(attrs,
                R.styleable.KeyboardView, defStyle, R.style.KeyboardView);
        mKeyBackground = keyboardViewAttr.getDrawable(R.styleable.KeyboardView_keyBackground);
        if (mKeyBackground != null) {
            mKeyBackground.getPadding(mKeyBackgroundPadding);
        }
        final Drawable functionalKeyBackground = keyboardViewAttr.getDrawable(
                R.styleable.KeyboardView_functionalKeyBackground);
        mFunctionalKeyBackground = (functionalKeyBackground != null) ? functionalKeyBackground
                : mKeyBackground;
        final Drawable spacebarBackground = keyboardViewAttr.getDrawable(
                R.styleable.KeyboardView_spacebarBackground);
        mSpacebarBackground = (spacebarBackground != null) ? spacebarBackground : mKeyBackground;
        mKeyHintLetterPadding = keyboardViewAttr.getDimension(
                R.styleable.KeyboardView_keyHintLetterPadding, 0.0f);
        mKeyShiftedLetterHintPadding = keyboardViewAttr.getDimension(
                R.styleable.KeyboardView_keyShiftedLetterHintPadding, 0.0f);
        mKeyTextShadowRadius = keyboardViewAttr.getFloat(
                R.styleable.KeyboardView_keyTextShadowRadius, KET_TEXT_SHADOW_RADIUS_DISABLED);
        mVerticalCorrection = keyboardViewAttr.getDimension(
                R.styleable.KeyboardView_verticalCorrection, 0.0f);
        keyboardViewAttr.recycle();

        final TypedArray keyAttr = context.obtainStyledAttributes(attrs,
                R.styleable.Keyboard_Key, defStyle, R.style.KeyboardView);
        mDefaultKeyLabelFlags = keyAttr.getInt(R.styleable.Keyboard_Key_keyLabelFlags, 0);
        mKeyVisualAttributes = KeyVisualAttributes.newInstance(keyAttr);
        keyAttr.recycle();

        mCachedDensity = getResources().getDisplayMetrics().density;
        mPaint.setAntiAlias(true);
        mPaint.setSubpixelText(true);
        mBgClearPaint.setStyle(Paint.Style.FILL);
        mPulseGlowPaint.setStyle(Paint.Style.FILL);
    }

    private static void blendAlpha(final Paint paint, final int alpha) {
        if (alpha >= Constants.Color.ALPHA_OPAQUE) {
            return;
        }
        final int color = paint.getColor();
        paint.setARGB((paint.getAlpha() * alpha) / Constants.Color.ALPHA_OPAQUE,
                Color.red(color), Color.green(color), Color.blue(color));
    }

    public void setKeyboard(final Keyboard keyboard) {
        mKeyboard = keyboard;
        final int keyHeight = keyboard.mMostCommonKeyHeight;
        mKeyDrawParams.updateParams(keyHeight, mKeyVisualAttributes);
        mKeyDrawParams.updateParams(keyHeight, keyboard.mKeyVisualAttributes);
        final SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(getContext());
        mCustomColor = Settings.readKeyboardColor(prefs, getContext());
        mTheme = Settings.getKeyboardTheme(getContext());

        mKeyShapeSetting = prefs.getString("pref_key_shape", "default");
        mKeyBgImageActive = prefs.getBoolean("pref_key_bg_image_active", false);
        mKbdBgImageActive = prefs.getBoolean("pref_kbd_bg_image_active", false);
        mKbdBgImageStyle = prefs.getString("pref_kbd_bg_image_style", "stretch");
        mKeySizeMode = prefs.getString("pref_key_size_mode", "uniform");
        mKeySizeScale = prefs.getInt("pref_key_size_scale", 100);
        mKeyWidthScale = prefs.getInt("pref_key_width_scale", 100);
        mKeyHeightScale = prefs.getInt("pref_key_height_scale", 100);
        mKeyGapX = prefs.getInt("pref_key_gap_x", 1);
        mKeyGapY = prefs.getInt("pref_key_gap_y", 2);
        loadPersonalizationAssets();
        mColorsResolved = false;

        clearActivePulsesAndPressed();
        mStaticBufferDirty = true;
        invalidateAllKeys();
        requestLayout();
    }

    public Keyboard getKeyboard() {
        return mKeyboard;
    }

    protected float getVerticalCorrection() {
        return mVerticalCorrection;
    }

    protected KeyDrawParams getKeyDrawParams() {
        return mKeyDrawParams;
    }

    /**
     * Called when a key is pressed down to track it in the fast overlay set.
     */
    protected void registerPressedKey(final Key key) {
        if (key == null) return;
        // Cancel any ongoing release pulse for the same key so press feels instant
        for (int i = 0; i < MAX_PULSING_KEYS; i++) {
            final PulseEntry entry = mPulsePool[i];
            if (entry.active && entry.key == key) {
                entry.active = false;
                entry.key = null;
            }
        }
        // Add to active pressed keys array if not already present
        for (int i = 0; i < mActivePressedCount; i++) {
            if (mActivePressedKeys[i] == key) {
                return;
            }
        }
        if (mActivePressedCount < mActivePressedKeys.length) {
            mActivePressedKeys[mActivePressedCount++] = key;
        }
    }

    /**
     * Called when a key is released to start a lightweight 95ms tactile spring-back pulse.
     */
    protected void triggerKeyReleasePulse(final Key key) {
        if (key == null) return;
        // Remove from active pressed keys
        for (int i = 0; i < mActivePressedCount; i++) {
            if (mActivePressedKeys[i] == key) {
                mActivePressedKeys[i] = mActivePressedKeys[--mActivePressedCount];
                mActivePressedKeys[mActivePressedCount] = null;
                break;
            }
        }
        // Skip pulse for spacer keys or MoreKeysKeyboard
        if (key.isSpacer() || (mKeyboard != null && mKeyboard.getClass() == MoreKeysKeyboard.class)) {
            return;
        }
        // Reuse existing slot for same key, or first inactive slot, or oldest slot
        PulseEntry target = null;
        long oldestTime = Long.MAX_VALUE;
        PulseEntry oldestEntry = mPulsePool[0];
        for (int i = 0; i < MAX_PULSING_KEYS; i++) {
            final PulseEntry entry = mPulsePool[i];
            if (entry.active && entry.key == key) {
                target = entry;
                break;
            }
            if (!entry.active && target == null) {
                target = entry;
            }
            if (entry.startTimeMs < oldestTime) {
                oldestTime = entry.startTimeMs;
                oldestEntry = entry;
            }
        }
        if (target == null) {
            target = oldestEntry;
        }
        target.key = key;
        target.startTimeMs = SystemClock.uptimeMillis();
        target.active = true;
        invalidateKeyBoundsWithMargin(key);
    }

    protected void clearActivePulsesAndPressed() {
        for (int i = 0; i < mActivePressedCount; i++) {
            mActivePressedKeys[i] = null;
        }
        mActivePressedCount = 0;
        for (int i = 0; i < MAX_PULSING_KEYS; i++) {
            mPulsePool[i].active = false;
            mPulsePool[i].key = null;
        }
    }

    private void invalidateKeyBoundsWithMargin(final Key key) {
        if (key == null) return;
        final int margin = (int) (4f * mCachedDensity) + 2;
        final int left = Math.max(0, key.getX() + getPaddingLeft() - margin);
        final int top = Math.max(0, key.getY() + getPaddingTop() - margin);
        final int right = key.getX() + getPaddingLeft() + key.getWidth() + margin;
        final int bottom = key.getY() + getPaddingTop() + key.getHeight() + margin;
        postInvalidateOnAnimation(left, top, right, bottom);
    }

    @Override
    protected void onSizeChanged(final int w, final int h, final int oldw, final int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) {
            mStaticBufferDirty = true;
        }
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        final Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        final int width = keyboard.mOccupiedWidth + getPaddingLeft() + getPaddingRight();
        final int height = keyboard.mOccupiedHeight + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        super.onDraw(canvas);
        final Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            return;
        }

        if (canvas.isHardwareAccelerated()) {
            onDrawHardwareAccelerated(canvas, keyboard);
            return;
        }

        final boolean bufferNeedsUpdates = mInvalidateAllKeys || !mInvalidatedKeys.isEmpty();
        if (bufferNeedsUpdates || mOffscreenBuffer == null || mOffscreenBuffer.isRecycled()) {
            if (maybeAllocateOffscreenBuffer()) {
                mInvalidateAllKeys = true;
                mOffscreenCanvas.setBitmap(mOffscreenBuffer);
            }
            onDrawKeyboardSoftware(mOffscreenCanvas, keyboard);
        }
        if (mOffscreenBuffer != null && !mOffscreenBuffer.isRecycled()) {
            canvas.drawBitmap(mOffscreenBuffer, 0.0f, 0.0f, null);
        }
    }

    private void resolveKeyboardBackground(final Keyboard keyboard) {
        final Drawable background = getBackground();
        if (background != null && mTheme != null && mTheme.mCustomColorSupport) {
            if (keyboard.getClass() == MoreKeysKeyboard.class) {
                background.setColorFilter(mCustomColor, PorterDuff.Mode.OVERLAY);
            } else if (mAppliedCustomBgColor != mCustomColor) {
                mAppliedCustomBgColor = mCustomColor;
                setBackgroundColor(mCustomColor);
            }
        }
        if (mTheme != null && mTheme.mCustomColorSupport && mCustomColor != 0) {
            mResolvedKeyboardBgColor = mCustomColor;
        } else if (background instanceof ColorDrawable) {
            mResolvedKeyboardBgColor = ((ColorDrawable) background).getColor();
        } else {
            mResolvedKeyboardBgColor = Color.BLACK;
        }
    }

    private boolean ensureStaticBuffer(final int width, final int height) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        if (mStaticKeyboardBuffer != null && !mStaticKeyboardBuffer.isRecycled()
                && mStaticKeyboardBuffer.getWidth() == width
                && mStaticKeyboardBuffer.getHeight() == height) {
            return true;
        }
        freeStaticBuffer();
        try {
            mStaticKeyboardBuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            mStaticKeyboardCanvas.setBitmap(mStaticKeyboardBuffer);
            mStaticBufferDirty = true;
            return true;
        } catch (OutOfMemoryError e) {
            mStaticKeyboardBuffer = null;
            return false;
        }
    }

    private void freeStaticBuffer() {
        mStaticKeyboardCanvas.setBitmap(null);
        mStaticKeyboardCanvas.setMatrix(null);
        if (mStaticKeyboardBuffer != null) {
            if (!mStaticKeyboardBuffer.isRecycled()) {
                mStaticKeyboardBuffer.recycle();
            }
            mStaticKeyboardBuffer = null;
        }
        mStaticBufferDirty = true;
    }

    /**
     * Ultra-fast hardware-accelerated rendering path:
     * 1. Blits the pre-rendered static keyboard bitmap in a single GPU call.
     * 2. Overlays ONLY currently pressed keys and actively pulsing keys (1-2 keys max).
     */
    private void onDrawHardwareAccelerated(final Canvas canvas, final Keyboard keyboard) {
        resolveKeyboardBackground(keyboard);
        final Paint paint = mPaint;
        paint.setFlags(paint.getFlags() | Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);

        // MoreKeysKeyboard is tiny (2-6 keys) and uses a popup background; draw directly.
        if (keyboard.getClass() == MoreKeysKeyboard.class) {
            for (final Key key : keyboard.getSortedKeys()) {
                onDrawKey(key, canvas, paint, false, 1.0f, 0f);
            }
            mInvalidatedKeys.clear();
            mInvalidateAllKeys = false;
            return;
        }

        final int width = getWidth();
        final int height = getHeight();
        if (!ensureStaticBuffer(width, height)) {
            drawKbdBg(canvas);
            for (final Key key : keyboard.getSortedKeys()) {
                onDrawKey(key, canvas, paint, false, 1.0f, 0f);
            }
            mInvalidatedKeys.clear();
            mInvalidateAllKeys = false;
            return;
        }

        // Rebuild full static buffer only when layout, theme, or all keys changed
        if (mStaticBufferDirty || mInvalidateAllKeys) {
            mStaticKeyboardCanvas.drawColor(mResolvedKeyboardBgColor);
            drawKbdBg(mStaticKeyboardCanvas);
            for (final Key key : keyboard.getSortedKeys()) {
                onDrawKey(key, mStaticKeyboardCanvas, paint, true /* forceUnpressed */, 1.0f, 0f);
            }
            mStaticBufferDirty = false;
            mInvalidateAllKeys = false;
            mInvalidatedKeys.clear();
        } else if (!mInvalidatedKeys.isEmpty()) {
            // Patch any non-pressed invalidated key (e.g., spacebar language change or shift icon)
            for (final Key key : mInvalidatedKeys) {
                if (keyboard.hasKey(key) && !key.isPressed() && !isKeyPulsing(key)) {
                    clearKeyCell(mStaticKeyboardCanvas, key);
                    onDrawKey(key, mStaticKeyboardCanvas, paint, true /* forceUnpressed */, 1.0f, 0f);
                }
            }
            mInvalidatedKeys.clear();
        }

        // Single O(1) GPU blit of the entire static keyboard!
        canvas.drawBitmap(mStaticKeyboardBuffer, 0.0f, 0.0f, null);

        // Overlay actively pressed keys with crisp depressed scale
        for (int i = 0; i < mActivePressedCount; i++) {
            final Key pressedKey = mActivePressedKeys[i];
            if (pressedKey != null && keyboard.hasKey(pressedKey) && pressedKey.isPressed()) {
                clearKeyCell(canvas, pressedKey);
                onDrawKey(pressedKey, canvas, paint, false, PRESSED_SCALE, 0.10f);
            }
        }

        // Overlay actively pulsing released keys (smooth 95ms spring-back pop)
        final long now = SystemClock.uptimeMillis();
        boolean anyPulseRemaining = false;
        mPulseDirtyRect.setEmpty();

        for (int i = 0; i < MAX_PULSING_KEYS; i++) {
            final PulseEntry entry = mPulsePool[i];
            if (!entry.active || entry.key == null) {
                continue;
            }
            final Key pulseKey = entry.key;
            if (!keyboard.hasKey(pulseKey) || pulseKey.isPressed()) {
                entry.active = false;
                entry.key = null;
                continue;
            }
            final long elapsed = now - entry.startTimeMs;
            if (elapsed >= PULSE_DURATION_MS) {
                entry.active = false;
                entry.key = null;
                continue;
            }
            final float t = (float) elapsed / (float) PULSE_DURATION_MS;
            final float scale;
            if (t < 0.42f) {
                // Fast rise from depressed scale (0.955) to subtle peak (1.032)
                final float p = t / 0.42f;
                final float eased = 1.0f - (1.0f - p) * (1.0f - p);
                scale = PRESSED_SCALE + (PULSE_PEAK_SCALE - PRESSED_SCALE) * eased;
            } else {
                // Smooth settle from peak (1.032) back to rest (1.0)
                final float p = (t - 0.42f) / 0.58f;
                final float eased = 1.0f - (1.0f - p) * (1.0f - p);
                scale = PULSE_PEAK_SCALE + (1.0f - PULSE_PEAK_SCALE) * eased;
            }
            final float glowAlpha = (1.0f - t) * 0.14f;

            clearKeyCell(canvas, pulseKey);
            onDrawKey(pulseKey, canvas, paint, true /* forceUnpressed */, scale, glowAlpha);

            anyPulseRemaining = true;
            final int margin = (int) (4f * mCachedDensity) + 2;
            final int l = Math.max(0, pulseKey.getX() + getPaddingLeft() - margin);
            final int tp = Math.max(0, pulseKey.getY() + getPaddingTop() - margin);
            final int r = Math.min(width, pulseKey.getX() + getPaddingLeft() + pulseKey.getWidth() + margin);
            final int b = Math.min(height, pulseKey.getY() + getPaddingTop() + pulseKey.getHeight() + margin);
            mPulseDirtyRect.union(l, tp, r, b);
        }

        if (anyPulseRemaining && !mPulseDirtyRect.isEmpty()) {
            postInvalidateOnAnimation(
                    mPulseDirtyRect.left, mPulseDirtyRect.top,
                    mPulseDirtyRect.right, mPulseDirtyRect.bottom);
        }
    }

    private boolean isKeyPulsing(final Key key) {
        for (int i = 0; i < MAX_PULSING_KEYS; i++) {
            if (mPulsePool[i].active && mPulsePool[i].key == key) {
                return true;
            }
        }
        return false;
    }

    private void clearKeyCell(final Canvas canvas, final Key key) {
        final int x = key.getX() + getPaddingLeft();
        final int y = key.getY() + getPaddingTop();
        final int r = x + key.getWidth();
        final int b = y + key.getHeight();
        mClipRect.set(x, y, r, b);
        canvas.save();
        canvas.clipRect(mClipRect);
        mBgClearPaint.setColor(mResolvedKeyboardBgColor);
        canvas.drawRect(mClipRect, mBgClearPaint);
        drawKbdBg(canvas);
        canvas.restore();
    }

    private boolean maybeAllocateOffscreenBuffer() {
        final int width = getWidth();
        final int height = getHeight();
        if (width == 0 || height == 0) {
            return false;
        }
        if (mOffscreenBuffer != null && !mOffscreenBuffer.isRecycled()
                && mOffscreenBuffer.getWidth() == width
                && mOffscreenBuffer.getHeight() == height) {
            return false;
        }
        freeOffscreenBuffer();
        mOffscreenBuffer = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        return true;
    }

    private void freeOffscreenBuffer() {
        mOffscreenCanvas.setBitmap(null);
        mOffscreenCanvas.setMatrix(null);
        if (mOffscreenBuffer != null) {
            if (!mOffscreenBuffer.isRecycled()) {
                mOffscreenBuffer.recycle();
            }
            mOffscreenBuffer = null;
        }
    }

    private void onDrawKeyboardSoftware(final Canvas canvas, final Keyboard keyboard) {
        resolveKeyboardBackground(keyboard);
        final Paint paint = mPaint;
        paint.setFlags(paint.getFlags() | Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        final Drawable background = getBackground();
        final boolean drawAllKeys = mInvalidateAllKeys || mInvalidatedKeys.isEmpty();
        if (drawAllKeys) {
            if (background != null) {
                canvas.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR);
                background.draw(canvas);
            }
            drawKbdBg(canvas);
            for (final Key key : keyboard.getSortedKeys()) {
                onDrawKey(key, canvas, paint, false, 1.0f, 0f);
            }
        } else {
            for (final Key key : mInvalidatedKeys) {
                if (!keyboard.hasKey(key)) {
                    continue;
                }
                if (background != null) {
                    final int x = key.getX() + getPaddingLeft();
                    final int y = key.getY() + getPaddingTop();
                    mClipRect.set(x, y, x + key.getWidth(), y + key.getHeight());
                    canvas.save();
                    canvas.clipRect(mClipRect);
                    canvas.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR);
                    background.draw(canvas);
                    drawKbdBg(canvas);
                    canvas.restore();
                }
                onDrawKey(key, canvas, paint, false, 1.0f, 0f);
            }
        }
        mInvalidatedKeys.clear();
        mInvalidateAllKeys = false;
    }

    private void onDrawKey(final Key key, final Canvas canvas, final Paint paint,
            final boolean forceUnpressed, final float pulseScale, final float pulseGlowAlpha) {
        final int keyDrawX = key.getX() + getPaddingLeft();
        final int keyDrawY = key.getY() + getPaddingTop();
        final boolean hasScaleAnim = (pulseScale != 1.0f);

        if (hasScaleAnim) {
            canvas.save();
            canvas.translate(keyDrawX, keyDrawY);
            canvas.scale(pulseScale, pulseScale, key.getWidth() * 0.5f, key.getHeight() * 0.5f);
        } else {
            canvas.translate(keyDrawX, keyDrawY);
        }

        final KeyVisualAttributes attr = key.getVisualAttributes();
        final KeyDrawParams params = mKeyDrawParams.mayCloneAndUpdateParams(key.getHeight(), attr);
        params.mAnimAlpha = Constants.Color.ALPHA_OPAQUE;

        if (!key.isSpacer()) {
            final Drawable background = key.selectBackgroundDrawable(
                    mKeyBackground, mFunctionalKeyBackground, mSpacebarBackground);
            if (background != null) {
                onDrawKeyBackground(key, canvas, background, forceUnpressed, pulseGlowAlpha);
            }
        }
        onDrawKeyTopVisuals(key, canvas, paint, params);

        if (hasScaleAnim) {
            canvas.restore();
        } else {
            canvas.translate(-keyDrawX, -keyDrawY);
        }
    }

    // Draw key background.
    protected void onDrawKeyBackground(final Key key, final Canvas canvas,
            final Drawable background) {
        onDrawKeyBackground(key, canvas, background, false, 0f);
    }

    private void onDrawKeyBackground(final Key key, final Canvas canvas,
            final Drawable background, final boolean forceUnpressed, final float pulseGlowAlpha) {
        final int keyWidth = key.getWidth();
        final int keyHeight = key.getHeight();
        final Rect padding = mKeyBackgroundPadding;
        final int bgWidth = keyWidth + padding.left + padding.right;
        final int bgHeight = keyHeight + padding.top + padding.bottom;
        final int bgX = -padding.left;
        final int bgY = -padding.top;

        final boolean isPressed = !forceUnpressed && key.isPressed();
        final boolean isCustomScale = "custom".equals(mKeySizeMode);
        final boolean hasScale = (isCustomScale && (mKeyWidthScale != 100 || mKeyHeightScale != 100))
                || (!isCustomScale && mKeySizeScale != 100);

        if (!mColorsResolved) {
            final android.util.TypedValue typedValue = new android.util.TypedValue();
            if (getContext().getTheme().resolveAttribute(R.attr.keyNormalBackgroundColor, typedValue, true)) {
                mKeyNormalColor = typedValue.data;
            } else {
                mKeyNormalColor = 0xFF222427;
            }
            if (getContext().getTheme().resolveAttribute(R.attr.keyPressedBackgroundColor, typedValue, true)) {
                mKeyPressedColor = typedValue.data;
            } else {
                mKeyPressedColor = 0xFF3E434A;
            }
            mCachedIsDarkTheme = isColorDark(mKeyNormalColor);
            mColorsResolved = true;
        }

        if (mCachedDensity <= 0f) {
            mCachedDensity = getResources().getDisplayMetrics().density;
        }
        if (mCachedCornerRadius <= 0f) {
            mCachedCornerRadius = Math.max(6.5f * mCachedDensity, getResources().getDimension(R.dimen.button_corner_radius_lxx));
        }
        final float density = mCachedDensity;
        final float gapX = mKeyGapX * density;
        final float gapY = mKeyGapY * density;
        float left = bgX + gapX;
        float top = bgY + gapY;
        float right = bgX + bgWidth - gapX;
        float bottom = bgY + bgHeight - gapY;

        if (left >= right || top >= bottom) {
            left = 0;
            top = 0;
            right = keyWidth;
            bottom = keyHeight;
        }

        if (hasScale) {
            final float scaleX = isCustomScale ? (mKeyWidthScale / 100.0f) : (mKeySizeScale / 100.0f);
            final float scaleY = isCustomScale ? (mKeyHeightScale / 100.0f) : (mKeySizeScale / 100.0f);
            final float cx = left + (right - left) * 0.5f;
            final float cy = top + (bottom - top) * 0.5f;
            final float halfW = ((right - left) * 0.5f) * scaleX;
            final float halfH = ((bottom - top) * 0.5f) * scaleY;
            left = cx - halfW;
            right = cx + halfW;
            top = cy - halfH;
            bottom = cy + halfH;
        }

        final boolean isDarkTheme = mCachedIsDarkTheme;
        final int bgType = key.getBackgroundType();
        final boolean isActionKey = (bgType == Key.BACKGROUND_TYPE_ACTION || key.getCode() == Constants.CODE_ENTER);
        final boolean isFunctionalKey = (bgType == Key.BACKGROUND_TYPE_FUNCTIONAL
                || bgType == Key.BACKGROUND_TYPE_STICKY_OFF
                || bgType == Key.BACKGROUND_TYPE_STICKY_ON);
        final boolean isStickyOn = (bgType == Key.BACKGROUND_TYPE_STICKY_ON);

        float radius = mCachedCornerRadius;
        if (isActionKey) {
            radius = Math.min(right - left, bottom - top) * 0.48f;
        } else if (hasScale) {
            final float avgScale = isCustomScale ? (mKeyWidthScale + mKeyHeightScale) / 200.0f : mKeySizeScale / 100.0f;
            radius = radius * avgScale;
        }

        // Sleek 3-tier fill color optimized for Pure Black AMOLED & Light themes
        final int fillColor;
        if (isActionKey) {
            if (isPressed) {
                fillColor = isDarkTheme ? 0xFF669DF6 : 0xFF1557B0;
            } else {
                fillColor = isDarkTheme ? 0xFF8AB4F8 : 0xFF1A73E8;
            }
        } else if (isStickyOn) {
            fillColor = isDarkTheme ? 0xFF8AB4F8 : 0xFF1A73E8;
        } else if (isFunctionalKey) {
            if (isPressed) {
                fillColor = mKeyPressedColor;
            } else {
                fillColor = isDarkTheme ? 0xFF16181B : 0xFFDADCE0;
            }
        } else {
            fillColor = isPressed ? mKeyPressedColor : mKeyNormalColor;
        }

        final boolean isStandardRounded = !"circle".equals(mKeyShapeSetting)
                && !"squircle".equals(mKeyShapeSetting)
                && !"hexagon".equals(mKeyShapeSetting)
                && !"square".equals(mKeyShapeSetting);

        if (isStandardRounded && !mKeyBgImageActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Fast zero-allocation GPU hardware-accelerated path for rounded keys
            if (!isPressed) {
                final float shadowOffset = 0.85f * density;
                mKeyStrokePaint.setStyle(Paint.Style.FILL);
                mKeyStrokePaint.setColor(isDarkTheme ? 0x3A000000 : 0x22000000);
                canvas.drawRoundRect(left, top + shadowOffset, right, bottom + shadowOffset, radius, radius, mKeyStrokePaint);
            }
            mKeyFillPaint.setStyle(Paint.Style.FILL);
            mKeyFillPaint.setColor(fillColor);
            if (isPressed) {
                final float pressInset = 0.55f * density;
                canvas.drawRoundRect(left + pressInset, top + pressInset, right - pressInset, bottom - pressInset, radius, radius, mKeyFillPaint);
            } else {
                canvas.drawRoundRect(left, top, right, bottom, radius, radius, mKeyFillPaint);
            }
            // Subtle lively tactile pulse sheen on press/release
            if (pulseGlowAlpha > 0.005f) {
                final int glowAlphaInt = Math.min(255, (int) (pulseGlowAlpha * 255f));
                mPulseGlowPaint.setColor(isDarkTheme
                        ? Color.argb(glowAlphaInt, 255, 255, 255)
                        : Color.argb(glowAlphaInt, 26, 115, 232));
                canvas.drawRoundRect(left, top, right, bottom, radius, radius, mPulseGlowPaint);
            }
        } else {
            android.graphics.Path path = new android.graphics.Path();
            if ("circle".equals(mKeyShapeSetting)) {
                final float cx = left + (right - left) * 0.5f;
                final float cy = top + (bottom - top) * 0.5f;
                final float r = Math.min(right - left, bottom - top) * 0.5f;
                path.addCircle(cx, cy, r, android.graphics.Path.Direction.CW);
            } else if ("squircle".equals(mKeyShapeSetting)) {
                path = getSquirclePath(left, top, right, bottom);
            } else if ("hexagon".equals(mKeyShapeSetting)) {
                path = getHexagonPath(left, top, right, bottom);
            } else if ("square".equals(mKeyShapeSetting)) {
                path.addRect(left, top, right, bottom, android.graphics.Path.Direction.CW);
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    path.addRoundRect(left, top, right, bottom, radius, radius, android.graphics.Path.Direction.CW);
                } else {
                    mTempRectF.set(left, top, right, bottom);
                    path.addRoundRect(mTempRectF, radius, radius, android.graphics.Path.Direction.CW);
                }
            }

            if (!isPressed) {
                mKeyStrokePaint.setStyle(Paint.Style.FILL);
                mKeyStrokePaint.setColor(isDarkTheme ? 0x48000000 : 0x26000000);
                canvas.save();
                canvas.translate(0f, 1.0f * density);
                canvas.drawPath(path, mKeyStrokePaint);
                canvas.restore();
            }

            mKeyFillPaint.setStyle(Paint.Style.FILL);
            mKeyFillPaint.setColor(fillColor);
            canvas.drawPath(path, mKeyFillPaint);

            if (mKeyBgImageActive && mCustomKeyBgBitmap != null && !mCustomKeyBgBitmap.isRecycled()) {
                canvas.save();
                canvas.clipPath(path);
                mTempRectF.set(left, top, right, bottom);
                canvas.drawBitmap(mCustomKeyBgBitmap, null, mTempRectF, null);
                if (isPressed) {
                    mKeyOverlayPaint.setColor(0x40000000);
                    canvas.drawRect(left, top, right, bottom, mKeyOverlayPaint);
                }
                canvas.restore();
            }
            if (pulseGlowAlpha > 0.005f) {
                final int glowAlphaInt = Math.min(255, (int) (pulseGlowAlpha * 255f));
                mPulseGlowPaint.setColor(isDarkTheme
                        ? Color.argb(glowAlphaInt, 255, 255, 255)
                        : Color.argb(glowAlphaInt, 26, 115, 232));
                canvas.drawPath(path, mPulseGlowPaint);
            }
        }
    }

    // Draw key top visuals.
    protected void onDrawKeyTopVisuals(final Key key, final Canvas canvas,
            final Paint paint, final KeyDrawParams params) {
        final int keyWidth = key.getWidth();
        final int keyHeight = key.getHeight();
        final float centerX = keyWidth * 0.5f;
        final float centerY = keyHeight * 0.5f;
        final float density = mCachedDensity > 0f ? mCachedDensity : getResources().getDisplayMetrics().density;

        final Keyboard keyboard = getKeyboard();
        final Drawable icon = (keyboard == null) ? null
                : key.getIcon(keyboard.mIconsSet, params.mAnimAlpha);
        float labelX = centerX;
        float labelBaseline = centerY;
        final String label = key.getLabel();
        if (label != null) {
            paint.setTypeface(key.selectTypeface(params));
            paint.setTextSize(key.selectTextSize(params));
            final float labelCharHeight = TypefaceUtils.getReferenceCharHeight(paint);
            final float labelCharWidth = TypefaceUtils.getReferenceCharWidth(paint);

            final float hintVerticalShift = (key.getHintLabel() != null && !key.hasHintLabel()) ? (1.2f * density) : 0.0f;
            labelBaseline = centerY + labelCharHeight / 2.0f + hintVerticalShift;

            if (key.isAlignLabelOffCenter()) {
                labelX = centerX + params.mLabelOffCenterRatio * labelCharWidth;
                paint.setTextAlign(Align.LEFT);
            } else {
                labelX = centerX;
                paint.setTextAlign(Align.CENTER);
            }
            if (key.needsAutoXScale()) {
                final float ratio = Math.min(1.0f, (keyWidth * MAX_LABEL_RATIO) /
                        TypefaceUtils.getStringWidth(label, paint));
                if (key.needsAutoScale()) {
                    final float autoSize = paint.getTextSize() * ratio;
                    paint.setTextSize(autoSize);
                } else {
                    paint.setTextScaleX(ratio);
                }
            }

            paint.setColor(key.selectTextColor(params));
            if (mKeyTextShadowRadius > 0.0f) {
                paint.setShadowLayer(mKeyTextShadowRadius, 0.0f, 0.0f, params.mTextShadowColor);
            } else {
                paint.clearShadowLayer();
            }

            blendAlpha(paint, params.mAnimAlpha);
            canvas.drawText(label, 0, label.length(), labelX, labelBaseline, paint);
            paint.clearShadowLayer();
            paint.setTextScaleX(1.0f);
        }

        final String hintLabel = key.getHintLabel();
        if (hintLabel != null) {
            final float insetX = mKeyGapX * density;
            final float insetY = mKeyGapY * density;
            paint.setTextSize(key.selectHintTextSize(params));
            paint.setColor(key.selectHintTextColor(params));
            paint.setTypeface(Typeface.DEFAULT);
            blendAlpha(paint, params.mAnimAlpha);
            final float labelCharHeight = TypefaceUtils.getReferenceCharHeight(paint);
            final float labelCharWidth = TypefaceUtils.getReferenceCharWidth(paint);
            final float hintX, hintBaseline;
            if (key.hasHintLabel()) {
                hintX = labelX + params.mHintLabelOffCenterRatio * labelCharWidth;
                if (key.isAlignHintLabelToBottom(mDefaultKeyLabelFlags)) {
                    hintBaseline = labelBaseline;
                } else {
                    hintBaseline = centerY + labelCharHeight / 2.0f;
                }
                paint.setTextAlign(Align.LEFT);
            } else if (key.hasShiftedLetterHint()) {
                hintX = keyWidth - insetX - mKeyShiftedLetterHintPadding - (2.5f * density) - labelCharWidth / 2.0f;
                paint.getFontMetrics(mFontMetrics);
                hintBaseline = insetY + (2.0f * density) - mFontMetrics.top;
                paint.setTextAlign(Align.CENTER);
            } else {
                final float hintDigitWidth = TypefaceUtils.getReferenceDigitWidth(paint);
                final float hintLabelWidth = TypefaceUtils.getStringWidth(hintLabel, paint);
                hintX = keyWidth - insetX - (3.8f * density)
                        - Math.max(hintDigitWidth, hintLabelWidth) / 2.0f;
                hintBaseline = insetY + (2.2f * density) - paint.ascent();
                paint.setTextAlign(Align.CENTER);
            }
            final float adjustmentY = params.mHintLabelVerticalAdjustment * labelCharHeight;
            canvas.drawText(
                    hintLabel, 0, hintLabel.length(), hintX, hintBaseline + adjustmentY, paint);
        }

        if (label == null && icon != null) {
            final int iconWidth = Math.min(icon.getIntrinsicWidth(), keyWidth);
            final int iconHeight = icon.getIntrinsicHeight();
            final int iconY;
            if (key.isAlignIconToBottom()) {
                iconY = keyHeight - iconHeight;
            } else {
                iconY = (keyHeight - iconHeight) / 2;
            }
            final int iconX = (keyWidth - iconWidth) / 2;
            drawIcon(canvas, icon, iconX, iconY, iconWidth, iconHeight);
        }
    }

    protected static void drawIcon(final Canvas canvas, final Drawable icon,
            final int x, final int y, final int width, final int height) {
        canvas.translate(x, y);
        icon.setBounds(0, 0, width, height);
        icon.draw(canvas);
        canvas.translate(-x, -y);
    }

    public Paint newLabelPaint(final Key key) {
        final Paint paint = mMeasurePaint;
        paint.reset();
        paint.setAntiAlias(true);
        if (key == null) {
            paint.setTypeface(mKeyDrawParams.mTypeface);
            paint.setTextSize(mKeyDrawParams.mLabelSize);
        } else {
            paint.setColor(key.selectTextColor(mKeyDrawParams));
            paint.setTypeface(key.selectTypeface(mKeyDrawParams));
            paint.setTextSize(key.selectTextSize(mKeyDrawParams));
        }
        return paint;
    }

    public void invalidateAllKeys() {
        mInvalidatedKeys.clear();
        mInvalidateAllKeys = true;
        mStaticBufferDirty = true;
        invalidate();
    }

    public void invalidateKey(final Key key) {
        if (mInvalidateAllKeys || key == null) {
            return;
        }
        mInvalidatedKeys.add(key);
        final int margin = (int) (4f * mCachedDensity) + 2;
        final int x = Math.max(0, key.getX() + getPaddingLeft() - margin);
        final int y = Math.max(0, key.getY() + getPaddingTop() - margin);
        invalidate(x, y, x + key.getWidth() + margin * 2, y + key.getHeight() + margin * 2);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        clearActivePulsesAndPressed();
        freeStaticBuffer();
        freeOffscreenBuffer();
        if (mCustomKeyBgBitmap != null) {
            if (!mCustomKeyBgBitmap.isRecycled()) mCustomKeyBgBitmap.recycle();
            mCustomKeyBgBitmap = null;
        }
        if (mCustomKbdBgBitmap != null) {
            if (!mCustomKbdBgBitmap.isRecycled()) mCustomKbdBgBitmap.recycle();
            mCustomKbdBgBitmap = null;
        }
    }

    public void deallocateMemory() {
        clearActivePulsesAndPressed();
        freeStaticBuffer();
        freeOffscreenBuffer();
        if (mCustomKeyBgBitmap != null) {
            if (!mCustomKeyBgBitmap.isRecycled()) mCustomKeyBgBitmap.recycle();
            mCustomKeyBgBitmap = null;
        }
        if (mCustomKbdBgBitmap != null) {
            if (!mCustomKbdBgBitmap.isRecycled()) mCustomKbdBgBitmap.recycle();
            mCustomKbdBgBitmap = null;
        }
    }

    private void loadPersonalizationAssets() {
        if (mCustomKeyBgBitmap != null) {
            if (!mCustomKeyBgBitmap.isRecycled()) mCustomKeyBgBitmap.recycle();
            mCustomKeyBgBitmap = null;
        }
        if (mKeyBgImageActive) {
            try {
                java.io.File file = new java.io.File(getContext().getFilesDir(), "key_background_custom.png");
                if (file.exists()) {
                    mCustomKeyBgBitmap = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
                }
            } catch (Exception e) {
                android.util.Log.e("KeyboardView", "Failed to load custom key background bitmap", e);
            }
        }

        if (mCustomKbdBgBitmap != null) {
            if (!mCustomKbdBgBitmap.isRecycled()) mCustomKbdBgBitmap.recycle();
            mCustomKbdBgBitmap = null;
        }
        if (mKbdBgImageActive) {
            try {
                java.io.File file = new java.io.File(getContext().getFilesDir(), "keyboard_background_custom.png");
                if (file.exists()) {
                    mCustomKbdBgBitmap = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
                }
            } catch (Exception e) {
                android.util.Log.e("KeyboardView", "Failed to load custom keyboard background bitmap", e);
            }
        }
    }

    private android.graphics.Path getSquirclePath(float left, float top, float right, float bottom) {
        android.graphics.Path path = new android.graphics.Path();
        float width = right - left;
        float height = bottom - top;
        float cx = left + width / 2.0f;
        float cy = top + height / 2.0f;
        float rx = width / 2.0f;
        float ry = height / 2.0f;

        int steps = 48;
        double n = 4.0;
        for (int i = 0; i < steps; i++) {
            double angle = (2.0 * Math.PI * i) / steps;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);

            double x = Math.signum(cos) * Math.pow(Math.abs(cos), 2.0 / n) * rx;
            double y = Math.signum(sin) * Math.pow(Math.abs(sin), 2.0 / n) * ry;

            if (i == 0) {
                path.moveTo((float)(cx + x), (float)(cy + y));
            } else {
                path.lineTo((float)(cx + x), (float)(cy + y));
            }
        }
        path.close();
        return path;
    }

    private android.graphics.Path getHexagonPath(float left, float top, float right, float bottom) {
        android.graphics.Path path = new android.graphics.Path();
        float width = right - left;
        float height = bottom - top;
        float cy = top + height / 2.0f;

        path.moveTo(left + width / 4.0f, top);
        path.lineTo(right - width / 4.0f, top);
        path.lineTo(right, cy);
        path.lineTo(right - width / 4.0f, bottom);
        path.lineTo(left + width / 4.0f, bottom);
        path.lineTo(left, cy);
        path.close();
        return path;
    }

    private static boolean isColorDark(final int color) {
        final double darkness = 1 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0;
        return darkness >= 0.5;
    }

    private void computeCenterCropRect(int bitmapWidth, int bitmapHeight, int viewWidth, int viewHeight, Rect outRect) {
        float bitmapRatio = (float) bitmapWidth / bitmapHeight;
        float viewRatio = (float) viewWidth / viewHeight;
        int srcLeft = 0, srcTop = 0, srcRight = bitmapWidth, srcBottom = bitmapHeight;
        if (bitmapRatio > viewRatio) {
            int newWidth = (int) (bitmapHeight * viewRatio);
            srcLeft = (bitmapWidth - newWidth) / 2;
            srcRight = srcLeft + newWidth;
        } else {
            int newHeight = (int) (bitmapWidth / viewRatio);
            srcTop = (bitmapHeight - newHeight) / 2;
            srcBottom = srcTop + newHeight;
        }
        outRect.set(srcLeft, srcTop, srcRight, srcBottom);
    }

    private void drawKbdBg(final Canvas canvas) {
        if (mKbdBgImageActive && mCustomKbdBgBitmap != null && !mCustomKbdBgBitmap.isRecycled()) {
            mTempDstRect.set(0, 0, getWidth(), getHeight());
            if ("crop".equals(mKbdBgImageStyle)) {
                computeCenterCropRect(mCustomKbdBgBitmap.getWidth(), mCustomKbdBgBitmap.getHeight(), getWidth(), getHeight(), mTempSrcRect);
                canvas.drawBitmap(mCustomKbdBgBitmap, mTempSrcRect, mTempDstRect, null);
            } else {
                canvas.drawBitmap(mCustomKbdBgBitmap, null, mTempDstRect, null);
            }
        }
    }
}
