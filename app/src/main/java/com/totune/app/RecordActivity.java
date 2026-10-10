package com.totune.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
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

/** Records a vocal take, optionally while a beat plays in the headphones. */
public class RecordActivity extends Activity {
    private static final int SR = AudioCodec.SR;
    private static final int MAX_SECONDS = 600;

    private TakeStore.Take take;
    private short[] beat;
    private Player beatPlayer;
    private volatile boolean recording;
    private Thread recThread;
    private long startedAt;
    private boolean saved;

    private TextView timer, status, hint;
    private WaveformView wave;
    private RecButton recButton;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.styleWindow(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        take = TakeStore.create(this);

        int pad = Ui.dp(this, 18);
        LinearLayout root = Ui.column(this);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout top = Ui.row(this);
        TextView close = Ui.text(this, "✕", 22, Ui.DIM, false);
        close.setPadding(0, 0, Ui.dp(this, 16), 0);
        close.setOnClickListener(v -> finish());
        top.addView(close);
        top.addView(Ui.text(this, take.name, 18, Ui.TEXT, true));
        root.addView(top);

        timer = Ui.text(this, "0:00", 64, Ui.TEXT, true);
        timer.setGravity(Gravity.CENTER);
        timer.setPadding(0, Ui.dp(this, 36), 0, Ui.dp(this, 8));
        root.addView(timer, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = Ui.text(this, "Ready", 14, Ui.DIM, false);
        status.setGravity(Gravity.CENTER);
        root.addView(status, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        wave = new WaveformView(this);
        root.addView(wave, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 140)),
                0, Ui.dp(this, 28), 0, Ui.dp(this, 28)));

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));

        FrameLayout btnWrap = new FrameLayout(this);
        recButton = new RecButton(this);
        recButton.setOnClickListener(v -> toggle());
        btnWrap.addView(recButton, new FrameLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 96), Gravity.CENTER));
        root.addView(btnWrap, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 110)));

        hint = Ui.hint(this, "Tip: hold the phone ~20 cm away and sing at a steady volume.");
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        Uri beatUri = getIntent().getData();
        if (beatUri != null) loadBeat(beatUri);

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
    }

    private void loadBeat(Uri uri) {
        recButton.setEnabled(false);
        status.setText("Loading beat…");
        new Thread(() -> {
            try {
                float[][] st = AudioCodec.decodeStereo(this, uri, MAX_SECONDS);
                WavIO.write(take.beat(), st, SR);
                short[] pcm = com.totune.app.dsp.Engine.toPcm(st);
                runOnUiThread(() -> {
                    beat = pcm;
                    beatPlayer = new Player(SR);
                    beatPlayer.setSources(Player.of(pcm), null);
                    status.setText("Beat loaded (" + Ui.time(pcm.length / 2.0 / SR) + ") — tap to record");
                    hint.setText("Use headphones so the beat doesn't leak into your vocal.");
                    recButton.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Couldn't load that beat — recording vocals only");
                    recButton.setEnabled(true);
                });
            }
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (res.length == 0 || res[0] != PackageManager.PERMISSION_GRANTED) {
            status.setText("Microphone permission is needed to record");
        }
    }

    private void toggle() {
        if (recording) stopRecording(true);
        else startRecording();
    }

    private void startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        int min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        final AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 4, SR));
        } catch (SecurityException e) {
            status.setText("Microphone permission is needed to record");
            return;
        }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            Toast.makeText(this, "Microphone is busy or unavailable", Toast.LENGTH_LONG).show();
            return;
        }
        final WavIO.Writer writer;
        try {
            writer = new WavIO.Writer(take.vocal(), SR);
        } catch (Exception e) {
            rec.release();
            Toast.makeText(this, "Can't write the recording: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        recording = true;
        wave.clear();
        wave.setActive(true);
        recButton.setRecording(true);
        status.setText(beat != null ? "Recording over the beat…" : "Recording…");
        if (beatPlayer != null) { beatPlayer.seek(0); beatPlayer.play(); }
        rec.startRecording();
        startedAt = System.currentTimeMillis();
        recThread = new Thread(() -> {
            short[] buf = new short[1024];
            long frames = 0;
            try {
                while (recording) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n <= 0) continue;
                    writer.write(buf, n);
                    frames += n;
                    int peak = 0;
                    for (int i = 0; i < n; i++) peak = Math.max(peak, Math.abs((int) buf[i]));
                    final float pk = peak / 32768f;
                    final long fr = frames;
                    ui.post(() -> {
                        wave.push(pk);
                        timer.setText(Ui.time(fr / (double) SR));
                    });
                    if (frames >= (long) MAX_SECONDS * SR) {
                        ui.post(() -> stopRecording(true));
                        break;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                try { rec.stop(); } catch (Exception ignored) { }
                rec.release();
                try { writer.close(); } catch (Exception ignored) { }
            }
        }, "recorder");
        recThread.start();
    }

    private void stopRecording(boolean openEditor) {
        if (!recording) return;
        recording = false;
        if (beatPlayer != null) beatPlayer.stop();
        try { if (recThread != null) recThread.join(2000); } catch (InterruptedException ignored) { }
        recButton.setRecording(false);
        wave.setActive(false);
        double secs = (System.currentTimeMillis() - startedAt) / 1000.0;
        if (secs < 0.5) {
            status.setText("Too short — tap to try again");
            return;
        }
        take.duration = take.vocal().length() / 2.0 / SR;
        if (take.hasBeat()) take.params.latencyMs = 100; // typical phone round-trip; adjustable in MIX
        TakeStore.save(take);
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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (beatPlayer != null) beatPlayer.stop();
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
