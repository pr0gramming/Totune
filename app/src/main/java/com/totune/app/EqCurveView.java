package com.totune.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

import com.totune.app.dsp.Biquad;

/** Draws the EQ's combined frequency response. */
public class EqCurveView extends View {
    private float[] params;
    private boolean on = true;
    private final Paint bg = new Paint(), grid = new Paint(), zero = new Paint();
    private final Paint curve = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final double FMIN = 20, FMAX = 20000, DB = 18;

    public EqCurveView(Context c) {
        super(c);
        bg.setColor(Ui.PANEL);
        grid.setColor(0xFF22262E);
        zero.setColor(0xFF353B47);
        curve.setStyle(Paint.Style.STROKE); curve.setStrokeWidth(Ui.dp(c, 2.4f)); curve.setColor(Ui.ACCENT);
        txt.setColor(Ui.DIM); txt.setTextSize(Ui.dp(c, 9.5f));
    }

    /** p = EQ module parameters (see ModuleType.EQ). */
    public void setParams(float[] p, boolean on) { params = p; this.on = on; invalidate(); }

    private static double response(float[] p, double f) {
        int sr = 48000;
        double db = 0;
        if (p[0] > 0.5f) db += Biquad.highPass(sr, p[1], 0.7071).responseDb(sr, f);
        if (Math.abs(p[2]) > 0.05f) db += Biquad.lowShelf(sr, p[3], p[2]).responseDb(sr, f);
        if (Math.abs(p[4]) > 0.05f) db += Biquad.peak(sr, p[5], Math.max(0.1f, p[6]), p[4]).responseDb(sr, f);
        if (Math.abs(p[7]) > 0.05f) db += Biquad.highShelf(sr, p[8], p[7]).responseDb(sr, f);
        if (p[9] > 0.5f) db += Biquad.lowPass(sr, p[10], 0.7071).responseDb(sr, f);
        return db;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, bg);
        int[] fs = {50, 100, 200, 500, 1000, 2000, 5000, 10000};
        for (int f : fs) {
            float x = xOf(f, w);
            c.drawLine(x, 0, x, h, grid);
            c.drawText(f >= 1000 ? (f / 1000) + "k" : String.valueOf(f), x + 3, h - 4, txt);
        }
        for (int db = -12; db <= 12; db += 6) {
            float y = yOf(db, h);
            c.drawLine(0, y, w, y, db == 0 ? zero : grid);
        }
        if (params == null) return;
        Path p = new Path(), area = new Path();
        int steps = 160;
        for (int i = 0; i <= steps; i++) {
            double f = FMIN * Math.pow(FMAX / FMIN, i / (double) steps);
            double db = on ? response(params, f) : 0;
            float x = xOf(f, w), y = yOf(Math.max(-DB * 1.5, Math.min(DB, db)), h);
            if (i == 0) { p.moveTo(x, y); area.moveTo(x, yOf(0, h)); area.lineTo(x, y); }
            else { p.lineTo(x, y); area.lineTo(x, y); }
        }
        area.lineTo(w, yOf(0, h));
        area.close();
        fill.setShader(new LinearGradient(0, 0, 0, h, 0x55FF6B3D, 0x05FF6B3D, Shader.TileMode.CLAMP));
        c.drawPath(area, fill);
        curve.setColor(on ? Ui.ACCENT : Ui.DIM);
        c.drawPath(p, curve);
    }

    private float xOf(double f, float w) {
        return (float) (Math.log(f / FMIN) / Math.log(FMAX / FMIN) * w);
    }

    private float yOf(double db, float h) {
        return (float) (h / 2 - db / DB * (h / 2 - 6));
    }
}
