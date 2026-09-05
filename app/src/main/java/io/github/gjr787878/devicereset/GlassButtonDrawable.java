package io.github.gjr787878.devicereset;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 玻璃拟态按钮背景 Drawable。
 *
 * 综合 iOS 26 Liquid Glass 与深色玻璃按钮设计（参考图二/图三），由以下几层叠加：
 *   1. 半透明深色填充（玻璃底色）
 *   2. 顶部白色高光渐变（玻璃顶面反光）
 *   3. 底部黑色内阴影（玻璃厚度感）
 *   4. 顶部细亮线（玻璃边缘反光）
 *   5. 上亮下暗的渐变描边（玻璃边缘折射）
 *
 * 选中态：填充加深、描边加粗加亮、配合外部蓝色文字。
 * 按压态：填充轻微提亮，保留反馈。
 */
public class GlassButtonDrawable extends Drawable {

    // 玻璃底色（未选中 / 选中 / 按压）—— 低不透明度，底部内容可穿透可见
    private static final int FILL_NORMAL = 0x331C1C1E;   // 20% 不透明度，高通透
    private static final int FILL_SELECTED = 0x591C1C1E; // 35% 不透明度
    private static final int FILL_PRESSED = 0x4C1C1C1E;  // 30% 不透明度

    // 顶部高光
    private static final int HIGHLIGHT_TOP = 0x4DFFFFFF;    // 30% 白
    private static final int HIGHLIGHT_BOTTOM = 0x00FFFFFF; // 透明

    // 底部内阴影
    private static final int SHADOW_TOP = 0x00000000;
    private static final int SHADOW_BOTTOM = 0x40000000; // 25% 黑

    // 顶部边缘亮线
    private static final int TOP_GLINT = 0x66FFFFFF; // 40% 白

    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float mCornerRadiusPx;
    private final float mBorderWidthPx;
    private boolean mSelected = false;
    private boolean mPressed = false;

    public GlassButtonDrawable(float cornerRadiusPx, float borderWidthPx, boolean selected) {
        mCornerRadiusPx = cornerRadiusPx;
        mBorderWidthPx = borderWidthPx;
        mSelected = selected;
    }

    public void setGlassSelected(boolean selected) {
        if (mSelected != selected) {
            mSelected = selected;
            invalidateSelf();
        }
    }

    public boolean isGlassSelected() {
        return mSelected;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean pressed = false;
        if (state != null) {
            for (int s : state) {
                if (s == android.R.attr.state_pressed) {
                    pressed = true;
                    break;
                }
            }
        }
        if (mPressed != pressed) {
            mPressed = pressed;
            invalidateSelf();
            return true;
        }
        return false;
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;

        RectF rect = new RectF(b);
        Path clip = new Path();
        clip.addRoundRect(rect, mCornerRadiusPx, mCornerRadiusPx, Path.Direction.CW);

        canvas.save();
        canvas.clipPath(clip);

        // 1. 半透明深色填充
        int fillColor;
        if (mPressed) {
            fillColor = FILL_PRESSED;
        } else {
            fillColor = mSelected ? FILL_SELECTED : FILL_NORMAL;
        }
        mFillPaint.setColor(fillColor);
        mFillPaint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(rect, mCornerRadiusPx, mCornerRadiusPx, mFillPaint);

        // 2. 顶部高光渐变（覆盖顶部 45%）
        float highlightBottom = b.top + b.height() * 0.45f;
        LinearGradient highlight = new LinearGradient(
                0, b.top, 0, highlightBottom,
                HIGHLIGHT_TOP, HIGHLIGHT_BOTTOM, Shader.TileMode.CLAMP);
        mHighlightPaint.setShader(highlight);
        mHighlightPaint.setStyle(Paint.Style.FILL);
        canvas.drawRect(b.left, b.top, b.right, highlightBottom, mHighlightPaint);

        // 3. 底部内阴影（从 55% 处开始）
        float shadowTop = b.top + b.height() * 0.55f;
        LinearGradient shadow = new LinearGradient(
                0, shadowTop, 0, b.bottom,
                SHADOW_TOP, SHADOW_BOTTOM, Shader.TileMode.CLAMP);
        mShadowPaint.setShader(shadow);
        mShadowPaint.setStyle(Paint.Style.FILL);
        canvas.drawRect(b.left, shadowTop, b.right, b.bottom, mShadowPaint);

        // 4. 顶部细亮线（玻璃边缘反光，被圆角裁剪后自然呈弧线）
        mGlintPaint.setColor(TOP_GLINT);
        mGlintPaint.setStrokeWidth(1.5f);
        mGlintPaint.setStyle(Paint.Style.STROKE);
        float glintInset = mCornerRadiusPx * 0.25f;
        canvas.drawLine(b.left + glintInset, b.top + 1.5f,
                b.right - glintInset, b.top + 1.5f, mGlintPaint);

        canvas.restore();

        // 5. 渐变描边（上亮下暗）
        int borderTop = mSelected ? 0xFFFFFFFF : 0x99FFFFFF;
        int borderBottom = mSelected ? 0x80FFFFFF : 0x40FFFFFF;
        LinearGradient borderGrad = new LinearGradient(
                0, b.top, 0, b.bottom,
                borderTop, borderBottom, Shader.TileMode.CLAMP);
        mBorderPaint.setShader(borderGrad);
        mBorderPaint.setStrokeWidth(mBorderWidthPx);
        mBorderPaint.setStyle(Paint.Style.STROKE);
        // 描边向内缩半个宽度，避免被边界裁剪
        RectF borderRect = new RectF(rect);
        float inset = mBorderWidthPx / 2f;
        borderRect.inset(inset, inset);
        float borderRadius = Math.max(0, mCornerRadiusPx - inset);
        canvas.drawRoundRect(borderRect, borderRadius, borderRadius, mBorderPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        // 玻璃效果由内部多层 alpha 控制，不支持外部统一 alpha
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // 不支持
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
