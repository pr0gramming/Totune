package com.totune.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntConsumer;

/** Colours and small view factories for the dark studio look (no XML layouts). */
public final class Ui {
    private Ui() {}

    public static final int BG = 0xFF0D0E12;
    public static final int PANEL = 0xFF16191F;
    public static final int PANEL2 = 0xFF1F232B;
    public static final int LINE = 0xFF2B303A;
    public static final int TEXT = 0xFFECEEF3;
    public static final int DIM = 0xFF8C93A6;
    public static final int ACCENT = 0xFFFF6B3D;
    public static final int TEAL = 0xFF3DD6C3;
    public static final int RED = 0xFFFF4458;

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static void styleWindow(Activity a) {
        a.getWindow().setStatusBarColor(BG);
        a.getWindow().setNavigationBarColor(BG);
        a.getWindow().getDecorView().setBackgroundColor(BG);
    }

    public static AlertDialog.Builder dialog(Context c) {
        return new AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert);
    }

    public static GradientDrawable round(int color, float radiusPx, int stroke, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0) g.setStroke(strokePx, stroke);
        return g;
    }

    public static Drawable ripple(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null);
    }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    public static TextView label(Context c, String s) {
        TextView t = text(c, s.toUpperCase(), 11, DIM, true);
        t.setLetterSpacing(0.12f);
        t.setPadding(0, dp(c, 16), 0, dp(c, 8));
        return t;
    }

    public static TextView hint(Context c, String s) {
        TextView t = text(c, s, 12.5f, DIM, false);
        t.setPadding(0, dp(c, 6), 0, dp(c, 4));
        return t;
    }

    /** Pill button. primary = filled accent, otherwise outlined. */
    public static TextView button(Context c, String label, boolean primary) {
        TextView b = text(c, label, 14.5f, primary ? 0xFF15100D : TEXT, true);
        b.setGravity(Gravity.CENTER);
        int ph = dp(c, 18), pv = dp(c, 12);
        b.setPadding(ph, pv, ph, pv);
        float r = dp(c, 24);
        b.setBackground(ripple(primary ? round(ACCENT, r, 0, 0) : round(PANEL2, r, LINE, dp(c, 1))));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    public static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams p, int l, int t, int r, int b) {
        p.setMargins(l, t, r, b);
        return p;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        int p = dp(c, 14);
        l.setPadding(p, p / 2, p, p);
        l.setBackground(round(PANEL, dp(c, 16), LINE, dp(c, 1)));
        return l;
    }

    /** On/off pill with a label. */
    public static TextView toggle(Context c, String label, boolean on, java.util.function.Consumer<Boolean> cb) {
        TextView t = text(c, "", 13.5f, TEXT, true);
        t.setGravity(Gravity.CENTER);
        int ph = dp(c, 14), pv = dp(c, 8);
        t.setPadding(ph, pv, ph, pv);
        final boolean[] state = {on};
        Runnable paint = () -> {
            t.setText((state[0] ? "● " : "○ ") + label);
            t.setTextColor(state[0] ? 0xFF15100D : DIM);
            t.setBackground(ripple(state[0] ? round(TEAL, dp(c, 18), 0, 0) : round(PANEL2, dp(c, 18), LINE, dp(c, 1))));
        };
        paint.run();
        t.setOnClickListener(v -> {
            state[0] = !state[0];
            paint.run();
            cb.accept(state[0]);
        });
        return t;
    }

    /**
     * Segmented chooser. columns > 0 lays the chips out in a grid,
     * otherwise in a horizontally scrolling row.
     */
    public static View chips(Context c, String[] items, int selected, int columns, IntConsumer onSelect) {
        TextView[] views = new TextView[items.length];
        final int[] sel = {selected};
        Runnable paint = () -> {
            for (int i = 0; i < views.length; i++) {
                boolean on = i == sel[0];
                views[i].setTextColor(on ? 0xFF15100D : TEXT);
                views[i].setBackground(ripple(on ? round(ACCENT, dp(c, 12), 0, 0) : round(PANEL2, dp(c, 12), LINE, dp(c, 1))));
            }
        };
        for (int i = 0; i < items.length; i++) {
            TextView t = text(c, items[i], 13.5f, TEXT, true);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            int ph = dp(c, 12), pv = dp(c, 9);
            t.setPadding(ph, pv, ph, pv);
            final int idx = i;
            t.setOnClickListener(v -> {
                sel[0] = idx;
                paint.run();
                onSelect.accept(idx);
            });
            views[i] = t;
        }
        paint.run();
        int gap = dp(c, 3);
        if (columns > 0) {
            GridLayout g = new GridLayout(c);
            g.setColumnCount(columns);
            for (TextView t : views) {
                GridLayout.LayoutParams p = new GridLayout.LayoutParams(
                        GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
                p.width = 0;
                p.setMargins(gap, gap, gap, gap);
                g.addView(t, p);
            }
            return g;
        }
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout r = row(c);
        for (TextView t : views) {
            LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.setMargins(gap, gap, gap, gap);
            r.addView(t, p);
        }
        hs.addView(r);
        return hs;
    }

    public static String time(double sec) {
        int s = (int) Math.max(0, Math.floor(sec));
        return (s / 60) + ":" + String.format(java.util.Locale.US, "%02d", s % 60);
    }
}
