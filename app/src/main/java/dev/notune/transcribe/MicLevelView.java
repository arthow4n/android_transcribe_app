package dev.notune.transcribe;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.google.android.material.color.MaterialColors;

/**
 * Animated sound wave arcs radiating symmetrically on both left and right sides of the record icon.
 * Reacts dynamically to mic level (0..1).
 */
public class MicLevelView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float current = 0f;   // 0..1
    private float target = 0f;    // 0..1
    private int baseColor = Color.WHITE;
    private ValueAnimator animator;
    private final RectF oval = new RectF();

    public MicLevelView(Context c) { super(c); init(); }
    public MicLevelView(Context c, AttributeSet a) { super(c, a); init(); }
    public MicLevelView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        baseColor = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorPrimary, Color.WHITE);
        paint.setColor(baseColor);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** Override the wave color (defaults to the theme's colorPrimary or colorOnPrimary). */
    public void setColor(int color) {
        baseColor = color;
        paint.setColor(color);
        invalidate();
    }

    /** level: 0..1 */
    public void setLevel(float level) {
        if (level < 0f) level = 0f;
        if (level > 1f) level = 1f;
        target = level;

        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(current, target);
        animator.setDuration(60); // fast, makes it feel "live"
        animator.addUpdateListener(a -> {
            current = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (getVisibility() != VISIBLE) {
            return;
        }

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        paint.setColor(baseColor);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(2.2f));

        float density = getResources().getDisplayMetrics().density;
        float r1 = 16f * density;
        float r2 = 22f * density;
        float r3 = 28f * density;

        // Arc 1 (inner): always visible while recording, brightens with volume
        int alpha1 = Math.min(255, 70 + (int)(185 * Math.min(1f, current * 2.5f)));
        paint.setAlpha(alpha1);
        drawSymmetricArcs(canvas, cx, cy, r1, 40f);

        // Arc 2 (middle): lights up at low-medium volume
        if (current > 0.12f) {
            float f2 = Math.min(1f, (current - 0.12f) / 0.45f);
            int alpha2 = (int)(255 * f2);
            paint.setAlpha(alpha2);
            drawSymmetricArcs(canvas, cx, cy, r2, 35f);
        }

        // Arc 3 (outer): lights up at higher volume
        if (current > 0.40f) {
            float f3 = Math.min(1f, (current - 0.40f) / 0.55f);
            int alpha3 = (int)(255 * f3);
            paint.setAlpha(alpha3);
            drawSymmetricArcs(canvas, cx, cy, r3, 30f);
        }
    }

    private void drawSymmetricArcs(Canvas canvas, float cx, float cy, float r, float sweep) {
        oval.set(cx - r, cy - r, cx + r, cy + r);
        // Left arc
        canvas.drawArc(oval, 180f - sweep, sweep * 2f, false, paint);
        // Right arc
        canvas.drawArc(oval, -sweep, sweep * 2f, false, paint);
    }
}
