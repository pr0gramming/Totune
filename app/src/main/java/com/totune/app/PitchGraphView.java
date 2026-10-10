package com.totune.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import com.totune.app.dsp.Scales;

/** Piano-roll style graph: sung pitch (grey) vs tuned pitch (teal), with the scale's notes highlighted. */
public class PitchGraphView extends View {
    public interface SeekListener { void onSeek(double sec); }

    private float[] det, out;
    private boolean[] mask = Scales.mask(0, 0);
    private double fps = 1, duration = 1, playhead = -1;
    private SeekListener seekListener;

    private final Paint bandIn = new Paint(), bandRoot = new Paint(), grid = new Paint();
    private final Paint detPaint = new Paint(Paint.ANTI_ALIAS_FLAG), outPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint();
    private int key = 0;
    private String emptyText = "Analyzing…";

    public PitchGraphView(Context c) {
        super(c);
        bg.setColor(Ui.PANEL);
        bandIn.setColor(0xFF1C2028);
        bandRoot.setColor(0xFF272B35);
        grid.setColor(0xFF20242C);
        detPaint.setStyle(Paint.Style.STROKE); detPaint.setStrokeWidth(Ui.dp(c, 1.6f));
        detPaint.setColor(0xFF6B7286); detPaint.setStrokeJoin(Paint.Join.ROUND);
        outPaint.setStyle(Paint.Style.STROKE); outPaint.setStrokeWidth(Ui.dp(c, 2.4f));
        outPaint.setColor(Ui.TEAL); outPaint.setStrokeJoin(Paint.Join.ROUND);
        head.setColor(Ui.ACCENT); head.setStrokeWidth(Ui.dp(c, 2));
        txt.setColor(Ui.DIM); txt.setTextSize(Ui.dp(c, 10));
    }

    public void setSeekListener(SeekListener l) { seekListener = l; }
    public void setEmptyText(String s) { emptyText = s; invalidate(); }

    public void setData(float[] det, float[] out, double fps, double durationSec, int key, int scale) {
        this.det = det; this.out = out; this.fps = fps; this.duration = Math.max(0.1, durationSec);
        this.key = key; this.mask = Scales.mask(key, scale);
        invalidate();
    }

    public void setPlayhead(double sec) { playhead = sec; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, bg);
        if (det == null || det.length == 0) {
            txt.setTextAlign(Paint.Align.CENTER);
            c.drawText(emptyText, w / 2, h / 2, txt);
            txt.setTextAlign(Paint.Align.LEFT);
            return;
        }
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < det.length; i++) {
            if (!Float.isNaN(det[i])) { lo = Math.min(lo, det[i]); hi = Math.max(hi, det[i]); }
            if (out != null && i < out.length && !Float.isNaN(out[i])) { lo = Math.min(lo, out[i]); hi = Math.max(hi, out[i]); }
        }
        if (lo > hi) {
            txt.setTextAlign(Paint.Align.CENTER);
            c.drawText("No singing detected yet", w / 2, h / 2, txt);
            txt.setTextAlign(Paint.Align.LEFT);
            return;
        }
        int nLo = (int) Math.floor(lo) - 2, nHi = (int) Math.ceil(hi) + 2;
        if (nHi - nLo < 12) { int mid = (nLo + nHi) / 2; nLo = mid - 6; nHi = mid + 6; }
        float rowH = h / (float) (nHi - nLo + 1);
        float left = Ui.dp(getContext(), 30);
        // note rows
        for (int n = nLo; n <= nHi; n++) {
            float y = yOf(n, nLo, nHi, h) - rowH / 2;
            int pc = Scales.pc(n);
            if (pc == key) c.drawRect(left, y, w, y + rowH, bandRoot);
            else if (mask[pc]) c.drawRect(left, y, w, y + rowH, bandIn);
            c.drawLine(left, y, w, y, grid);
            if (mask[pc] && rowH >= Ui.dp(getContext(), 7)) {
                c.drawText(Scales.noteName(n), Ui.dp(getContext(), 3), y + rowH / 2 + Ui.dp(getContext(), 3.5f), txt);
            }
        }
        drawCurve(c, det, detPaint, nLo, nHi, left, w, h);
        if (out != null) drawCurve(c, out, outPaint, nLo, nHi, left, w, h);
        if (playhead >= 0) {
            float x = left + (float) (playhead / duration) * (w - left);
            c.drawLine(x, 0, x, h, head);
        }
    }

    private float yOf(double midi, int nLo, int nHi, float h) {
        float rowH = h / (float) (nHi - nLo + 1);
        return (float) (h - (midi - nLo + 0.5) * rowH);
    }

    private void drawCurve(Canvas c, float[] m, Paint p, int nLo, int nHi, float left, float w, float h) {
        Path path = new Path();
        boolean pen = false;
        int stride = Math.max(1, (int) (m.length / (w - left) / 2));
        for (int i = 0; i < m.length; i += stride) {
            if (Float.isNaN(m[i])) { pen = false; continue; }
            float x = left + (float) ((i / fps) / duration) * (w - left);
            float y = yOf(m[i], nLo, nHi, h);
            if (!pen) { path.moveTo(x, y); pen = true; } else path.lineTo(x, y);
        }
        c.drawPath(path, p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (seekListener == null) return false;
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE || a == MotionEvent.ACTION_UP) {
            getParent().requestDisallowInterceptTouchEvent(true);
            float left = Ui.dp(getContext(), 30);
            double sec = Math.max(0, Math.min(1, (e.getX() - left) / (getWidth() - left))) * duration;
            playhead = sec;
            invalidate();
            if (a == MotionEvent.ACTION_UP) seekListener.onSeek(sec);
            return true;
        }
        return super.onTouchEvent(e);
    }
}
