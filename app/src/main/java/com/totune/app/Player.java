package com.totune.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Streams stereo float buffers to the speaker; can swap buffers (A/B, re-render) mid-playback. */
public class Player {
    public interface Listener { void onEnded(); }

    /** Something that can produce interleaved stereo 16-bit frames. */
    public interface Source {
        int length();
        void fill(int pos, short[] out, int frames);
    }

    public static Source of(final short[] interleaved) {
        return new Source() {
            public int length() { return interleaved.length / 2; }
            public void fill(int pos, short[] out, int frames) {
                System.arraycopy(interleaved, pos * 2, out, 0, frames * 2);
            }
        };
    }

    private static final int CHUNK = 2048;
    private volatile Source main, alt;
    private volatile boolean useAlt;
    private volatile int pos;
    private volatile boolean playing;
    private Thread thread;
    private AudioTrack track;
    private Listener listener;
    private final int sr;

    public Player(int sr) { this.sr = sr; }

    public void setListener(Listener l) { listener = l; }

    public void setSources(Source main, Source alt) {
        this.main = main;
        this.alt = alt;
    }

    public void setUseAlt(boolean b) { useAlt = b; }

    public boolean isPlaying() { return playing; }

    public double positionSec() { return pos / (double) sr; }

    public void seek(double sec) { pos = (int) Math.max(0, sec * sr); }

    public int lengthFrames() {
        Source b = useAlt && alt != null ? alt : main;
        return b == null ? 0 : b.length();
    }

    public synchronized void play() {
        if (playing || main == null) return;
        if (pos >= lengthFrames()) pos = 0;
        int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(Math.max(min, CHUNK * 4 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        playing = true;
        track.play();
        final AudioTrack t = track;
        thread = new Thread(() -> loop(t), "player");
        thread.start();
    }

    private void loop(AudioTrack t) {
        short[] buf = new short[CHUNK * 2];
        boolean ended = false;
        while (playing) {
            Source b = useAlt && alt != null ? alt : main;
            if (b == null) break;
            int len = b.length();
            int p = pos;
            if (p >= len) { ended = true; break; }
            int n = Math.min(CHUNK, len - p);
            b.fill(p, buf, n);
            t.write(buf, 0, n * 2);
            if (pos == p) pos = p + n; // unless someone seeked meanwhile
        }
        try {
            if (ended) {
                // let the tail drain before stopping
                t.stop();
            } else {
                t.pause();
                t.flush();
            }
        } catch (Exception ignored) {
        }
        t.release();
        if (ended) {
            playing = false;
            pos = 0;
            if (listener != null) listener.onEnded();
        }
    }

    public synchronized void stop() {
        playing = false;
        if (thread != null) {
            try { thread.join(500); } catch (InterruptedException ignored) { }
            thread = null;
        }
    }

}
