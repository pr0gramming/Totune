package com.totune.app;

import android.app.Activity;
import android.app.AlertDialog;
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

import com.totune.app.dsp.Params;
import com.totune.app.dsp.WavIO;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The studio: pitch graph, transport, song settings and the processing rack — all heard in real time. */
@SuppressWarnings("deprecation")
public class EditorActivity extends Activity implements RackView.Host {
    private static final int SR = NativeEngine.SR;
    private static final int REQ_BEAT = 20;

    private TakeStore.Take take;
    private Params p;
    private Rack rack;
    private NativeEngine engine;
    private float[] vocal, beatL, beatR;
    private float[] sung;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int graphGen = 0;

    private PitchGraphView graph;
    private TextView playBtn, timeText, statusText, titleText, songChip;
    private ProgressBar progress;
    private RackView rackView;
    private AlertDialog openSheet;
    private boolean loaded;

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
        rack = take.rack;
        engine = new NativeEngine();
        pushGlobals();
        engine.setRack(rack);
        buildUi();
        load();
    }

    // ------------------------------------------------------------------ layout

    private void buildUi() {
        int pad = Ui.dp(this, 14);
        LinearLayout root = Ui.column(this);
        root.setPadding(pad, pad, pad, Ui.dp(this, 10));

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

        LinearLayout chips = Ui.row(this);
        chips.setPadding(0, Ui.dp(this, 8), 0, 0);
        songChip = chipButton("♪  " + Sheets.songSummary(p));
        songChip.setOnClickListener(v -> Sheets.song(this, p, sung, this::songChanged));
        chips.addView(songChip, Ui.margins(Ui.weight(1), 0, 0, Ui.dp(this, 8), 0));
        TextView mix = chipButton("Beat & mix");
        mix.setOnClickListener(v -> mixDialog());
        chips.addView(mix);
        root.addView(chips);

        graph = new PitchGraphView(this);
        graph.setSeekListener(sec -> engine.seek((long) (sec * SR)));
        graph.setBackground(Ui.round(Ui.PANEL, Ui.dp(this, 14), 0, 0));
        graph.setClipToOutline(true);
        graph.setEmptyText("Loading…");
        root.addView(graph, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 150)),
                0, Ui.dp(this, 8), 0, Ui.dp(this, 4)));
        LinearLayout legend = Ui.row(this);
        legend.addView(Ui.text(this, "━ sung", 11, 0xFF8088A0, false));
        legend.addView(Ui.text(this, "   ━ tuned", 11, Ui.TEAL, false));
        legend.addView(Ui.text(this, "      tap to seek · changes play live", 11, Ui.DIM, false));
        root.addView(legend);

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
        statusText.setSingleLine(true);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tcol.addView(timeText);
        tcol.addView(statusText);
        tr.addView(tcol, Ui.weight(1));
        tr.addView(Ui.chips(this, new String[]{"Tuned", "Original"}, 0, 0, i -> engine.setOriginal(i == 1)));
        root.addView(tr);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        root.addView(progress, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 4)));

        ScrollView sv = new ScrollView(this);
        sv.setClipChildren(false);
        rackView = new RackView(this);
        rackView.setPadding(0, Ui.dp(this, 10), 0, 0);
        rackView.bind(rack, this);
        sv.addView(rackView);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView export = Ui.button(this, "Export / Share", true);
        export.setOnClickListener(v -> exportDialog());
        root.addView(export, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                0, Ui.dp(this, 6), 0, 0));
        setContentView(root);
    }

    private TextView chipButton(String s) {
        TextView t = Ui.text(this, s, 13, Ui.TEXT, true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        t.setBackground(Ui.ripple(Ui.round(Ui.PANEL2, Ui.dp(this, 12), Ui.LINE, Ui.dp(this, 1))));
        return t;
    }

    // ------------------------------------------------------------------ loading

    private void load() {
        worker.execute(() -> {
            try {
                WavIO.Audio a = WavIO.read(take.vocal());
                float[] x = WavIO.resample(a.mono(), a.sampleRate, SR);
                float[] bl = null, br = null;
                if (take.hasBeat()) {
                    WavIO.Audio ba = WavIO.read(take.beat());
                    bl = WavIO.resample(ba.channels[0], ba.sampleRate, SR);
                    br = ba.channels.length > 1 ? WavIO.resample(ba.channels[1], ba.sampleRate, SR) : bl;
                }
                final float[] fbl = bl, fbr = br;
                ui.post(() -> {
                    vocal = x; beatL = fbl; beatR = fbr;
                    engine.load(vocal, beatL, beatR);
                    pushMix();
                    loaded = true;
                    progress.setVisibility(View.INVISIBLE);
                    statusText.setText(statusLine());
                    updateTransport();
                    refreshGraph();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    statusText.setText("Couldn't open this take: " + e.getMessage());
                    graph.setEmptyText("Couldn't open this take");
                });
            }
        });
    }

    private String statusLine() {
        Rack.Module t = rack.firstOfType(ModuleType.TUNE);
        String tune = t == null ? "no autotune" : !t.on ? "autotune off"
                : ModuleType.STYLES[Math.round(t.p[0])] + " · " + (t.p[1] < 1 ? "robot" : Math.round(t.p[1]) + " ms");
        return tune + "  ·  " + rack.modules.size() + (rack.modules.size() == 1 ? " module" : " modules");
    }

    // ------------------------------------------------------------------ engine sync

    private void pushGlobals() { engine.setGlobals(p.key, p.scale, p.refHz, p.bpm); }

    private void pushMix() {
        int sync = beatL != null ? Math.round(p.latencyMs * SR / 1000f) : 0;
        engine.setMix(p.vocalVol, beatL != null ? p.beatVol : 0, sync);
    }

    private final Runnable saveRunnable = () -> {
        TakeStore.save(take);
        Presets.saveLastRack(this, rack);
    };

    private void saveSoon() {
        ui.removeCallbacks(saveRunnable);
        ui.postDelayed(saveRunnable, 600);
    }

    private final Runnable graphRunnable = this::refreshGraph;

    private void graphSoon() {
        ui.removeCallbacks(graphRunnable);
        ui.postDelayed(graphRunnable, 250);
    }

    private void refreshGraph() {
        if (!loaded) return;
        final int gen = ++graphGen;
        Rack.Module t = rack.firstOfType(ModuleType.TUNE);
        float[] tp = t != null ? t.p.clone() : ModuleType.get(ModuleType.TUNE).defaults();
        if (t == null || !t.on) tp[2] = 0;          // no correction: tuned line = sung line
        final float[] ftp = tp;
        final int key = p.key, scale = p.scale;
        final float ref = p.refHz;
        final float[] x = vocal;
        worker.execute(() -> {
            if (gen != graphGen) return;
            float[][] r = NativeEngine.analyze(x, key, scale, ref, ftp);
            ui.post(() -> {
                if (gen != graphGen) return;
                sung = r[0];
                graph.setData(r[0], r[1], SR / 256.0, x.length / (double) SR, key, scale);
            });
        });
    }

    private void songChanged() {
        pushGlobals();
        songChip.setText("♪  " + Sheets.songSummary(p));
        Presets.saveLastSong(this, p);
        graphSoon();
        saveSoon();
    }

    // ------------------------------------------------------------------ RackView.Host

    @Override
    public void structureChanged() {
        engine.setRack(rack);
        rackView.rebuild();
        statusText.setText(statusLine());
        graphSoon();
        saveSoon();
    }

    @Override
    public void paramChanged(Rack.Module m, int index, float value) {
        m.p[index] = value;
        engine.setParam(m.id, index, value);
        if (m.type == ModuleType.TUNE) { graphSoon(); statusText.setText(statusLine()); }
        saveSoon();
    }

    @Override
    public void onChanged(Rack.Module m, boolean on) {
        engine.setOn(m.id, on);
        if (m.type == ModuleType.TUNE) { graphSoon(); statusText.setText(statusLine()); }
        saveSoon();
    }

    @Override
    public void configure(Rack.Module m) {
        openSheet = Sheets.module(this, m, new Sheets.ModuleListener() {
            @Override public void onParam(Rack.Module mm, int index, float value) { paramChanged(mm, index, value); }
            @Override public void onOn(Rack.Module mm, boolean on) { mm.on = on; onChanged(mm, on); }
            @Override public void onRemove(Rack.Module mm) { rack.modules.remove(mm); structureChanged(); }
        });
        openSheet.setOnDismissListener(d -> rackView.rebuild());
    }

    @Override
    public void addRequested() {
        Sheets.addModule(this, type -> {
            Rack.Module m = rack.add(type);
            Rack.Module tune = rack.firstOfType(ModuleType.TUNE);
            if (tune != null && type == ModuleType.HARMONY) m.p[4] = tune.p[6];
            structureChanged();
            configure(m);
        });
    }

    // ------------------------------------------------------------------ transport

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (engine.needsRestart() && engine.isPlaying()) {
                engine.close();
                engine.openPlayback();
                engine.play(true);
            }
            updateTransport();
            if (engine.isPlaying()) ui.postDelayed(this, 40);
        }
    };

    private void togglePlay() {
        if (!loaded) { Toast.makeText(this, "Still loading…", Toast.LENGTH_SHORT).show(); return; }
        if (engine.isPlaying()) {
            engine.play(false);
        } else {
            if (!engine.openPlayback()) {
                Toast.makeText(this, "Couldn't open the audio output", Toast.LENGTH_LONG).show();
                return;
            }
            engine.play(true);
            ui.post(tick);
        }
        updateTransport();
    }

    private void updateTransport() {
        boolean playing = engine.isPlaying();
        playBtn.setText(playing ? "❚❚" : "▶");
        double pos = engine.position() / (double) SR;
        double total = vocal != null ? vocal.length / (double) SR : 0;
        timeText.setText(Ui.time(pos) + " / " + Ui.time(total));
        graph.setPlayhead(playing || pos > 0 ? pos : -1);
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

    private void mixDialog() {
        LinearLayout col = Ui.column(this);
        int pd = Ui.dp(this, 18);
        col.setPadding(pd, Ui.dp(this, 4), pd, pd);
        LinearLayout r = Ui.row(this);
        Knob vv = new Knob(this, "Vocal", 0, 150, 100, p.vocalVol * 100, false, 1).format(v -> String.format(Locale.US, "%.0f%%", v));
        vv.listener(v -> { p.vocalVol = v / 100f; pushMix(); saveSoon(); });
        r.addView(vv, Ui.weight(1));
        if (beatL != null) {
            Knob bv = new Knob(this, "Beat", 0, 150, 80, p.beatVol * 100, false, 1).format(v -> String.format(Locale.US, "%.0f%%", v));
            bv.listener(v -> { p.beatVol = v / 100f; pushMix(); saveSoon(); });
            Knob sy = new Knob(this, "Sync", 0, 400, 100, p.latencyMs, false, 1).format(v -> String.format(Locale.US, "%.0f ms", v));
            sy.listener(v -> { p.latencyMs = v; pushMix(); saveSoon(); });
            r.addView(bv, Ui.weight(1));
            r.addView(sy, Ui.weight(1));
        } else {
            r.addView(new View(this), Ui.weight(2));
        }
        r.addView(new View(this), Ui.weight(1));
        col.addView(r);
        if (beatL != null) col.addView(Ui.hint(this, "Sync was measured from your phone's audio delay when you recorded. "
                + "If the vocal still sounds late against the beat, raise it; early, lower it."));
        final AlertDialog[] dlg = new AlertDialog[1];
        LinearLayout br = Ui.row(this);
        TextView pick = Ui.button(this, beatL == null ? "Add a beat…" : "Replace beat…", false);
        pick.setOnClickListener(v -> {
            dlg[0].dismiss();
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("audio/*");
            startActivityForResult(i, REQ_BEAT);
        });
        br.addView(pick, Ui.margins(Ui.weight(1), 0, Ui.dp(this, 10), Ui.dp(this, 8), 0));
        if (beatL != null) {
            TextView rm = Ui.button(this, "Remove beat", false);
            rm.setOnClickListener(v -> {
                dlg[0].dismiss();
                take.beat().delete();
                beatL = beatR = null;
                engine.play(false);
                engine.load(vocal, null, null);
                pushMix();
                updateTransport();
            });
            br.addView(rm, Ui.margins(Ui.weight(1), 0, Ui.dp(this, 10), 0, 0));
        }
        col.addView(br);
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        dlg[0] = Ui.dialog(this).setTitle("Beat & mix").setView(sv).setPositiveButton("Done", null).create();
        dlg[0].show();
    }

    private void presetsDialog() {
        LinearLayout col = Ui.column(this);
        int pd = Ui.dp(this, 18);
        col.setPadding(pd, Ui.dp(this, 4), pd, pd);
        final AlertDialog[] dlg = new AlertDialog[1];
        TextView save = Ui.button(this, "＋  Save this rack as a preset", false);
        save.setOnClickListener(v -> { dlg[0].dismiss(); savePresetDialog(); });
        col.addView(save);
        for (Map.Entry<String, List<Presets.Preset>> cat : Presets.categories().entrySet()) {
            col.addView(Ui.label(this, cat.getKey()));
            for (Presets.Preset pr : cat.getValue()) {
                col.addView(presetRow(pr.name, pr.desc, () -> { dlg[0].dismiss(); applyPreset(pr.name, pr.rack); }, null));
            }
        }
        Map<String, Rack> user = Presets.user(this);
        if (!user.isEmpty()) {
            col.addView(Ui.label(this, "My presets"));
            for (Map.Entry<String, Rack> e : user.entrySet()) {
                col.addView(presetRow("★ " + e.getKey(), e.getValue().modules.size() + " modules · long-press to delete",
                        () -> { dlg[0].dismiss(); applyPreset(e.getKey(), e.getValue()); },
                        () -> {
                            dlg[0].dismiss();
                            Ui.dialog(this).setTitle("Delete “" + e.getKey() + "”?")
                                    .setPositiveButton("Delete", (d, w) -> Presets.deleteUser(this, e.getKey()))
                                    .setNegativeButton("Cancel", null).show();
                        }));
            }
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        dlg[0] = Ui.dialog(this).setTitle("Presets").setView(sv).setNegativeButton("Close", null).create();
        dlg[0].show();
    }

    private View presetRow(String name, String desc, Runnable onTap, Runnable onLong) {
        LinearLayout r = Ui.column(this);
        int ph = Ui.dp(this, 14), pv = Ui.dp(this, 10);
        r.setPadding(ph, pv, ph, pv);
        r.setBackground(Ui.ripple(Ui.round(Ui.PANEL2, Ui.dp(this, 12), Ui.LINE, Ui.dp(this, 1))));
        r.addView(Ui.text(this, name, 15, Ui.TEXT, true));
        r.addView(Ui.text(this, desc, 12, Ui.DIM, false));
        r.setOnClickListener(v -> onTap.run());
        if (onLong != null) r.setOnLongClickListener(v -> { onLong.run(); return true; });
        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, Ui.dp(this, 6));
        r.setLayoutParams(lp);
        return r;
    }

    private void applyPreset(String name, Rack preset) {
        Rack next = Presets.applied(preset, rack);
        // keep ids increasing so the engine treats every module as new
        rack.modules.clear();
        for (Rack.Module m : next.modules) {
            Rack.Module n = rack.add(m.type);
            n.on = m.on;
            n.p = m.p.clone();
        }
        structureChanged();
        Toast.makeText(this, "Preset: " + name, Toast.LENGTH_SHORT).show();
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
                    Presets.saveUser(this, n, rack);
                    Toast.makeText(this, "Saved “" + n + "”", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void exportDialog() {
        if (!loaded) { Toast.makeText(this, "Still loading…", Toast.LENGTH_SHORT).show(); return; }
        String[] items = {"Save WAV to Music", "Save M4A to Music", "Share WAV…", "Share M4A…"};
        Ui.dialog(this).setTitle("Export “" + take.name + "”")
                .setItems(items, (d, which) -> doExport(which == 1 || which == 3, which >= 2)).show();
    }

    private void doExport(boolean m4a, boolean share) {
        final Rack r = rack.copy();
        final float[] x = vocal, bl = beatL, br = beatR;
        final Params snap = p.clone();
        final int sync = bl != null ? Math.round(snap.latencyMs * SR / 1000f) : 0;
        progress.setVisibility(View.VISIBLE);
        statusText.setText("Exporting…");
        new Thread(() -> {
            try {
                short[] mix = NativeEngine.render(x, bl, br, snap.key, snap.scale, snap.refHz, snap.bpm, r,
                        snap.vocalVol, bl != null ? snap.beatVol : 0, sync);
                Uri uri = Exporter.save(this, mix, take.name, m4a);
                ui.post(() -> {
                    progress.setVisibility(View.INVISIBLE);
                    statusText.setText("Saved to Music/Totune");
                    if (share) Exporter.share(this, uri, m4a);
                    else Toast.makeText(this, "Saved to Music/Totune", Toast.LENGTH_LONG).show();
                });
            } catch (Throwable e) {
                ui.post(() -> {
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
                    beatL = st[0]; beatR = st[1];
                    engine.play(false);
                    engine.load(vocal, beatL, beatR);
                    pushMix();
                    statusText.setText(statusLine());
                    updateTransport();
                });
            } catch (Exception e) {
                ui.post(() -> statusText.setText("Couldn't load that beat: " + e.getMessage()));
            }
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (engine != null) {
            engine.play(false);
            engine.close();
        }
        if (take != null) { TakeStore.save(take); Presets.saveLastRack(this, rack); }
        if (playBtn != null) updateTransport();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        if (engine != null) engine.release();
    }
}
