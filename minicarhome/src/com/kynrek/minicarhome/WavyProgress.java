package com.kynrek.minicarhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

/**
 * The media card's progress line: the played part as a gentle wave that drifts while playing (or a
 * straight line), the rest thin and straight. Same look as the Music app's player.
 */
final class WavyProgress extends View {
    private static final float WAVELENGTH_DP = 36f;
    private static final long PERIOD_MS = 3200;   // one wavelength of drift, about 11 dp a second

    private final Paint played = new Paint(Paint.ANTI_ALIAS_FLAG), rest = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;
    private float fraction;
    private boolean playing, wavy = true;
    private float stroke;

    WavyProgress(Context c, int playedColor, int restColor) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        for (Paint p : new Paint[]{played, rest}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        played.setColor(playedColor);
        rest.setColor(restColor);
        setStyle(true, 3);
    }

    void setStyle(boolean wavy, float thicknessDp) {
        this.wavy = wavy;
        stroke = thicknessDp * density;
        played.setStrokeWidth(stroke);
        rest.setStrokeWidth(Math.max(density, stroke * 0.6f));
        requestLayout();
        invalidate();
    }

    /** Height the line needs, wave included. */
    int wantedHeight() {
        return (int) Math.ceil(stroke + (wavy ? 2 * amplitude() : 0) + 2 * density);
    }

    private float amplitude() { return Math.max(1.5f * density, stroke * 0.6f); }

    void setProgress(float fraction, boolean playing) {
        this.fraction = Math.max(0f, Math.min(1f, fraction));
        this.playing = playing;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(getDefaultSize(0, w), resolveSize(wantedHeight(), h));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float pad = stroke;
        float left = pad, right = getWidth() - pad, cy = getHeight() / 2f;
        float x = left + (right - left) * fraction;
        if (x < right) canvas.drawLine(x, cy, right, cy, rest);
        if (x <= left) return;
        if (!wavy) {
            canvas.drawLine(left, cy, x, cy, played);
            return;
        }
        float wl = WAVELENGTH_DP * density, a = amplitude();
        float phase = playing ? (SystemClock.uptimeMillis() % PERIOD_MS) / (float) PERIOD_MS : 0f;
        path.rewind();
        float step = Math.max(1f, 1.5f * density);
        boolean first = true;
        for (float px = left; ; px += step) {
            if (px > x) px = x;
            float ease = Math.min(1f, (px - left) / (wl / 2));
            float y = cy + (float) (a * ease * Math.sin(2 * Math.PI * ((px - left) / wl - phase)));
            if (first) { path.moveTo(px, y); first = false; } else path.lineTo(px, y);
            if (px >= x) break;
        }
        canvas.drawPath(path, played);
        if (playing) postInvalidateOnAnimation();
    }
}
