package com.linguakey.keyboard.keyboard.internal;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

import java.util.HashSet;

import com.linguakey.keyboard.keyboard.Key;

/**
 * Sleek, hardware-accelerated key preview pill view optimized for Pure Black AMOLED and Light themes.
 */
public class KeyPreviewView extends TextView {
    private static final HashSet<String> sNoScaleXTextSet = new HashSet<>();
    private final GradientDrawable mPillDrawable;
    private final float mDensity;
    private int mLastBgColor = 0;

    public KeyPreviewView(final Context context, final AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyPreviewView(final Context context, final AttributeSet attrs, final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setGravity(Gravity.CENTER);
        mDensity = context.getResources().getDisplayMetrics().density;
        int padH = (int) (3f * mDensity);
        int padBottom = (int) (2f * mDensity);
        setPadding(padH, 0, padH, padBottom);

        mPillDrawable = new GradientDrawable();
        mPillDrawable.setShape(GradientDrawable.RECTANGLE);
        mPillDrawable.setCornerRadius(10f * mDensity);
        mPillDrawable.setColor(0xFF2C3036);
        mPillDrawable.setStroke(Math.max(1, (int) (1f * mDensity)), 0xFF4A5058);
        setBackground(mPillDrawable);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            setElevation(4f * mDensity);
        }
    }

    public void setPreviewVisual(
            final Key key,
            final KeyboardIconsSet iconsSet,
            final KeyDrawParams drawParams,
            final int backgroundColor
    ) {
        final int iconId = key.getIconId();
        if (iconId != KeyboardIconsSet.ICON_UNDEFINED) {
            setCompoundDrawables(null, null, null, key.getPreviewIcon(iconsSet));
            setText(null);
            return;
        }

        setCompoundDrawables(null, null, null, null);
        setTextColor(drawParams.mPreviewTextColor != 0 ? drawParams.mPreviewTextColor : Color.WHITE);
        float targetPx = Math.min(24f * mDensity, key.selectPreviewTextSize(drawParams));
        setTextSize(TypedValue.COMPLEX_UNIT_PX, targetPx);
        setTypeface(Typeface.DEFAULT_BOLD);
        setTextAndScaleX(key.getPreviewLabel());
        updatePillColor(backgroundColor);
    }

    private void setTextAndScaleX(final String text) {
        setTextScaleX(1.0f);
        setText(text);
        if (text == null || sNoScaleXTextSet.contains(text)) {
            return;
        }
        final int maxWidth = (int) (32f * mDensity);
        final float width = getTextWidth(text, getPaint());
        if (width <= maxWidth || width <= 0f) {
            sNoScaleXTextSet.add(text);
            return;
        }
        setTextScaleX(maxWidth / width);
    }

    private void updatePillColor(final int backgroundColor) {
        int resolved = (Color.alpha(backgroundColor) > 0
                && backgroundColor != 0xFF1F1F1F
                && backgroundColor != 0xFF000000)
                ? backgroundColor
                : 0xFF2C3036;
        if (resolved != mLastBgColor) {
            mLastBgColor = resolved;
            mPillDrawable.setColor(resolved);
        }
    }

    public static void clearTextCache() {
        sNoScaleXTextSet.clear();
    }

    private static float getTextWidth(final String text, final TextPaint paint) {
        if (TextUtils.isEmpty(text)) {
            return 0.0f;
        }
        final int len = text.length();
        final float[] widths = new float[len];
        final int count = paint.getTextWidths(text, 0, len, widths);
        float width = 0;
        for (int i = 0; i < count; i++) {
            width += widths[i];
        }
        return width;
    }
}
