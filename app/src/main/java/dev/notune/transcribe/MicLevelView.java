package dev.notune.transcribe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import com.google.android.material.color.MaterialColors;

/**
 * Animated sound wave arcs radiating symmetrically on both left and right sides of the record icon.
 * Spans dynamically across the available button width with a perceptually smoothed
 * logarithmic envelope follower (fast attack, smooth natural decay).
 */
public class MicLevelView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float currentLevel = 0f;   // 0..1 smoothed
    private float targetLevel = 0f;    // 0..1 perceptual target
    private int baseColor = Color.WHITE;
    private final RectF oval = new RectF();

    private boolean isAnimating = false;
    private long lastFrameTime = 0L;

    private final Runnable animTicker = new Runnable() {
        @Override
        public void run() {
            if (getVisibility() != VISIBLE) {
                isAnimating = false;
                return;
            }

            long now = SystemClock.uptimeMillis();
            float dt = (lastFrameTime > 0) ? (now - lastFrameTime) / 1000f : 0.016f;
            lastFrameTime = now;
            if (dt > 0.1f) dt = 0.1f; // clamp if dropped frames

            float diff = targetLevel - currentLevel;
            if (diff > 0) {
                // Fast attack: reacts promptly to voice onset (~35ms time constant)
                float alpha = 1.0f - (float) Math.exp(-dt / 0.035f);
                currentLevel += diff * alpha;
            } else {
                // Smooth decay: bridges inter-syllable pauses naturally (~280ms time constant)
                float alpha = 1.0f - (float) Math.exp(-dt / 0.280f);
                currentLevel += diff * alpha;
            }

            invalidate();

            if (targetLevel > 0.005f || currentLevel > 0.005f) {
                postOnAnimation(this);
            } else {
                currentLevel = 0f;
                isAnimating = false;
                invalidate();
            }
        }
    };

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

    /**
     * Map raw audio level (0..1) to logarithmic/perceptual scale.
     * Accurately reflects how human hearing perceives loudness and distinguishes nuances.
     */
    private float mapToPerceptual(float raw) {
        if (raw <= 0.002f) return 0f;
        float logMin = (float) Math.log10(0.015);
        float logMax = (float) Math.log10(1.015);
        float logVal = (float) Math.log10(Math.min(1.0f, raw) + 0.015);
        float norm = (logVal - logMin) / (logMax - logMin);
        return Math.max(0f, Math.min(1f, norm));
    }

    /** Set new audio level from microphone input (0..1). */
    public void setLevel(float level) {
        if (level < 0f) level = 0f;
        if (level > 1f) level = 1f;

        targetLevel = mapToPerceptual(level);

        if (getVisibility() == VISIBLE) {
            startAnimation();
        }
    }

    private void startAnimation() {
        if (!isAnimating) {
            isAnimating = true;
            lastFrameTime = SystemClock.uptimeMillis();
            postOnAnimation(animTicker);
        }
    }

    private void stopAnimation() {
        isAnimating = false;
        removeCallbacks(animTicker);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE && (targetLevel > 0 || currentLevel > 0)) {
            startAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimation();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) {
            stopAnimation();
            currentLevel = 0f;
            targetLevel = 0f;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (getVisibility() != VISIBLE) {
            return;
        }

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        if (cx <= 0 || cy <= 0) return;

        paint.setColor(baseColor);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(2.0f));

        // Dynamically compute the number of concentric arcs that fit in the button width
        float maxRadius = Math.max(dp(24f), cx - dp(8f));
        float baseR = dp(16f); // Starts right outside the 24dp mic icon
        float arcSpacing = dp(8.5f);
        int numArcs = Math.max(3, Math.min(8, (int) ((maxRadius - baseR) / arcSpacing) + 1));

        // Reach indicator based on smooth envelope
        float reach = currentLevel * (numArcs - 0.2f);

        for (int i = 0; i < numArcs; i++) {
            float r = baseR + i * arcSpacing;
            if (r > maxRadius) break;

            float weight;
            if (i == 0) {
                // Innermost arc: always softly visible while recording, brightens with speech
                weight = Math.max(0.30f, currentLevel);
            } else {
                weight = reach - i;
                if (weight <= 0f) continue;
                if (weight > 1f) weight = 1f;
            }

            // Alpha falls off gently for outer arcs for a soft, natural acoustic dissipation
            int alpha = (int) (240 * weight * (1f - i * 0.06f));
            alpha = Math.max(0, Math.min(255, alpha));
            paint.setAlpha(alpha);

            // Sweep angle smoothly widens with volume
            float sweep = (22f + 18f * weight) * (1f - i * 0.035f);
            drawSymmetricArcs(canvas, cx, cy, r, sweep);
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
