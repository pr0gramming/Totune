package com.totune.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.totune.app.dsp.WavIO;

/** Records a vocal take with optional live autotune in the headphones, optionally over a beat. */
public class RecordActivity extends Activity {
    private static final int SR = NativeEngine.SR;
    private static final int MAX_SECONDS = 600;

    private TakeStore.Take take;
    private NativeEngine engine;
    private boolean hasBeat, saved, recording, streamOpen;
    private volatile boolean writing;
    private Thread writerThread;

    private TextView timer, status, hint, monitorBtn, songChip;
    private WaveformView wave;
    private RecButton recButton;
    private boolean monitor;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.styleWindow(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        take = TakeStore.create(this);
        engine = new NativeEngine();
        engine.setGlobals(take.params.key, take.params.scale, take.params.refHz, take.params.bpm);
        engine.setRack(take.rack);
        engine.setMix(1f, take.params.beatVol, 0);
        engine.load(new float[0], null, null);

        int pad = Ui.dp(this, 18);
        LinearLayout root = Ui.column(this);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout top = Ui.row(this);
        TextView close = Ui.text(this, "✕", 22, Ui.DIM, false);
        close.setPadding(0, 0, Ui.dp(this, 16), 0);
        close.setOnClickListener(v -> finish());
        top.addView(close);
        top.addView(Ui.text(this, take.name, 18, Ui.TEXT, true), Ui.weight(1));
        root.addView(top);

        LinearLayout chips = Ui.row(this);
        chips.setPadding(0, Ui.dp(this, 12), 0, 0);
        songChip = chip("♪  " + Sheets.songSummary(take.params));
        songChip.setOnClickListener(v -> Sheets.song(this, take.params, null, () -> {
            engine.setGlobals(take.params.key, take.params.scale, take.params.refHz, take.params.bpm);
            songChip.setText("♪  " + Sheets.songSummary(take.params));
            Presets.saveLastSong(this, take.params);
        }));
        chips.addView(songChip, Ui.margins(Ui.weight(1), 0, 0, Ui.dp(this, 8), 0));
        TextView tune = chip("⚙ Autotune");
        tune.setOnClickListener(v -> openTune());
        chips.addView(tune);
        root.addView(chips);

        timer = Ui.text(this, "0:00", 64, Ui.TEXT, true);
        timer.setGravity(Gravity.CENTER);
        timer.setPadding(0, Ui.dp(this, 26), 0, Ui.dp(this, 6));
        root.addView(timer, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = Ui.text(this, "Ready", 14, Ui.DIM, false);
        status.setGravity(Gravity.CENTER);
        root.addView(status, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        wave = new WaveformView(this);
        root.addView(wave, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 130)),
                0, Ui.dp(this, 22), 0, Ui.dp(this, 18)));

        monitorBtn = Ui.text(this, "", 14, Ui.TEXT, true);
        monitorBtn.setGravity(Gravity.CENTER);
        monitorBtn.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 12));
        monitorBtn.setOnClickListener(v -> setMonitor(!monitor));
        LinearLayout mw = Ui.row(this);
        mw.setGravity(Gravity.CENTER);
        mw.addView(monitorBtn);
        root.addView(mw, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));

        FrameLayout btnWrap = new FrameLayout(this);
        recButton = new RecButton(this);
        recButton.setOnClickListener(v -> { if (recording) stopRecording(true); else startRecording(); });
        btnWrap.addView(recButton, new FrameLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 96), Gravity.CENTER));
        root.addView(btnWrap, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 110)));

        hint = Ui.hint(this, "");
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        setMonitor(headphones() != 0);

        Uri beatUri = getIntent().getData();
        if (beatUri != null) loadBeat(beatUri);
    }

    private TextView chip(String s) {
        TextView t = Ui.text(this, s, 13, Ui.TEXT, true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        t.setBackground(Ui.ripple(Ui.round(Ui.PANEL2, Ui.dp(this, 12), Ui.LINE, Ui.dp(this, 1))));
        return t;
    }

    /** 0 = speaker only, 1 = wired/USB headphones, 2 = Bluetooth. */
    private int headphones() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int best = 0;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    || t == AudioDeviceInfo.TYPE_USB_HEADSET) best = 1;
            else if ((t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == 26 /* BLE headset */) && best == 0) best = 2;
        }
        return best;
    }

    private void setMonitor(boolean on) {
        monitor = on;
        engine.setMonitor(on);
        monitorBtn.setText(on ? "🎧  Live autotune: ON" : "🎧  Live autotune: OFF");
        monitorBtn.setTextColor(on ? 0xFF15100D : Ui.TEXT);
        monitorBtn.setBackground(Ui.ripple(on ? Ui.round(Ui.TEAL, Ui.dp(this, 22), 0, 0)
                : Ui.round(Ui.PANEL2, Ui.dp(this, 22), Ui.LINE, Ui.dp(this, 1))));
        int hp = headphones();
        if (on && hp == 0) hint.setText("No headphones: live monitoring through the speaker will feed back into the mic.");
        else if (on && hp == 2) hint.setText("Bluetooth adds a noticeable delay. Wired or USB-C headphones feel instant.");
        else if (on) hint.setText("You hear yourself tuned live. The recording stays clean, so you can change everything after.");
        else hint.setText(hasBeat ? "Use headphones so the beat doesn't leak into your vocal." : "Hold the phone ~20 cm away and sing at a steady volume.");
    }

    private void openTune() {
        Rack.Module m = take.rack.firstOfType(ModuleType.TUNE);
        if (m == null) {
            m = take.rack.add(ModuleType.TUNE);
            take.rack.modules.remove(m);
            take.rack.modules.add(0, m);
            engine.setRack(take.rack);
        }
        Sheets.module(this, m, new Sheets.ModuleListener() {
            @Override public void onParam(Rack.Module mm, int index, float value) {
                mm.p[index] = value;
                engine.setParam(mm.id, index, value);
                Presets.saveLastRack(RecordActivity.this, take.rack);
            }
            @Override public void onOn(Rack.Module mm, boolean on) { mm.on = on; engine.setOn(mm.id, on); }
            @Override public void onRemove(Rack.Module mm) { take.rack.modules.remove(mm); engine.setRack(take.rack); }
        });
    }

    private void loadBeat(Uri uri) {
        recButton.setEnabled(false);
        status.setText("Loading beat…");
        new Thread(() -> {
            try {
                float[][] st = AudioCodec.decodeStereo(this, uri, MAX_SECONDS);
                WavIO.write(take.beat(), st, SR);
                runOnUiThread(() -> {
                    hasBeat = true;
                    engine.load(new float[0], st[0], st[1]);
                    status.setText("Beat loaded (" + Ui.time(st[0].length / (double) SR) + ") — tap to record");
                    recButton.setEnabled(true);
                    setMonitor(monitor);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Couldn't load that beat — recording vocals only");
                    recButton.setEnabled(true);
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------ streams

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        } else {
            openStream();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) openStream();
        else status.setText("Microphone permission is needed to record");
    }

    private void openStream() {
        streamOpen = engine.openDuplex();
        if (!streamOpen) {
            status.setText("Couldn't open the microphone");
            return;
        }
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (engine.needsRestart()) {
                engine.close();
                streamOpen = engine.openDuplex();
                setMonitor(monitor);
            }
            wave.push(engine.inputPeak());
            if (recording) {
                long frames = engine.recordedFrames();
                timer.setText(Ui.time(frames / (double) SR));
                if (frames >= (long) MAX_SECONDS * SR) stopRecording(true);
            }
            if (streamOpen) ui.postDelayed(this, 40);
        }
    };

    // ------------------------------------------------------------------ recording

    private void startRecording() {
        if (!streamOpen) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            } else openStream();
            if (!streamOpen) return;
        }
        final WavIO.Writer writer;
        try {
            writer = new WavIO.Writer(take.vocal(), SR);
        } catch (Exception e) {
            Toast.makeText(this, "Can't write the recording: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        engine.record(true);
        recording = true;
        writing = true;
        wave.clear();
        wave.setActive(true);
        recButton.setRecording(true);
        status.setText(hasBeat ? "Recording over the beat…" : "Recording…");
        writerThread = new Thread(() -> {
            short[] buf = new short[8192];
            try {
                while (true) {
                    int n = engine.readRecorded(buf);
                    if (n > 0) { writer.write(buf, n); continue; }
                    if (!writing) break;
                    Thread.sleep(10);
                }
            } catch (Exception ignored) {
            } finally {
                try { writer.close(); } catch (Exception ignored) { }
            }
        }, "rec-writer");
        writerThread.start();
    }

    private void stopRecording(boolean openEditor) {
        if (!recording) return;
        float rt = engine.roundTripMs();
        long frames = engine.recordedFrames();
        engine.record(false);
        recording = false;
        writing = false;
        try { if (writerThread != null) writerThread.join(3000); } catch (InterruptedException ignored) { }
        recButton.setRecording(false);
        wave.setActive(false);
        if (frames < SR / 2) {
            status.setText("Too short — tap to try again");
            return;
        }
        take.duration = frames / (double) SR;
        if (hasBeat) take.params.latencyMs = rt > 0 ? Math.min(400, rt) : 100;
        TakeStore.save(take);
        Presets.saveLastRack(this, take.rack);
        saved = true;
        if (openEditor) {
            startActivity(new Intent(this, EditorActivity.class).putExtra("take", take.id()));
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (recording) stopRecording(false);
        ui.removeCallbacks(tick);
        engine.close();
        streamOpen = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        engine.release();
        if (!saved) TakeStore.delete(take);
    }

    /** The big round record/stop button. */
    static final class RecButton extends View {
        private boolean rec;
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);

        RecButton(android.content.Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dp(c, 4));
            ring.setColor(Ui.TEXT);
            fill.setColor(Ui.RED);
            setClickable(true);
        }

        void setRecording(boolean r) { rec = r; invalidate(); }

        @Override
        public void setEnabled(boolean e) { super.setEnabled(e); setAlpha(e ? 1f : 0.35f); }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - Ui.dp(getContext(), 4);
            c.drawCircle(cx, cy, r, ring);
            if (rec) {
                float s = r * 0.42f;
                c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, Ui.dp(getContext(), 6), Ui.dp(getContext(), 6), fill);
            } else {
                c.drawCircle(cx, cy, r * 0.78f, fill);
            }
        }
    }
}
