package com.totune.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Rotary knob: drag up/down to change, double-tap to reset. */
public class Knob extends View {
    public interface Format { String format(float v); }
    public interface Listener { void onChange(float v); }

    private final String label;
    private final float min, max, def;
    private final boolean log;
    private final float step;
    private float value;
    private Format format = v -> String.format(java.util.Locale.US, "%.1f", v);
    private Listener listener;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private float lastY, downX, downY;
    private long lastTap;
    private float dragNorm;

    /**
     * @param log  logarithmic travel (frequencies)
     * @param step snap to multiples of this (0 = continuous)
     */
    public Knob(Context c, String label, float min, float max, float def, float value, boolean log, float step) {
        super(c);
        this.label = label;
        this.min = min; this.max = max; this.def = def; this.log = log; this.step = step;
        this.value = clamp(value);
        float sw = Ui.dp(c, 4.5f);
        track.setStyle(Paint.Style.STROKE); track.setStrokeWidth(sw); track.setColor(Ui.LINE);
        track.setStrokeCap(Paint.Cap.ROUND);
        arc.setStyle(Paint.Style.STROKE); arc.setStrokeWidth(sw); arc.setColor(Ui.ACCENT);
        arc.setStrokeCap(Paint.Cap.ROUND);
        body.setColor(Ui.PANEL2);
        dot.setColor(Ui.TEXT);
        valuePaint.setColor(Ui.TEXT); valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setTextSize(Ui.dp(c, 12.5f));
        valuePaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        labelPaint.setColor(Ui.DIM); labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(Ui.dp(c, 11.5f));
    }

    public Knob format(Format f) { format = f; invalidate(); return this; }
    public Knob listener(Listener l) { listener = l; return this; }
    public float getValue() { return value; }

    public void setValue(float v) { value = clamp(v); invalidate(); }

    private float clamp(float v) {
        v = Math.max(min, Math.min(max, v));
        if (step > 0) v = Math.round(v / step) * step;
        return v;
    }

    private float toNorm(float v) {
        if (log) return (float) (Math.log(v / min) / Math.log(max / min));
        return (v - min) / (max - min);
    }

    private float fromNorm(float n) {
        n = Math.max(0, Math.min(1, n));
        if (log) return (float) (min * Math.pow(max / min, n));
        return min + n * (max - min);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = resolveSize(Ui.dp(getContext(), 78), wSpec);
        int h = Ui.dp(getContext(), 100);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth();
        float size = Math.min(w, Ui.dp(getContext(), 64));
        float cx = w / 2, cy = Ui.dp(getContext(), 8) + size / 2;
        float r = size / 2 - Ui.dp(getContext(), 4);
        oval.set(cx - r, cy - r, cx + r, cy + r);
        cv.drawArc(oval, 135, 270, false, track);
        float n = toNorm(value);
        // bipolar knobs (min < 0 < max) draw from the centre
        if (min < 0 && max > 0 && !log) {
            float z = toNorm(0);
            float a0 = 135 + 270 * z, a1 = 135 + 270 * n;
            cv.drawArc(oval, Math.min(a0, a1), Math.abs(a1 - a0), false, arc);
        } else {
            cv.drawArc(oval, 135, 270 * n, false, arc);
        }
        cv.drawCircle(cx, cy, r - Ui.dp(getContext(), 7), body);
        double ang = Math.toRadians(135 + 270 * n);
        float dr = r - Ui.dp(getContext(), 14);
        cv.drawCircle(cx + (float) Math.cos(ang) * dr, cy + (float) Math.sin(ang) * dr, Ui.dp(getContext(), 3), dot);
        float ty = cy + r + Ui.dp(getContext(), 14);
        cv.drawText(format.format(value), cx, ty, valuePaint);
        cv.drawText(label, cx, ty + Ui.dp(getContext(), 15), labelPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                lastY = downY = e.getY();
                downX = e.getX();
                dragNorm = toNorm(value);
                arc.setColor(0xFFFF8A63);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = lastY - e.getY();
                lastY = e.getY();
                dragNorm = Math.max(0, Math.min(1, dragNorm + dy / Ui.dp(getContext(), 220)));
                float nv = clamp(fromNorm(dragNorm));
                if (nv != value) {
                    value = nv;
                    invalidate();
                    if (listener != null) listener.onChange(value);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                arc.setColor(Ui.ACCENT);
                invalidate();
                if (e.getActionMasked() == MotionEvent.ACTION_UP
                        && Math.abs(e.getY() - downY) < Ui.dp(getContext(), 6)
                        && Math.abs(e.getX() - downX) < Ui.dp(getContext(), 6)) {
                    long now = System.currentTimeMillis();
                    if (now - lastTap < 320) {
                        value = clamp(def);
                        invalidate();
                        if (listener != null) listener.onChange(value);
                        lastTap = 0;
                    } else {
                        lastTap = now;
                    }
                }
                return true;
        }
        return super.onTouchEvent(e);
    }
}
