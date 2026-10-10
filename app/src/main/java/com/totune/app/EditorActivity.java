package com.totune.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.totune.app.dsp.Analysis;
import com.totune.app.dsp.Engine;
import com.totune.app.dsp.KeyDetect;
import com.totune.app.dsp.Params;
import com.totune.app.dsp.Scales;
import com.totune.app.dsp.WavIO;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The studio: pitch graph, transport and the TUNE / EQ / COMP / FX / VOICES / MIX tabs. */
@SuppressWarnings("deprecation")
public class EditorActivity extends Activity {
    private static final int SR = AudioCodec.SR;
    private static final int REQ_BEAT = 20;
    private static final String[] TABS = {"TUNE", "EQ", "COMP", "FX", "VOICES", "MIX"};

    private TakeStore.Take take;
    private Params p;
    private float[] vocal;
    private float[][] beat;
    private Analysis analysis;
    private Engine.Result result;
    private short[] mixPcm;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int renderGen = 0;
    private Player player;

    private PitchGraphView graph;
    private TextView playBtn, timeText, statusText, titleText;
    private ProgressBar progress;
    private LinearLayout tabContent;
    private ScrollView tabScroll;
    private final List<TextView> tabViews = new ArrayList<>();
    private int currentTab = 0;
    private EqCurveView eqCurve;
    private final List<Long> taps = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.styleWindow(this);
        take = TakeStore.get(this, getIntent().getStringExtra("take"));
        if (take == null) {
            Toast.makeText(this, "Take not found", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        p = take.params;
        player = new Player(SR);
        player.setListener(() -> ui.post(this::updateTransport));
        buildUi();
        load();
    }

    // ------------------------------------------------------------------ layout

    private void buildUi() {
        int pad = Ui.dp(this, 14);
        LinearLayout root = Ui.column(this);
        root.setPadding(pad, pad, pad, Ui.dp(this, 10));

        // top bar
        LinearLayout top = Ui.row(this);
        TextView back = Ui.text(this, "‹", 30, Ui.TEXT, false);
        back.setPadding(Ui.dp(this, 2), 0, Ui.dp(this, 14), Ui.dp(this, 4));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        titleText = Ui.text(this, take.name, 18, Ui.TEXT, true);
        titleText.setSingleLine(true);
        titleText.setOnClickListener(v -> rename());
        top.addView(titleText, Ui.weight(1));
        TextView presets = Ui.button(this, "Presets", false);
        presets.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        presets.setOnClickListener(v -> presetsDialog());
        top.addView(presets);
        root.addView(top);

        // pitch graph
        graph = new PitchGraphView(this);
        graph.setSeekListener(sec -> player.seek(Math.max(0, sec - shiftSec())));
        graph.setBackground(Ui.round(Ui.PANEL, Ui.dp(this, 14), 0, 0));
        graph.setClipToOutline(true);
        root.addView(graph, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 170)),
                0, Ui.dp(this, 10), 0, Ui.dp(this, 4)));

        LinearLayout legend = Ui.row(this);
        TextView l1 = Ui.text(this, "━ sung", 11, 0xFF8088A0, false);
        TextView l2 = Ui.text(this, "   ━ tuned", 11, Ui.TEAL, false);
        TextView l3 = Ui.text(this, "      tap graph to seek", 11, Ui.DIM, false);
        legend.addView(l1); legend.addView(l2); legend.addView(l3);
        root.addView(legend);

        // transport
        LinearLayout tr = Ui.row(this);
        tr.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        playBtn = Ui.text(this, "▶", 20, 0xFF15100D, true);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setBackground(Ui.ripple(Ui.round(Ui.ACCENT, Ui.dp(this, 26), 0, 0)));
        playBtn.setOnClickListener(v -> togglePlay());
        tr.addView(playBtn, Ui.lp(Ui.dp(this, 52), Ui.dp(this, 52)));
        LinearLayout tcol = Ui.column(this);
        tcol.setPadding(Ui.dp(this, 12), 0, 0, 0);
        timeText = Ui.text(this, "0:00", 15, Ui.TEXT, true);
        statusText = Ui.text(this, "Loading…", 12, Ui.DIM, false);
        tcol.addView(timeText);
        tcol.addView(statusText);
        tr.addView(tcol, Ui.weight(1));
        View ab = Ui.chips(this, new String[]{"Tuned", "Original"}, 0, 0, i -> player.setUseAlt(i == 1));
        tr.addView(ab);
        root.addView(tr);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        root.addView(progress, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 4)));

        // tabs
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = Ui.row(this);
        tabs.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 2));
        for (int i = 0; i < TABS.length; i++) {
            TextView t = Ui.text(this, TABS[i], 13, Ui.DIM, true);
            t.setLetterSpacing(0.1f);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 14), Ui.dp(this, 9));
            final int idx = i;
            t.setOnClickListener(v -> showTab(idx));
            tabViews.add(t);
            tabs.addView(t, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    0, 0, Ui.dp(this, 6), 0));
        }
        hs.addView(tabs);
        root.addView(hs);

        tabScroll = new ScrollView(this);
        tabContent = Ui.column(this);
        tabContent.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 12));
        tabScroll.addView(tabContent);
        root.addView(tabScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView export = Ui.button(this, "Export / Share", true);
        export.setOnClickListener(v -> exportDialog());
        root.addView(export, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                0, Ui.dp(this, 6), 0, 0));

        setContentView(root);
        showTab(0);
    }

    private void showTab(int i) {
        currentTab = i;
        for (int k = 0; k < tabViews.size(); k++) {
            TextView t = tabViews.get(k);
            boolean on = k == i;
            t.setTextColor(on ? Ui.TEXT : Ui.DIM);
            t.setBackground(on ? Ui.round(Ui.PANEL2, Ui.dp(this, 12), Ui.LINE, Ui.dp(this, 1)) : null);
        }
        tabContent.removeAllViews();
        eqCurve = null;
        switch (i) {
            case 0: buildTune(); break;
            case 1: buildEq(); break;
            case 2: buildComp(); break;
            case 3: buildFx(); break;
            case 4: buildVoices(); break;
            default: buildMix(); break;
        }
        tabScroll.scrollTo(0, 0);
    }

    private void rebuildTab() {
        int y = tabScroll.getScrollY();
        showTab(currentTab);
        tabScroll.post(() -> tabScroll.scrollTo(0, y));
    }

    // ------------------------------------------------------------------ helpers for controls

    private Knob knob(String label, float min, float max, float def, float val, boolean log, float step,
                      Knob.Format f, Knob.Listener l) {
        Knob k = new Knob(this, label, min, max, def, val, log, step).format(f);
        k.listener(v -> { l.onChange(v); changed(); });
        return k;
    }

    private LinearLayout knobRow(View... knobs) {
        LinearLayout r = Ui.row(this);
        for (View k : knobs) r.addView(k, Ui.weight(1));
        for (int i = knobs.length; i < 4; i++) r.addView(new View(this), Ui.weight(1));
        return r;
    }

    private void add(View v) {
        tabContent.addView(v, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private LinearLayout toggleRow(View... toggles) {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        for (View t : toggles) {
            r.addView(t, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    0, 0, Ui.dp(this, 8), 0));
        }
        return r;
    }

    private static Knob.Format fmt(String pattern) {
        return v -> String.format(Locale.US, pattern, v);
    }

    private static Knob.Format hz() {
        return v -> v >= 1000 ? String.format(Locale.US, "%.1fk", v / 1000) : String.format(Locale.US, "%.0f", v);
    }

    private static Knob.Format db() {
        return v -> String.format(Locale.US, "%+.1f dB", v);
    }

    private static Knob.Format pct() {
        return v -> String.format(Locale.US, "%.0f%%", v);
    }

    // ------------------------------------------------------------------ tabs

    private void buildTune() {
        add(toggleRow(Ui.toggle(this, "Pitch correction", p.tuneOn, v -> { p.tuneOn = v; changed(); })));

        add(Ui.label(this, "Key"));
        add(Ui.chips(this, Scales.NOTE_NAMES, p.key, 6, i -> { p.key = i; graphKey(); changed(); }));
        add(Ui.label(this, "Scale"));
        add(Ui.chips(this, Scales.NAMES, p.scale, 0, i -> { p.scale = i; graphKey(); changed(); }));

        TextView detect = Ui.button(this, "Detect key from my voice", false);
        detect.setOnClickListener(v -> {
            if (analysis == null) { Toast.makeText(this, "Still analyzing…", Toast.LENGTH_SHORT).show(); return; }
            int[] k = KeyDetect.detect(analysis, p.refHz);
            if (k == null) { Toast.makeText(this, "Not enough singing to guess the key", Toast.LENGTH_LONG).show(); return; }
            p.key = k[0];
            p.scale = k[1];
            Toast.makeText(this, "Detected: " + Scales.NOTE_NAMES[k[0]] + " " + Scales.NAMES[k[1]], Toast.LENGTH_LONG).show();
            graphKey();
            rebuildTab();
            changed();
        });
        tabContent.addView(detect, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                0, Ui.dp(this, 12), 0, 0));

        add(Ui.label(this, "Correction"));
        add(knobRow(
                knob("Retune speed", 0, 400, 20, p.retuneMs, false, 1,
                        v -> v < 1 ? "Robot" : String.format(Locale.US, "%.0f ms", v), v -> p.retuneMs = v),
                knob("Amount", 0, 100, 100, p.amount * 100, false, 1, pct(), v -> p.amount = v / 100f),
                knob("A4 ref", 415, 466, 440, p.refHz, false, 1, fmt("%.0f Hz"), v -> p.refHz = v),
                knob("Transpose", -12, 12, 0, p.transpose, false, 1,
                        v -> String.format(Locale.US, "%+.0f st", v), v -> p.transpose = v)));
        add(Ui.hint(this, "Retune 0 ms = hard robotic snap (T-Pain / trap). 50–150 ms keeps it natural. "
                + "A4 ref sets the tuning reference (440 Hz standard, 432/442 for other tunings)."));
    }

    private void buildEq() {
        eqCurve = new EqCurveView(this);
        eqCurve.setParams(p);
        eqCurve.setBackground(Ui.round(Ui.PANEL, Ui.dp(this, 14), 0, 0));
        eqCurve.setClipToOutline(true);
        tabContent.addView(eqCurve, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 130)),
                0, Ui.dp(this, 6), 0, 0));
        add(toggleRow(Ui.toggle(this, "EQ", p.eqOn, v -> { p.eqOn = v; changed(); })));

        add(Ui.label(this, "Cuts"));
        add(toggleRow(
                Ui.toggle(this, "Low cut", p.hpOn, v -> { p.hpOn = v; changed(); }),
                Ui.toggle(this, "High cut", p.lpOn, v -> { p.lpOn = v; changed(); })));
        add(knobRow(
                knob("Low cut Hz", 20, 600, 80, p.hpFreq, true, 0, hz(), v -> p.hpFreq = v),
                knob("High cut Hz", 2000, 20000, 16000, p.lpFreq, true, 0, hz(), v -> p.lpFreq = v)));

        add(Ui.label(this, "Low · High shelf"));
        add(knobRow(
                knob("Low", -15, 15, 0, p.lowGain, false, 0.5f, db(), v -> p.lowGain = v),
                knob("Low Hz", 60, 600, 200, p.lowFreq, true, 0, hz(), v -> p.lowFreq = v),
                knob("High", -15, 15, 0, p.highGain, false, 0.5f, db(), v -> p.highGain = v),
                knob("High Hz", 2000, 16000, 6000, p.highFreq, true, 0, hz(), v -> p.highFreq = v)));
        add(Ui.label(this, "Mid band"));
        add(knobRow(
                knob("Mid", -15, 15, 0, p.midGain, false, 0.5f, db(), v -> p.midGain = v),
                knob("Mid Hz", 200, 8000, 1500, p.midFreq, true, 0, hz(), v -> p.midFreq = v),
                knob("Width (Q)", 0.3f, 6, 1, p.midQ, true, 0, fmt("%.1f"), v -> p.midQ = v)));
        add(Ui.hint(this, "Low cut around 80–120 Hz removes rumble and mic handling noise. "
                + "A small high shelf boost adds air; cut 250–500 Hz to remove muddiness."));
    }

    private void buildComp() {
        add(toggleRow(Ui.toggle(this, "Compressor", p.compOn, v -> { p.compOn = v; changed(); })));
        add(knobRow(
                knob("Threshold", -40, 0, -18, p.compThresh, false, 0.5f, fmt("%.1f dB"), v -> p.compThresh = v),
                knob("Ratio", 1, 20, 3, p.compRatio, true, 0, fmt("%.1f:1"), v -> p.compRatio = v),
                knob("Makeup", 0, 18, 3, p.compMakeup, false, 0.5f, fmt("+%.1f dB"), v -> p.compMakeup = v)));
        add(knobRow(
                knob("Attack", 0.5f, 100, 10, p.compAttack, true, 0, fmt("%.1f ms"), v -> p.compAttack = v),
                knob("Release", 20, 1000, 120, p.compRelease, true, 0, fmt("%.0f ms"), v -> p.compRelease = v)));
        add(Ui.hint(this, "Evens out loud and quiet words. Start around −18 dB, 3:1, then raise Makeup to taste."));
    }

    private void buildFx() {
        add(Ui.label(this, "Delay"));
        add(toggleRow(
                Ui.toggle(this, "Delay", p.delayOn, v -> { p.delayOn = v; changed(); }),
                Ui.toggle(this, "Ping-pong", p.pingPong, v -> { p.pingPong = v; changed(); })));

        Knob bpm = knob("Tempo", 60, 200, 90, p.bpm, false, 1, fmt("%.0f BPM"), v -> p.bpm = v);
        TextView tap = Ui.button(this, "Tap tempo", false);
        tap.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (!taps.isEmpty() && now - taps.get(taps.size() - 1) > 2000) taps.clear();
            taps.add(now);
            if (taps.size() > 5) taps.remove(0);
            if (taps.size() >= 2) {
                double avg = (taps.get(taps.size() - 1) - taps.get(0)) / (double) (taps.size() - 1);
                float t = (float) Math.max(60, Math.min(200, 60000.0 / avg));
                p.bpm = Math.round(t);
                bpm.setValue(p.bpm);
                changed();
            }
        });
        LinearLayout r = Ui.row(this);
        r.addView(bpm, Ui.weight(1));
        LinearLayout tw = Ui.column(this);
        tw.setGravity(Gravity.CENTER);
        tw.addView(tap);
        r.addView(tw, Ui.weight(2));
        r.addView(new View(this), Ui.weight(1));
        add(r);
        add(Ui.chips(this, Params.DELAY_DIVS, p.delayDiv, 0, i -> { p.delayDiv = i; changed(); }));
        add(knobRow(
                knob("Feedback", 0, 90, 30, p.delayFeedback * 100, false, 1, pct(), v -> p.delayFeedback = v / 100f),
                knob("Mix", 0, 100, 20, p.delayMix * 100, false, 1, pct(), v -> p.delayMix = v / 100f)));

        add(Ui.label(this, "Reverb"));
        add(toggleRow(Ui.toggle(this, "Reverb", p.reverbOn, v -> { p.reverbOn = v; changed(); })));
        add(knobRow(
                knob("Size", 0, 100, 60, p.revSize * 100, false, 1, pct(), v -> p.revSize = v / 100f),
                knob("Damping", 0, 100, 40, p.revDamp * 100, false, 1, pct(), v -> p.revDamp = v / 100f),
                knob("Pre-delay", 0, 150, 20, p.revPreDelay, false, 1, fmt("%.0f ms"), v -> p.revPreDelay = v),
                knob("Mix", 0, 100, 18, p.revMix * 100, false, 1, pct(), v -> p.revMix = v / 100f)));
        add(knobRow(
                knob("Width", 0, 100, 100, p.revWidth * 100, false, 1, pct(), v -> p.revWidth = v / 100f)));
        add(Ui.hint(this, "Set the tempo to your beat's BPM so delay repeats land on the groove."));
    }

    private void buildVoices() {
        add(Ui.label(this, "Harmony"));
        add(Ui.chips(this, Params.HARMONY_NAMES, p.harmony, 0, i -> { p.harmony = i; changed(); }));
        add(knobRow(knob("Harmony level", 0, 100, 50, p.harmonyLevel * 100, false, 1, pct(),
                v -> p.harmonyLevel = v / 100f)));
        add(Ui.hint(this, "Harmonies are generated from your voice and follow the key & scale on the TUNE tab."));

        add(Ui.label(this, "Doubler"));
        add(toggleRow(Ui.toggle(this, "Doubler", p.doublerOn, v -> { p.doublerOn = v; changed(); })));
        add(knobRow(
                knob("Level", 0, 100, 50, p.doublerLevel * 100, false, 1, pct(), v -> p.doublerLevel = v / 100f),
                knob("Detune", 0, 30, 10, p.doublerDetune, false, 1, fmt("%.0f ct"), v -> p.doublerDetune = v),
                knob("Width", 0, 100, 100, p.doublerWidth * 100, false, 1, pct(), v -> p.doublerWidth = v / 100f)));
        add(Ui.hint(this, "The doubler adds two slightly detuned, slightly late copies left and right for a thick, wide vocal."));
    }

    private void buildMix() {
        add(Ui.label(this, "Levels"));
        if (beat != null) {
            add(knobRow(
                    knob("Vocal", 0, 150, 100, p.vocalVol * 100, false, 1, pct(), v -> p.vocalVol = v / 100f),
                    knob("Beat", 0, 150, 80, p.beatVol * 100, false, 1, pct(), v -> p.beatVol = v / 100f),
                    knob("Sync", 0, 400, 100, p.latencyMs, false, 1, fmt("%.0f ms"), v -> p.latencyMs = v)));
            add(Ui.hint(this, "If your vocal sounds late against the beat, raise Sync. Wired headphones usually need "
                    + "~60–120 ms, Bluetooth 150–300 ms."));
        } else {
            add(knobRow(knob("Vocal", 0, 150, 100, p.vocalVol * 100, false, 1, pct(), v -> p.vocalVol = v / 100f)));
        }
        add(Ui.label(this, "Beat"));
        LinearLayout r = Ui.row(this);
        TextView pick = Ui.button(this, beat == null ? "Add a beat…" : "Replace beat…", false);
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("audio/*");
            startActivityForResult(i, REQ_BEAT);
        });
        r.addView(pick, Ui.margins(Ui.weight(1), 0, 0, Ui.dp(this, 8), 0));
        if (beat != null) {
            TextView rm = Ui.button(this, "Remove beat", false);
            rm.setOnClickListener(v -> {
                take.beat().delete();
                beat = null;
                rebuildTab();
                changed();
            });
            r.addView(rm, Ui.weight(1));
        }
        add(r);
    }

    // ------------------------------------------------------------------ processing

    private void load() {
        statusText.setText("Analyzing pitch…");
        graph.setEmptyText("Analyzing pitch…");
        worker.execute(() -> {
            try {
                WavIO.Audio a = WavIO.read(take.vocal());
                float[] x = a.mono();
                if (a.sampleRate != SR) x = Engine.resample(x, a.sampleRate, SR);
                float[][] bt = null;
                if (take.hasBeat()) {
                    WavIO.Audio ba = WavIO.read(take.beat());
                    bt = ba.channels.length > 1 ? ba.channels : new float[][]{ba.channels[0], ba.channels[0]};
                }
                Analysis an = Analysis.run(x, SR, f -> postProgress(f, "Analyzing pitch…"));
                final float[] fx = x;
                final float[][] fb = bt;
                ui.post(() -> {
                    vocal = fx;
                    beat = fb;
                    analysis = an;
                    if (currentTab == 5) rebuildTab();
                    render();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    statusText.setText("Couldn't open this take: " + e.getMessage());
                    graph.setEmptyText("Couldn't open this take");
                });
            }
        });
    }

    private void postProgress(float f, String label) {
        ui.post(() -> {
            progress.setVisibility(View.VISIBLE);
            progress.setProgress((int) (f * 1000));
            statusText.setText(label + " " + Math.round(f * 100) + "%");
        });
    }

    private final Runnable renderRunnable = this::render;

    /** Called whenever a setting changes: save, then re-render shortly after the user stops fiddling. */
    private void changed() {
        if (eqCurve != null) eqCurve.invalidate();
        ui.removeCallbacks(renderRunnable);
        ui.postDelayed(renderRunnable, 350);
    }

    private void render() {
        if (analysis == null) return;
        final int gen = ++renderGen;
        final Params snap = p.clone();
        final float[] x = vocal;
        final float[][] bt = beat;
        final Analysis an = analysis;
        TakeStore.save(take);
        worker.execute(() -> {
            if (gen != renderGen) return;
            try {
                Engine.Result r = Engine.render(x, SR, an, bt, snap, f -> {
                    if (gen == renderGen) postProgress(f, "Processing…");
                });
                short[] pcm = Engine.toPcm(r.mix);
                r.mix = null;
                ui.post(() -> {
                    if (gen != renderGen) return;
                    result = r;
                    mixPcm = pcm;
                    player.setSources(Player.of(pcm), originalSource(x, bt, snap));
                    graph.setData(r.detMidi, r.outMidi, r.framesPerSecond, x.length / (double) SR, snap.key, snap.scale);
                    progress.setVisibility(View.INVISIBLE);
                    statusText.setText(Scales.NOTE_NAMES[snap.key] + " " + Scales.NAMES[snap.scale]
                            + (snap.tuneOn ? " · " + (snap.retuneMs < 1 ? "robot" : Math.round(snap.retuneMs) + " ms") : " · tune off"));
                    updateTransport();
                });
            } catch (OutOfMemoryError e) {
                ui.post(() -> statusText.setText("This take is too long for this phone's memory"));
            } catch (Exception e) {
                ui.post(() -> statusText.setText("Processing failed: " + e.getMessage()));
            }
        });
    }

    /** The untouched recording (+ beat) generated on the fly, for A/B comparison. */
    private static Player.Source originalSource(float[] x, float[][] bt, Params snap) {
        final int shift = bt != null ? (int) (SR * snap.latencyMs / 1000.0) : 0;
        final int vLen = Math.max(0, x.length - shift);
        final int len = bt != null ? Math.max(vLen, bt[0].length) : vLen;
        final float vv = snap.vocalVol, bv = snap.beatVol;
        return new Player.Source() {
            @Override public int length() { return len; }
            @Override public void fill(int pos, short[] out, int frames) {
                for (int i = 0; i < frames; i++) {
                    int k = pos + i;
                    float v = k < vLen ? x[k + shift] * vv : 0;
                    float l = v, r = v;
                    if (bt != null && k < bt[0].length) { l += bt[0][k] * bv; r += bt[1][k] * bv; }
                    out[2 * i] = (short) (Math.max(-1f, Math.min(1f, l)) * 32767f);
                    out[2 * i + 1] = (short) (Math.max(-1f, Math.min(1f, r)) * 32767f);
                }
            }
        };
    }

    private void graphKey() {
        if (result != null) {
            graph.setData(result.detMidi, result.outMidi, result.framesPerSecond, vocal.length / (double) SR, p.key, p.scale);
        }
    }

    private double shiftSec() {
        return beat != null ? p.latencyMs / 1000.0 : 0;
    }

    // ------------------------------------------------------------------ transport

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateTransport();
            if (player.isPlaying()) ui.postDelayed(this, 50);
        }
    };

    private void togglePlay() {
        if (result == null) { Toast.makeText(this, "Still processing…", Toast.LENGTH_SHORT).show(); return; }
        if (player.isPlaying()) player.stop();
        else { player.play(); ui.post(tick); }
        updateTransport();
    }

    private void updateTransport() {
        boolean playing = player.isPlaying();
        playBtn.setText(playing ? "❚❚" : "▶");
        double pos = player.positionSec();
        double total = player.lengthFrames() / (double) SR;
        timeText.setText(Ui.time(pos) + " / " + Ui.time(total));
        graph.setPlayhead(playing || pos > 0 ? pos + shiftSec() : -1);
    }

    // ------------------------------------------------------------------ dialogs

    private void rename() {
        EditText e = new EditText(this);
        e.setText(take.name);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setSelectAllOnFocus(true);
        LinearLayout wrap = Ui.column(this);
        int pd = Ui.dp(this, 20);
        wrap.setPadding(pd, Ui.dp(this, 8), pd, 0);
        wrap.addView(e);
        Ui.dialog(this).setTitle("Rename take").setView(wrap)
                .setPositiveButton("Save", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (!n.isEmpty()) { take.name = n; titleText.setText(n); TakeStore.save(take); }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void presetsDialog() {
        Map<String, Params> builtIns = Presets.builtIns();
        Map<String, Params> user = Presets.user(this);
        List<String> items = new ArrayList<>();
        List<Params> values = new ArrayList<>();
        items.add("＋  Save current settings as preset…");
        values.add(null);
        for (Map.Entry<String, Params> e : builtIns.entrySet()) { items.add(e.getKey()); values.add(e.getValue()); }
        for (Map.Entry<String, Params> e : user.entrySet()) { items.add("★ " + e.getKey()); values.add(e.getValue()); }
        if (!user.isEmpty()) { items.add("Delete one of my presets…"); values.add(null); }
        Ui.dialog(this).setTitle("Presets")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) { savePresetDialog(); return; }
                    Params v = values.get(which);
                    if (v == null) { deletePresetDialog(new ArrayList<>(user.keySet())); return; }
                    Presets.apply(v, p);
                    rebuildTab();
                    changed();
                    Toast.makeText(this, "Preset: " + items.get(which).replace("★ ", ""), Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void savePresetDialog() {
        EditText e = new EditText(this);
        e.setHint("Preset name");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        LinearLayout wrap = Ui.column(this);
        int pd = Ui.dp(this, 20);
        wrap.setPadding(pd, Ui.dp(this, 8), pd, 0);
        wrap.addView(e);
        Ui.dialog(this).setTitle("Save preset").setView(wrap)
                .setPositiveButton("Save", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (n.isEmpty()) return;
                    Presets.saveUser(this, n, p);
                    Toast.makeText(this, "Saved “" + n + "”", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void deletePresetDialog(List<String> names) {
        Ui.dialog(this).setTitle("Delete preset")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    Presets.deleteUser(this, names.get(which));
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void exportDialog() {
        if (mixPcm == null) { Toast.makeText(this, "Still processing…", Toast.LENGTH_SHORT).show(); return; }
        String[] items = {"Save WAV to Music", "Save M4A to Music", "Share WAV…", "Share M4A…"};
        Ui.dialog(this).setTitle("Export “" + take.name + "”")
                .setItems(items, (d, which) -> doExport(which == 1 || which == 3, which >= 2)).show();
    }

    private void doExport(boolean m4a, boolean share) {
        final short[] mix = mixPcm;
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        statusText.setText("Exporting…");
        new Thread(() -> {
            try {
                Uri uri = Exporter.save(this, mix, take.name, m4a);
                ui.post(() -> {
                    progress.setIndeterminate(false);
                    progress.setVisibility(View.INVISIBLE);
                    statusText.setText("Saved to Music/Totune");
                    if (share) Exporter.share(this, uri, m4a);
                    else Toast.makeText(this, "Saved to Music/Totune", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    progress.setIndeterminate(false);
                    progress.setVisibility(View.INVISIBLE);
                    statusText.setText("Export failed: " + e.getMessage());
                });
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_BEAT || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        statusText.setText("Loading beat…");
        new Thread(() -> {
            try {
                float[][] st = AudioCodec.decodeStereo(this, uri, 600);
                WavIO.write(take.beat(), st, SR);
                ui.post(() -> {
                    beat = st;
                    rebuildTab();
                    changed();
                });
            } catch (Exception e) {
                ui.post(() -> statusText.setText("Couldn't load that beat: " + e.getMessage()));
            }
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.stop();
        if (take != null) TakeStore.save(take);
        if (playBtn != null) updateTransport();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        ui.removeCallbacksAndMessages(null);
    }
}
