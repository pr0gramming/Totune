package com.totune.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Scrolling live level display used while recording. */
public class WaveformView extends View {
    private final float[] peaks = new float[160];
    private int head = 0;
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), mid = new Paint();
    private boolean active;

    public WaveformView(Context c) {
        super(c);
        bar.setStrokeCap(Paint.Cap.ROUND);
        bar.setStrokeWidth(Ui.dp(c, 2.5f));
        mid.setColor(Ui.LINE);
    }

    public void setActive(boolean a) { active = a; invalidate(); }

    public void push(float peak) {
        peaks[head] = peak;
        head = (head + 1) % peaks.length;
        invalidate();
    }

    public void clear() {
        java.util.Arrays.fill(peaks, 0);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cy = h / 2;
        c.drawLine(0, cy, w, cy, mid);
        float dx = w / peaks.length;
        for (int i = 0; i < peaks.length; i++) {
            float p = peaks[(head + i) % peaks.length];
            // perceptual scaling so quiet singing is still visible
            float v = (float) Math.min(1, Math.pow(p, 0.5));
            float a = v * (h / 2 - 4);
            bar.setColor(p > 0.97f ? Ui.RED : (active ? Ui.ACCENT : Ui.DIM));
            float x = i * dx + dx / 2;
            c.drawLine(x, cy - a - 1, x, cy + a + 1, bar);
        }
    }
}
