package com.totune.app.dsp;

/** Turns a recording + settings (+ optional beat) into a finished stereo mix. */
public final class Engine {
    private Engine() {}

    public static final class Result {
        public float[][] mix;      // stereo, ready to play/export
        public float[] detMidi;    // sung pitch per frame (NaN = unvoiced)
        public float[] outMidi;    // corrected pitch per frame (NaN = unvoiced)
        public int[] targetNote;   // note the frame was tuned to (-1 = none)
        public double framesPerSecond;
    }

    /**
     * @param x    mono vocal at {@code sr}
     * @param beat stereo beat at {@code sr}, or null
     */
    public static Result render(float[] x, int sr, Analysis a, float[][] beat, Params p, Progress prog) {
        Result res = new Result();
        int frames = a.frameCount();
        boolean[] mask = Scales.mask(p.key, p.scale);
        float[] det = new float[frames], out = new float[frames];
        int[] tgt = new int[frames];
        float[] resid = new float[frames]; // expression left on top of the target note

        double hopSec = Analysis.HOP * (double) Analysis.DECIM / sr;
        double tau = p.retuneMs / 1000.0;
        double alpha = tau <= 0.0005 ? 1.0 : 1.0 - Math.exp(-hopSec / tau);
        double s = 0;
        int prev = -1;
        for (int i = 0; i < frames; i++) {
            if (a.f0[i] <= 0) {
                det[i] = out[i] = Float.NaN; tgt[i] = -1; prev = -1; s = 0;
                continue;
            }
            double m = Scales.hzToMidi(a.f0[i], p.refHz);
            int cand = Scales.nearest(m, mask);
            // hysteresis: don't flip between two notes when singing right between them
            if (prev >= 0 && cand != prev && mask[Scales.pc(prev)] && Math.abs(m - prev) < 0.65) cand = prev;
            double raw = cand - m;
            if (prev < 0) s = alpha >= 1 ? raw : 0; // note onset
            s += alpha * (raw - s);
            double tuned = p.tuneOn ? m + s * p.amount : m;
            det[i] = (float) m;
            out[i] = (float) (tuned + p.transpose);
            tgt[i] = cand;
            resid[i] = (float) (tuned - cand);
            prev = cand;
        }
        res.detMidi = det; res.outMidi = out; res.targetNote = tgt;
        res.framesPerSecond = 1.0 / hopSec;
        progress(prog, 0.05f);

        // lead vocal
        float[] lead;
        if (!p.tuneOn && Math.abs(p.transpose) < 0.01) {
            lead = x.clone();
        } else {
            float[] fr = new float[frames];
            for (int i = 0; i < frames; i++) fr[i] = Float.isNaN(det[i]) ? 1f : semis(out[i] - det[i]);
            lead = Psola.render(x, a, markRatios(a, fr));
        }
        progress(prog, 0.3f);

        int n = x.length;
        int tail = (p.reverbOn || p.delayOn) ? (int) (sr * 3.0) : (int) (sr * 0.1);
        int vLen = n + tail;
        float[] busL = new float[vLen], busR = new float[vLen];
        for (int i = 0; i < n; i++) { busL[i] = lead[i]; busR[i] = lead[i]; }
        lead = null;

        // harmonies (scale-aware)
        int[][] modes = {{}, {2}, {4}, {2, 4}, {-2}, {-100}, {100}}; // +-100 = octave
        int[] steps = modes[Math.max(0, Math.min(modes.length - 1, p.harmony))];
        for (int v = 0; v < steps.length; v++) {
            float[] fr = new float[frames];
            for (int i = 0; i < frames; i++) {
                if (tgt[i] < 0) { fr[i] = 1f; continue; }
                int h = harmonyNote(tgt[i], steps[v], p.scale, mask);
                double hm = h + resid[i] + p.transpose;
                fr[i] = semis(hm - det[i]);
            }
            float[] voice = Psola.render(x, a, markRatios(a, fr));
            float pan = steps.length == 1 ? 0.25f : (v == 0 ? -0.45f : 0.45f);
            addPanned(voice, busL, busR, p.harmonyLevel * 0.8f, pan, 0);
            progress(prog, 0.3f + 0.15f * (v + 1) / steps.length);
        }

        // doubler: two slightly detuned, slightly late copies spread left/right
        if (p.doublerOn) {
            for (int v = 0; v < 2; v++) {
                float[] fr = new float[frames];
                double sign = v == 0 ? 1 : -1;
                for (int i = 0; i < frames; i++) {
                    double t = i * hopSec;
                    double cents = sign * p.doublerDetune * (0.6 + 0.4 * Math.sin(2 * Math.PI * (0.23 + 0.11 * v) * t + v));
                    double base = Float.isNaN(det[i]) ? 0 : out[i] - det[i];
                    fr[i] = Float.isNaN(det[i]) ? 1f : semis(base + cents / 100.0);
                }
                float[] voice = Psola.render(x, a, markRatios(a, fr));
                int delay = (int) (sr * (v == 0 ? 0.017 : 0.029));
                float pan = (v == 0 ? -1 : 1) * Effects.clamp01(p.doublerWidth);
                addPanned(voice, busL, busR, p.doublerLevel * 0.7f, pan, delay);
            }
        }
        progress(prog, 0.55f);

        // EQ + compressor on the vocal bus
        if (p.eqOn) { Effects.eq(busL, sr, p); Effects.eq(busR, sr, p); }
        if (p.compOn) Effects.compress(busL, busR, sr, p);
        progress(prog, 0.65f);

        // sends (computed in place to keep memory low on phones)
        int dryLen = n + (int) (sr * 0.05);
        float[] revIn = (p.reverbOn && p.revMix > 0) ? Effects.reverbInput(busL, busR, dryLen, sr, p) : null;
        if (p.delayOn && p.delayMix > 0) Effects.addDelay(busL, busR, dryLen, sr, p);
        progress(prog, 0.75f);
        if (revIn != null) Effects.addReverb(revIn, busL, busR, sr, p);
        revIn = null;
        progress(prog, 0.92f);

        res.mix = mixWithBeat(busL, busR, beat, sr, p);
        progress(prog, 1f);
        return res;
    }

    private static float[][] mixWithBeat(float[] vl, float[] vr, float[][] beat, int sr, Params p) {
        int shift = beat != null ? (int) (sr * p.latencyMs / 1000.0) : 0;
        shift = Math.max(0, Math.min(shift, vl.length));
        if (beat == null) {
            for (int i = 0; i < vl.length; i++) { vl[i] *= p.vocalVol; vr[i] *= p.vocalVol; }
            limit(vl, vr);
            return new float[][]{vl, vr};
        }
        int vLen = vl.length - shift;
        int len = beat != null ? Math.max(vLen, beat[0].length) : vLen;
        float[] l = new float[len], r = new float[len];
        for (int i = 0; i < vLen; i++) {
            l[i] = vl[i + shift] * p.vocalVol;
            r[i] = vr[i + shift] * p.vocalVol;
        }
        if (beat != null) {
            float[] bl = beat[0], br = beat.length > 1 ? beat[1] : beat[0];
            for (int i = 0; i < bl.length; i++) { l[i] += bl[i] * p.beatVol; r[i] += br[i] * p.beatVol; }
        }
        limit(l, r);
        return new float[][]{l, r};
    }

    /** Keeps the mix out of clipping. */
    private static void limit(float[] l, float[] r) {
        float peak = 0;
        for (int i = 0; i < l.length; i++) peak = Math.max(peak, Math.max(Math.abs(l[i]), Math.abs(r[i])));
        if (peak > 0.98f) {
            float g = 0.98f / peak;
            for (int i = 0; i < l.length; i++) { l[i] *= g; r[i] *= g; }
        }
    }

    /** Interleaves a stereo float mix into 16-bit PCM (half the memory of floats). */
    public static short[] toPcm(float[][] st) {
        float[] l = st[0], r = st.length > 1 ? st[1] : st[0];
        short[] o = new short[l.length * 2];
        for (int i = 0; i < l.length; i++) {
            o[2 * i] = (short) Math.round(Math.max(-1f, Math.min(1f, l[i])) * 32767f);
            o[2 * i + 1] = (short) Math.round(Math.max(-1f, Math.min(1f, r[i])) * 32767f);
        }
        return o;
    }

    private static int harmonyNote(int target, int steps, int scale, boolean[] mask) {
        if (steps == 100) return target + 12;
        if (steps == -100) return target - 12;
        if (scale == Scales.CHROMATIC) {
            return target + (steps == 2 ? 4 : steps == 4 ? 7 : steps == -2 ? -4 : 0);
        }
        return Scales.shiftInScale(target, steps, mask);
    }

    private static void addPanned(float[] v, float[] l, float[] r, float level, float pan, int delay) {
        float gl = level * (float) Math.cos((pan + 1) * Math.PI / 4) * 1.4142f;
        float gr = level * (float) Math.sin((pan + 1) * Math.PI / 4) * 1.4142f;
        for (int i = 0; i + delay < l.length && i < v.length; i++) {
            l[i + delay] += v[i] * gl;
            r[i + delay] += v[i] * gr;
        }
    }

    private static float semis(double st) {
        return (float) Math.pow(2, st / 12.0);
    }

    /** Per-mark pitch ratios from per-frame ratios (log-interpolated). */
    private static float[] markRatios(Analysis a, float[] frameRatio) {
        float[] r = new float[a.marks.length];
        int f = frameRatio.length;
        for (int k = 0; k < r.length; k++) {
            if (f == 0) { r[k] = 1; continue; }
            double fi = a.frameIndex(a.marks[k]);
            int i0 = (int) Math.floor(fi);
            if (i0 < 0) { r[k] = frameRatio[0]; continue; }
            if (i0 >= f - 1) { r[k] = frameRatio[f - 1]; continue; }
            double t = fi - i0;
            double la = Math.log(frameRatio[i0]), lb = Math.log(frameRatio[i0 + 1]);
            boolean va = a.f0[i0] > 0, vb = a.f0[i0 + 1] > 0;
            if (va && vb) r[k] = (float) Math.exp(la + (lb - la) * t);
            else r[k] = va ? frameRatio[i0] : frameRatio[i0 + 1];
        }
        return r;
    }

    private static void progress(Progress p, float f) {
        if (p != null) p.update(f);
    }

    /** Linear-interpolation resampler (used for imported beats). */
    public static float[] resample(float[] in, int from, int to) {
        if (from == to) return in;
        int outLen = (int) ((long) in.length * to / from);
        float[] out = new float[outLen];
        double step = (double) from / to;
        for (int i = 0; i < outLen; i++) {
            double pos = i * step;
            int i0 = (int) pos;
            double t = pos - i0;
            float a = in[Math.min(i0, in.length - 1)], b = in[Math.min(i0 + 1, in.length - 1)];
            out[i] = (float) (a + (b - a) * t);
        }
        return out;
    }
}
