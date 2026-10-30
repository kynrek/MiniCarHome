package com.kynrek.minicarhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Four rising bars, the first {@code level} filled; level -1 draws them all dimmed (no signal). */
public class SignalBars extends View {
    private static final int BARS = 4;
    private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG), off = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private int level = -1;

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

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float gap = w * 0.08f, barW = (w - gap * (BARS - 1)) / BARS, radius = barW * 0.25f;
        for (int i = 0; i < BARS; i++) {
            float barH = h * (i + 1) / BARS;
            float left = i * (barW + gap);
            r.set(left, h - barH, left + barW, h);
            c.drawRoundRect(r, radius, radius, i < level ? on : off);
        }
    }
}
