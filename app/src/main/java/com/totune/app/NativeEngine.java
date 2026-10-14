package com.totune.app;

/** The native real-time audio engine (C++ / AAudio). One instance per screen. */
public final class NativeEngine {
    static {
        System.loadLibrary("totune");
    }

    public static final int SR = 48000;

    private long h;

    public NativeEngine() {
        h = nCreate(SR);
    }

    public void release() {
        if (h != 0) { nDestroy(h); h = 0; }
    }

    public boolean alive() { return h != 0; }

    // ---- song + rack
    public void setGlobals(int key, int scale, float ref, float bpm) { nSetGlobals(h, key, scale, ref, bpm); }

    public void setRack(Rack rack) {
        Rack.Flat f = rack.flatten();
        nSetRack(h, f.ids, f.types, f.on, f.params);
    }

    public void setParam(int moduleId, int index, float value) { nSetParam(h, moduleId, index, value); }
    public void setOn(int moduleId, boolean on) { nSetOn(h, moduleId, on); }
    public int latency() { return nLatency(h); }

    // ---- playback while editing
    public void load(float[] vocal, float[] beatL, float[] beatR) { nLoad(h, vocal, beatL, beatR); }
    public void setMix(float vocalVol, float beatVol, int syncSamples) { nSetMix(h, vocalVol, beatVol, syncSamples); }
    public boolean openPlayback() { return nOpenPlayback(h); }
    public boolean openDuplex() { return nOpenDuplex(h); }
    public void close() { nClose(h); }
    public boolean needsRestart() { return nNeedsRestart(h); }
    public void play(boolean play) { nPlay(h, play); }
    public boolean isPlaying() { return nIsPlaying(h); }
    public void seek(long frame) { nSeek(h, frame); }
    public long position() { return nPosition(h); }
    public long length() { return nLength(h); }
    public void setOriginal(boolean original) { nSetOriginal(h, original); }

    // ---- recording
    public void setMonitor(boolean on) { nSetMonitor(h, on); }
    public void record(boolean rec) { nRecord(h, rec); }
    public int readRecorded(short[] buf) { return nReadRecorded(h, buf); }
    public float inputPeak() { return nInputPeak(h); }
    public long recordedFrames() { return nRecFrames(h); }
    public float roundTripMs() { return nRoundTripMs(h); }

    // ---- offline
    public static short[] render(float[] vocal, float[] bl, float[] br, int key, int scale, float ref, float bpm,
                                 Rack rack, float vocalVol, float beatVol, int sync) {
        Rack.Flat f = rack.flatten();
        return nRender(SR, vocal, bl, br, key, scale, ref, bpm, f.ids, f.types, f.on, f.params, vocalVol, beatVol, sync);
    }

    /** Returns {sung[], corrected[]} in MIDI per 256-sample hop (NaN = silence). */
    public static float[][] analyze(float[] vocal, int key, int scale, float ref, float[] tuneParams) {
        float[] t = tuneParams;
        float[] r = nAnalyze(SR, vocal, key, scale, ref, (int) t[0], t[1], t[2] / 100f, t[3] / 100f, t[4], (int) t[6]);
        int n = r.length / 2;
        float[] a = new float[n], b = new float[n];
        System.arraycopy(r, 0, a, 0, n);
        System.arraycopy(r, n, b, 0, n);
        return new float[][]{a, b};
    }

    private static native long nCreate(int sr);
    private static native void nDestroy(long h);
    private static native void nSetGlobals(long h, int key, int scale, float ref, float bpm);
    private static native void nSetRack(long h, int[] ids, int[] types, boolean[] on, float[] params);
    private static native void nSetParam(long h, int id, int idx, float v);
    private static native void nSetOn(long h, int id, boolean on);
    private static native int nLatency(long h);
    private static native void nLoad(long h, float[] vocal, float[] bl, float[] br);
    private static native void nSetMix(long h, float vv, float bv, int sync);
    private static native boolean nOpenPlayback(long h);
    private static native boolean nOpenDuplex(long h);
    private static native void nClose(long h);
    private static native boolean nNeedsRestart(long h);
    private static native void nPlay(long h, boolean play);
    private static native boolean nIsPlaying(long h);
    private static native void nSeek(long h, long f);
    private static native long nPosition(long h);
    private static native long nLength(long h);
    private static native void nSetOriginal(long h, boolean o);
    private static native void nSetMonitor(long h, boolean on);
    private static native void nRecord(long h, boolean rec);
    private static native int nReadRecorded(long h, short[] buf);
    private static native float nInputPeak(long h);
    private static native long nRecFrames(long h);
    private static native float nRoundTripMs(long h);
    private static native short[] nRender(int sr, float[] vocal, float[] bl, float[] br, int key, int scale, float ref, float bpm,
                                          int[] ids, int[] types, boolean[] on, float[] params, float vv, float bv, int sync);
    private static native float[] nAnalyze(int sr, float[] vocal, int key, int scale, float ref,
                                           int style, float speed, float amount, float keep, float transpose, int range);
}
