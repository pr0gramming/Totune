package com.totune.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.totune.app.dsp.KeyDetect;
import com.totune.app.dsp.Params;
import com.totune.app.dsp.Scales;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Dialog sheets shared by the record and editor screens. */
public final class Sheets {
    private Sheets() {}

    public interface ModuleListener {
        void onParam(Rack.Module m, int index, float value);
        void onOn(Rack.Module m, boolean on);
        void onRemove(Rack.Module m);
    }

    private static final String[] HINTS = {
            "Tuny: modelled on Antares Auto-Tune Pro — the most locked, glassy hard tune. "
                    + "Crisp: modelled on Crispy Tuner. Wavy: modelled on Waves Tune, slightly looser and softer on top. "
                    + "Retune speed 0 = robot.\n"
                    + "Voice range: pick your voice type; higher ranges also lower the live-monitor delay.",
            "Harmonies are built from your voice and follow the key & scale of the song.",
            "Chords: your voice plays a chord from the key. Melody robot: a synth following your tuned melody. "
                    + "Monotone: one flat robot note. Deep robot: an octave down.",
            "Two slightly detuned, slightly late copies left and right for a thick, wide vocal.",
            "Low cut around 80–120 Hz removes rumble. A little Air adds sparkle; cut 250–500 Hz to remove mud.",
            "One knob does it all: more Squash = more even and upfront.",
            "A little adds energy; a lot gives a dirty, distorted vocal.",
            "Wobbly, wide shimmer — a staple of spacey melodic-trap vocals.",
            "Delay time follows the song tempo (set it from the song bar).",
            "Size and Decay shape the space. Low cut keeps the reverb from getting muddy.",
    };

    private static ScrollView wrap(Activity a, LinearLayout col) {
        ScrollView sv = new ScrollView(a);
        sv.addView(col);
        return sv;
    }

    private static LinearLayout sheetColumn(Activity a) {
        LinearLayout col = Ui.column(a);
        int pd = Ui.dp(a, 18);
        col.setPadding(pd, Ui.dp(a, 4), pd, pd);
        return col;
    }

    private static LinearLayout knobRow(Activity a, List<View> knobs) {
        LinearLayout r = Ui.row(a);
        for (View k : knobs) r.addView(k, Ui.weight(1));
        for (int i = knobs.size(); i < 4; i++) r.addView(new View(a), Ui.weight(1));
        return r;
    }

    // ------------------------------------------------------------------ module config

    public static AlertDialog module(Activity a, Rack.Module m, ModuleListener l) {
        ModuleType t = m.def();
        LinearLayout col = sheetColumn(a);
        final AlertDialog[] dlg = new AlertDialog[1];

        LinearLayout top = Ui.row(a);
        top.addView(Ui.toggle(a, "On", m.on, v -> l.onOn(m, v)));
        top.addView(new View(a), Ui.weight(1));
        TextView remove = Ui.button(a, "Remove", false);
        remove.setPadding(Ui.dp(a, 14), Ui.dp(a, 8), Ui.dp(a, 14), Ui.dp(a, 8));
        remove.setOnClickListener(v -> { dlg[0].dismiss(); l.onRemove(m); });
        top.addView(remove);
        col.addView(top);

        EqCurveView curve = null;
        if (t.id == ModuleType.EQ) {
            curve = new EqCurveView(a);
            curve.setParams(m.p, true);
            curve.setBackground(Ui.round(Ui.PANEL, Ui.dp(a, 14), 0, 0));
            curve.setClipToOutline(true);
            col.addView(curve, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 120)), 0, Ui.dp(a, 10), 0, 0));
        }
        final EqCurveView fc = curve;

        List<View> pending = new ArrayList<>();
        for (int i = 0; i < t.params.length; i++) {
            ModuleType.Param p = t.params[i];
            final int idx = i;
            if (p.kind == ModuleType.Kind.KNOB) {
                Knob k = new Knob(a, p.name, p.min, p.max, p.def, m.p[i], p.log, p.step).format(p::format);
                k.listener(v -> { l.onParam(m, idx, v); if (fc != null) fc.invalidate(); });
                pending.add(k);
                if (pending.size() == 4) { col.addView(knobRow(a, pending)); pending = new ArrayList<>(); }
                continue;
            }
            if (!pending.isEmpty()) { col.addView(knobRow(a, pending)); pending = new ArrayList<>(); }
            if (p.kind == ModuleType.Kind.CHOICE) {
                col.addView(Ui.label(a, p.name));
                col.addView(Ui.chips(a, p.choices, Math.round(m.p[i]), 0, c -> l.onParam(m, idx, c)));
            } else {
                LinearLayout r = Ui.row(a);
                r.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 4));
                r.addView(Ui.toggle(a, p.name, m.p[i] > 0.5f, v -> { l.onParam(m, idx, v ? 1 : 0); if (fc != null) fc.invalidate(); }));
                col.addView(r);
            }
        }
        if (!pending.isEmpty()) col.addView(knobRow(a, pending));
        col.addView(Ui.hint(a, HINTS[t.id]));

        dlg[0] = Ui.dialog(a).setTitle(t.name).setView(wrap(a, col)).setPositiveButton("Done", null).create();
        dlg[0].show();
        return dlg[0];
    }

    // ------------------------------------------------------------------ song settings (key, scale, reference, tempo)

    public static String songSummary(Params p) {
        return Scales.NOTE_NAMES[p.key] + " " + Scales.NAMES[p.scale] + "  ·  A" + Math.round(p.refHz)
                + "  ·  " + Math.round(p.bpm) + " BPM";
    }

    /** sung may be null (e.g. before recording). */
    public static void song(Activity a, Params p, float[] sung, Runnable changed) {
        LinearLayout col = sheetColumn(a);
        col.addView(Ui.label(a, "Key"));
        final View[] keyChips = {Ui.chips(a, Scales.NOTE_NAMES, p.key, 6, i -> { p.key = i; changed.run(); })};
        LinearLayout keyHolder = Ui.column(a);
        keyHolder.addView(keyChips[0]);
        col.addView(keyHolder);
        col.addView(Ui.label(a, "Scale"));
        LinearLayout scaleHolder = Ui.column(a);
        scaleHolder.addView(Ui.chips(a, Scales.NAMES, p.scale, 0, i -> { p.scale = i; changed.run(); }));
        col.addView(scaleHolder);
        if (sung != null) {
            TextView detect = Ui.button(a, "Detect key from my voice", false);
            detect.setOnClickListener(v -> {
                int[] k = KeyDetect.detect(sung);
                if (k == null) { Toast.makeText(a, "Not enough singing to guess the key", Toast.LENGTH_LONG).show(); return; }
                p.key = k[0]; p.scale = k[1];
                keyHolder.removeAllViews();
                keyHolder.addView(Ui.chips(a, Scales.NOTE_NAMES, p.key, 6, i -> { p.key = i; changed.run(); }));
                scaleHolder.removeAllViews();
                scaleHolder.addView(Ui.chips(a, Scales.NAMES, p.scale, 0, i -> { p.scale = i; changed.run(); }));
                changed.run();
                Toast.makeText(a, "Detected: " + Scales.NOTE_NAMES[k[0]] + " " + Scales.NAMES[k[1]], Toast.LENGTH_LONG).show();
            });
            col.addView(detect, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    0, Ui.dp(a, 12), 0, 0));
        }
        col.addView(Ui.label(a, "Tuning & tempo"));
        Knob ref = new Knob(a, "A4 reference", 415, 466, 440, p.refHz, false, 1)
                .format(v -> String.format(Locale.US, "%.0f Hz", v));
        ref.listener(v -> { p.refHz = v; changed.run(); });
        Knob bpm = new Knob(a, "Tempo", 60, 200, 90, p.bpm, false, 1)
                .format(v -> String.format(Locale.US, "%.0f BPM", v));
        bpm.listener(v -> { p.bpm = v; changed.run(); });
        TextView tap = Ui.button(a, "Tap tempo", false);
        final List<Long> taps = new ArrayList<>();
        tap.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (!taps.isEmpty() && now - taps.get(taps.size() - 1) > 2000) taps.clear();
            taps.add(now);
            if (taps.size() > 5) taps.remove(0);
            if (taps.size() >= 2) {
                double avg = (taps.get(taps.size() - 1) - taps.get(0)) / (double) (taps.size() - 1);
                p.bpm = Math.round(Math.max(60, Math.min(200, 60000.0 / avg)));
                bpm.setValue(p.bpm);
                changed.run();
            }
        });
        LinearLayout r = Ui.row(a);
        r.addView(ref, Ui.weight(1));
        r.addView(bpm, Ui.weight(1));
        LinearLayout tw = Ui.column(a);
        tw.setGravity(Gravity.CENTER);
        tw.addView(tap);
        r.addView(tw, Ui.weight(2));
        col.addView(r);
        col.addView(Ui.hint(a, "Key and scale drive the autotune, harmonies and vocoder. Tempo sets the delay timing. "
                + "440 Hz is standard tuning; use 432 or 442 if your beat is tuned that way."));
        Ui.dialog(a).setTitle("Song").setView(wrap(a, col)).setPositiveButton("Done", null).show();
    }

    // ------------------------------------------------------------------ add module

    public interface TypePicked { void picked(int type); }

    public static void addModule(Activity a, TypePicked cb) {
        LinearLayout col = sheetColumn(a);
        final AlertDialog[] dlg = new AlertDialog[1];
        for (ModuleType t : ModuleType.ALL) {
            LinearLayout row = Ui.column(a);
            int ph = Ui.dp(a, 14), pv = Ui.dp(a, 10);
            row.setPadding(ph, pv, ph, pv);
            row.setBackground(Ui.ripple(Ui.round(Ui.PANEL2, Ui.dp(a, 12), Ui.LINE, Ui.dp(a, 1))));
            row.addView(Ui.text(a, t.name, 15, Ui.TEXT, true));
            row.addView(Ui.text(a, t.blurb, 12, Ui.DIM, false));
            row.setOnClickListener(v -> { dlg[0].dismiss(); cb.picked(t.id); });
            col.addView(row, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    0, 0, 0, Ui.dp(a, 6)));
        }
        dlg[0] = Ui.dialog(a).setTitle("Add processing").setView(wrap(a, col)).setNegativeButton("Cancel", null).create();
        dlg[0].show();
    }
}
