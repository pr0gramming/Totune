package com.totune.app.dsp;

/** EQ, compressor, delay and reverb working on whole stereo buffers. */
public final class Effects {
    private Effects() {}

    // ------------------------------------------------------------------ EQ

    /** Builds the EQ filter chain for one channel (also used to draw the curve). */
    public static Biquad[] eqChain(int sr, Params p) {
        java.util.ArrayList<Biquad> list = new java.util.ArrayList<>();
        if (p.hpOn) { list.add(Biquad.highPass(sr, p.hpFreq, 0.7071)); }
        if (Math.abs(p.lowGain) > 0.05) list.add(Biquad.lowShelf(sr, p.lowFreq, p.lowGain));
        if (Math.abs(p.midGain) > 0.05) list.add(Biquad.peak(sr, p.midFreq, p.midQ, p.midGain));
        if (Math.abs(p.highGain) > 0.05) list.add(Biquad.highShelf(sr, p.highFreq, p.highGain));
        if (p.lpOn) { list.add(Biquad.lowPass(sr, p.lpFreq, 0.7071)); }
        return list.toArray(new Biquad[0]);
    }

    public static double eqResponseDb(int sr, Params p, double f) {
        double db = 0;
        for (Biquad b : eqChain(sr, p)) db += b.responseDb(sr, f);
        return db;
    }

    static void eq(float[] ch, int sr, Params p) {
        for (Biquad b : eqChain(sr, p)) b.process(ch);
    }

    // ------------------------------------------------------------------ compressor

    static void compress(float[] l, float[] r, int sr, Params p) {
        double atk = Math.exp(-1.0 / (sr * Math.max(0.1, p.compAttack) / 1000.0));
        double rel = Math.exp(-1.0 / (sr * Math.max(1, p.compRelease) / 1000.0));
        double knee = 6, slope = 1 - 1 / Math.max(1, p.compRatio);
        double g = 0; // current gain reduction in dB
        for (int i = 0; i < l.length; i++) {
            double peak = Math.max(Math.abs(l[i]), Math.abs(r[i]));
            double lvl = 20 * Math.log10(peak + 1e-9);
            double over = lvl - p.compThresh;
            double target;
            if (over <= -knee / 2) target = 0;
            else if (over >= knee / 2) target = over * slope;
            else { double o = over + knee / 2; target = slope * o * o / (2 * knee); }
            g = target > g ? atk * g + (1 - atk) * target : rel * g + (1 - rel) * target;
            float gain = (float) Math.pow(10, (p.compMakeup - g) / 20);
            l[i] *= gain;
            r[i] *= gain;
        }
    }

    // ------------------------------------------------------------------ grit (saturation)

    static void grit(float[] l, float[] r, int sr, Params p) {
        float d = clamp01(p.grit);
        float g = 1 + d * 14;
        float norm = (float) (1.0 / Math.tanh(g * 0.5)) * 0.5f; // keeps level roughly constant
        Biquad toneL = Biquad.lowPass(sr, 9000 - 4000 * d, 0.7071), toneR = Biquad.lowPass(sr, 9000 - 4000 * d, 0.7071);
        for (int i = 0; i < l.length; i++) {
            float wl = (float) Math.tanh(l[i] * g) * norm, wr = (float) Math.tanh(r[i] * g) * norm;
            l[i] = l[i] * (1 - d) + toneL.process(wl) * d;
            r[i] = r[i] * (1 - d) + toneR.process(wr) * d;
        }
    }

    // ------------------------------------------------------------------ chorus

    /** Two modulated delay lines per side, mixed in place. */
    static void chorus(float[] l, float[] r, int sr, Params p) {
        float mix = clamp01(p.chorusMix), depth = clamp01(p.chorusDepth);
        int size = (int) (sr * 0.05);
        float[] bl = new float[size], br = new float[size];
        double base = sr * 0.014, dep = sr * (0.0015 + 0.004 * depth);
        double[] rates = {0.43, 0.71};
        int w = 0;
        for (int i = 0; i < l.length; i++) {
            bl[w] = l[i];
            br[w] = r[i];
            double t = i / (double) sr;
            float wl = 0, wr = 0;
            for (int v = 0; v < 2; v++) {
                double dl = base + dep * (1 + Math.sin(2 * Math.PI * rates[v] * t + v * 2.1)) * 0.5;
                double dr = base + dep * (1 + Math.sin(2 * Math.PI * rates[v] * t + v * 2.1 + Math.PI / 2)) * 0.5;
                wl += tap(bl, w, dl, size);
                wr += tap(br, w, dr, size);
            }
            l[i] = l[i] * (1 - mix * 0.5f) + wl * 0.5f * mix;
            r[i] = r[i] * (1 - mix * 0.5f) + wr * 0.5f * mix;
            if (++w == size) w = 0;
        }
    }

    private static float tap(float[] buf, int w, double delay, int size) {
        double pos = w - delay;
        while (pos < 0) pos += size;
        int i0 = (int) pos;
        double f = pos - i0;
        int i1 = (i0 + 1) % size;
        return (float) (buf[i0] * (1 - f) + buf[i1] * f);
    }

    // ------------------------------------------------------------------ delay

    /** Adds the delay's wet signal (times mix) into l/r in place. Input is read before it is overwritten. */
    static void addDelay(float[] l, float[] r, int dryLen, int sr, Params p) {
        double beats = Params.DELAY_BEATS[Math.max(0, Math.min(Params.DELAY_BEATS.length - 1, p.delayDiv))];
        int d = (int) Math.round(60.0 / Math.max(30, p.bpm) * beats * sr);
        d = Math.max(1, Math.min(d, 4 * sr));
        float[] bl = new float[d], br = new float[d];
        float fb = Math.min(0.95f, p.delayFeedback), mix = p.delayMix;
        // darker, thinner repeats like an analog/tape delay
        Biquad lpL = Biquad.lowPass(sr, 5000, 0.7071), lpR = Biquad.lowPass(sr, 5000, 0.7071);
        Biquad hpL = Biquad.highPass(sr, 180, 0.7071), hpR = Biquad.highPass(sr, 180, 0.7071);
        int idx = 0;
        for (int i = 0; i < l.length; i++) {
            float inL = i < dryLen ? l[i] : 0, inR = i < dryLen ? r[i] : 0;
            float yl = bl[idx], yr = br[idx];
            l[i] += yl * mix;
            r[i] += yr * mix;
            float fl = hpL.process(lpL.process(yl)), fr = hpR.process(lpR.process(yr));
            if (p.pingPong) {
                bl[idx] = 0.5f * (inL + inR) + fb * fr;
                br[idx] = fb * fl;
            } else {
                bl[idx] = inL + fb * fl;
                br[idx] = inR + fb * fr;
            }
            if (++idx == d) idx = 0;
        }
    }

    // ------------------------------------------------------------------ reverb (Freeverb)

    private static final int[] COMB = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    private static final int[] ALLPASS = {556, 441, 341, 225};
    private static final int SPREAD = 23;

    /** Reverb input taken from the dry vocal (call before other effects are added in place). */
    static float[] reverbInput(float[] l, float[] r, int dryLen, int sr, Params p) {
        int pre = (int) (sr * Math.max(0, p.revPreDelay) / 1000.0);
        float[] in = new float[l.length];
        for (int i = 0; i < in.length; i++) {
            int j = i - pre;
            if (j >= 0 && j < dryLen) in[i] = (l[j] + r[j]) * 0.015f;
        }
        return in;
    }

    /** Mixes the reverb of {@code in} into l/r in place: out = out*dry + wet*mix. */
    static void addReverb(float[] in, float[] l, float[] r, int sr, Params p) {
        double scale = sr / 44100.0;
        float room = 0.7f + 0.28f * clamp01(p.revSize);
        float damp = 0.4f * clamp01(p.revDamp);
        float width = clamp01(p.revWidth), mix = p.revMix;
        float w1 = 3f * (width / 2 + 0.5f) * mix, w2 = 3f * ((1 - width) / 2) * mix;
        float dry = 1f - 0.4f * clamp01(mix);
        for (int i = 0; i < l.length; i++) { l[i] *= dry; r[i] *= dry; }
        float[] wet = channel(in, scale, 0, room, damp);
        for (int i = 0; i < l.length; i++) { l[i] += wet[i] * w1; r[i] += wet[i] * w2; }
        wet = channel(in, scale, SPREAD, room, damp);
        for (int i = 0; i < l.length; i++) { r[i] += wet[i] * w1; l[i] += wet[i] * w2; }
    }

    private static float[] channel(float[] in, double scale, int spread, float room, float damp) {
        int n = in.length;
        float[] out = new float[n];
        for (int c : COMB) {
            int len = Math.max(1, (int) ((c + spread) * scale));
            float[] buf = new float[len];
            int idx = 0;
            float store = 0;
            for (int i = 0; i < n; i++) {
                float y = buf[idx];
                store = y * (1 - damp) + store * damp;
                buf[idx] = in[i] + store * room;
                if (++idx == len) idx = 0;
                out[i] += y;
            }
        }
        for (int a : ALLPASS) {
            int len = Math.max(1, (int) ((a + spread) * scale));
            float[] buf = new float[len];
            int idx = 0;
            for (int i = 0; i < n; i++) {
                float bo = buf[idx];
                float x = out[i];
                out[i] = -x + bo;
                buf[idx] = x + bo * 0.5f;
                if (++idx == len) idx = 0;
            }
        }
        return out;
    }

    static float clamp01(float v) { return Math.max(0, Math.min(1, v)); }
}
