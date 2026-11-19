package com.mailcrusher;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.animation.LinearInterpolator;

/** Subtle ambient motion over the dark MailCrusher surface. */
public final class AnimatedAmbientDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float phase;

    public AnimatedAmbientDrawable() {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        animator.setDuration(18000L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> {
            phase = (Float) value.getAnimatedValue();
            invalidateSelf();
        });
        animator.start();
    }

    @Override public void draw(Canvas canvas) {
        android.graphics.Rect b = getBounds();
        float w = b.width();
        float h = b.height();
        paint.setShader(new LinearGradient(0, 0, w, h,
                new int[]{Color.rgb(11, 13, 16), Color.rgb(14, 16, 21), Color.rgb(11, 13, 16)},
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(b, paint);

        float radius = Math.max(w, h) * 0.72f;
        float x1 = w * (0.18f + 0.07f * (float) Math.sin(phase));
        float y1 = h * (0.18f + 0.05f * (float) Math.cos(phase));
        paint.setShader(new RadialGradient(x1, y1, radius,
                new int[]{0x187C5CC4, 0x087C5CC4, 0x000B0D10},
                new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(b, paint);

        float x2 = w * (0.82f + 0.05f * (float) Math.cos(phase * 0.8f));
        float y2 = h * (0.72f + 0.06f * (float) Math.sin(phase * 0.8f));
        paint.setShader(new RadialGradient(x2, y2, radius * 0.8f,
                new int[]{0x1292A0B8, 0x0592A0B8, 0x000B0D10},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(b, paint);
        paint.setShader(null);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter); invalidateSelf();
    }
    @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
}