package com.kynrek.minicarhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Four rising bars, the first {@code level} filled; level -1 draws them all dimmed (no signal).
 * With {@link #setFan} it draws the Wi-Fi fan instead: four rings of a quarter circle, filled from the point up.
 */
public class SignalBars extends View {
    private static final int BARS = 4;
    private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG), off = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path ring = new Path();
    private int level = -1;
    private boolean fan;

    public SignalBars(Context c, int onColor, int offColor) {
        super(c);
        on.setColor(onColor);
        off.setColor(offColor);
    }

    public void setLevel(int level) {
        if (level != this.level) {
            this.level = level;
            invalidate();
        }
    }

    public void setFan(boolean fan) {
        this.fan = fan;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (fan) {
            float radius = Math.min(h, w / 1.414f), cx = w / 2, gap = radius * 0.07f;
            for (int i = 0; i < BARS; i++) {
                float outer = radius * (i + 1) / BARS, inner = i == 0 ? 0 : radius * i / BARS + gap;
                ring.reset();
                r.set(cx - outer, h - outer, cx + outer, h + outer);
                ring.arcTo(r, -135, 90, true);
                if (inner > 0) {
                    r.set(cx - inner, h - inner, cx + inner, h + inner);
                    ring.arcTo(r, -45, -90, false);
                } else {
                    ring.lineTo(cx, h);
                }
                ring.close();
                c.drawPath(ring, i < level ? on : off);
            }
            return;
        }
        float gap = w * 0.08f, barW = (w - gap * (BARS - 1)) / BARS, radius = barW * 0.25f;
        for (int i = 0; i < BARS; i++) {
            float barH = h * (i + 1) / BARS;
            float left = i * (barW + gap);
            r.set(left, h - barH, left + barW, h);
            c.drawRoundRect(r, radius, radius, i < level ? on : off);
        }
    }
}
