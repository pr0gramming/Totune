package com.totune.app.dsp;

/**
 * Pitch analysis of a mono recording: YIN pitch tracking (FFT accelerated)
 * plus pitch marks for PSOLA. Depends only on the recording, so it is
 * computed once per take and reused for every re-render.
 */
public final class Analysis {
    public static final int DECIM = 2;          // pitch tracking runs at sr / 2
    public static final int N = 1024;           // analysis frame (decimated samples)
    public static final int W = N / 2;          // YIN integration window
    public static final int HOP = 256;          // decimated hop -> 512 original samples
    private static final double MIN_HZ = 65, MAX_HZ = 1100;
    private static final double YIN_THRESH = 0.15;
    static final int UNVOICED_PERIOD = 256;     // grain half-size in unvoiced parts

    public final int sr;
    public final int length;
    /** Detected fundamental per frame in Hz, 0 when unvoiced. */
    public final float[] f0;
    /** PSOLA analysis marks (sample index), their local period, and voicing. */
    public final int[] marks;
    public final int[] periods;
    public final boolean[] markVoiced;

    private Analysis(int sr, int length, float[] f0, int[] marks, int[] periods, boolean[] mv) {
        this.sr = sr; this.length = length; this.f0 = f0;
        this.marks = marks; this.periods = periods; this.markVoiced = mv;
    }

    public int frameCount() { return f0.length; }

    /** Original-sample position of a frame's centre. */
    public double frameTime(int i) {
        return (i * (double) HOP + W * 0.75) * DECIM;
    }

    /** Fractional frame index for an original-sample position. */
    public double frameIndex(double t) {
        return (t / DECIM - W * 0.75) / HOP;
    }

    /** Interpolated f0 (Hz) at an original-sample position; 0 if unvoiced. */
    public double f0At(double t) {
        double fi = frameIndex(t);
        int i0 = (int) Math.floor(fi);
        if (i0 < 0) return f0.length > 0 ? f0[0] : 0;
        if (i0 >= f0.length - 1) return f0.length > 0 ? f0[f0.length - 1] : 0;
        float a = f0[i0], b = f0[i0 + 1];
        if (a <= 0 || b <= 0) return (fi - i0 < 0.5) ? a : b;
        double frac = fi - i0;
        return a + (b - a) * frac;
    }

    public static Analysis run(float[] x, int sr, Progress progress) {
        float[] d = decimate(x, sr);
        float[] f0 = trackPitch(d, sr / (double) DECIM, progress);
        cleanup(f0);
        if (progress != null) progress.update(0.95f);
        return buildMarks(x, sr, f0);
    }

    // ------------------------------------------------------------------ pitch tracking

    private static float[] decimate(float[] x, int sr) {
        // two cascaded biquad low-passes at ~0.4 of the new Nyquist, then keep every 2nd sample
        Biquad a = Biquad.lowPass(sr, sr * 0.2, 0.7071);
        Biquad b = Biquad.lowPass(sr, sr * 0.2, 0.7071);
        float[] out = new float[x.length / DECIM];
        for (int i = 0, j = 0; i < x.length; i++) {
            float v = b.process(a.process(x[i]));
            if ((i % DECIM) == 0 && j < out.length) out[j++] = v;
        }
        return out;
    }

    private static float[] trackPitch(float[] d, double dsr, Progress progress) {
        int frames = Math.max(0, (d.length - N) / HOP + 1);
        float[] f0 = new float[frames];
        if (frames == 0) return f0;

        int tauMin = (int) Math.floor(dsr / MAX_HZ);
        int tauMax = Math.min(W - 1, (int) Math.ceil(dsr / MIN_HZ));
        int fftN = 2 * N;
        FFT fft = new FFT(fftN);
        double[] re = new double[fftN], im = new double[fftN];
        double[] pre = new double[N + 1];
        double[] diff = new double[W];
        double[] pr = new double[fftN], pi = new double[fftN];

        // loudness gate relative to the loudest frame
        double maxRms = 1e-9;
        double[] rms = new double[frames];
        for (int f = 0; f < frames; f++) {
            double s = 0;
            int o = f * HOP;
            for (int j = 0; j < N; j++) s += d[o + j] * d[o + j];
            rms[f] = Math.sqrt(s / N);
            maxRms = Math.max(maxRms, rms[f]);
        }
        double gate = Math.max(maxRms * 0.03, 0.002);

        for (int f = 0; f < frames; f++) {
            if (progress != null && (f & 255) == 0) progress.update(0.9f * f / frames);
            if (rms[f] < gate) { f0[f] = 0; continue; }
            int o = f * HOP;
            for (int j = 0; j < fftN; j++) {
                re[j] = j < N ? d[o + j] : 0;
                im[j] = j < W ? d[o + j] : 0;
            }
            fft.transform(re, im, false);
            // unpack two real spectra A (frame) and B (first W samples): P = A * conj(B)
            for (int k = 0; k < fftN; k++) {
                int m = (fftN - k) % fftN;
                double ar = (re[k] + re[m]) * 0.5, ai = (im[k] - im[m]) * 0.5;
                double br = (im[k] + im[m]) * 0.5, bi = -(re[k] - re[m]) * 0.5;
                pr[k] = ar * br + ai * bi;
                pi[k] = ai * br - ar * bi;
            }
            fft.transform(pr, pi, true); // pr[tau] = sum_j x[j+tau] * x[j]

            pre[0] = 0;
            for (int j = 0; j < N; j++) pre[j + 1] = pre[j] + d[o + j] * (double) d[o + j];
            double e1 = pre[W];
            diff[0] = 0;
            for (int tau = 1; tau < W; tau++) {
                double e2 = pre[tau + W] - pre[tau];
                diff[tau] = Math.max(0, e1 + e2 - 2 * pr[tau]);
            }
            // cumulative mean normalised difference
            double run = 0;
            int best = -1;
            double bestVal = Double.MAX_VALUE;
            double[] cm = diff; // reuse in place
            cm[0] = 1;
            for (int tau = 1; tau <= tauMax; tau++) {
                run += diff[tau];
                cm[tau] = run > 0 ? diff[tau] * tau / run : 1;
            }
            for (int tau = tauMin; tau <= tauMax; tau++) {
                if (cm[tau] < YIN_THRESH) {
                    while (tau + 1 <= tauMax && cm[tau + 1] < cm[tau]) tau++;
                    best = tau; bestVal = cm[tau];
                    break;
                }
            }
            if (best < 0) { f0[f] = 0; continue; }
            double t = best;
            if (best > 1 && best < tauMax) {
                double a = cm[best - 1], b = cm[best], c = cm[best + 1];
                double den = a - 2 * b + c;
                if (Math.abs(den) > 1e-12) t = best + 0.5 * (a - c) / den;
            }
            f0[f] = bestVal < 0.5 ? (float) (dsr / t) : 0;
        }
        return f0;
    }

    /** Median smoothing, octave-jump repair, gap filling and removal of tiny islands. */
    private static void cleanup(float[] f0) {
        int n = f0.length;
        if (n == 0) return;
        float[] src = f0.clone();
        double[] win = new double[5];
        for (int i = 0; i < n; i++) {
            if (src[i] <= 0) continue;
            int c = 0;
            for (int k = i - 2; k <= i + 2; k++) {
                if (k >= 0 && k < n && src[k] > 0) win[c++] = Math.log(src[k]);
            }
            java.util.Arrays.sort(win, 0, c);
            double med = win[c / 2];
            double cur = Math.log(src[i]);
            // more than ~9 semitones away from the neighbourhood = tracking error
            if (Math.abs(cur - med) > Math.log(2) * 0.75) f0[i] = (float) Math.exp(med);
        }
        // fill short unvoiced gaps (<= 3 frames) between voiced frames
        int i = 0;
        while (i < n) {
            if (f0[i] > 0) { i++; continue; }
            int s = i;
            while (i < n && f0[i] <= 0) i++;
            int len = i - s;
            if (s > 0 && i < n && len <= 3) {
                double a = Math.log(f0[s - 1]), b = Math.log(f0[i]);
                if (Math.abs(a - b) < Math.log(2) * 0.25) {
                    for (int k = 0; k < len; k++) {
                        f0[s + k] = (float) Math.exp(a + (b - a) * (k + 1) / (len + 1));
                    }
                }
            }
        }
        // drop voiced islands shorter than 3 frames
        i = 0;
        while (i < n) {
            if (f0[i] <= 0) { i++; continue; }
            int s = i;
            while (i < n && f0[i] > 0) i++;
            if (i - s < 3) for (int k = s; k < i; k++) f0[k] = 0;
        }
    }

    // ------------------------------------------------------------------ pitch marks

    private static Analysis buildMarks(float[] x, int sr, float[] f0) {
        Analysis tmp = new Analysis(sr, x.length, f0, new int[0], new int[0], new boolean[0]);
        int n = x.length;
        int cap = n / 40 + 16;
        int[] marks = new int[cap], per = new int[cap];
        boolean[] mv = new boolean[cap];
        int count = 0;
        int t = 0;
        boolean prevVoiced = false;
        while (t < n) {
            double f = tmp.f0At(t);
            int T;
            boolean voiced = f > 0;
            int pos = t;
            if (voiced) {
                T = Math.max(20, (int) Math.round(sr / f));
                int lo, hi;
                if (!prevVoiced) { lo = t; hi = Math.min(n - 1, t + T); }
                else { lo = Math.max(0, t - T / 4); hi = Math.min(n - 1, t + T / 4); }
                if (count > 0) lo = Math.max(lo, marks[count - 1] + T / 2);
                if (lo <= hi) {
                    float best = -Float.MAX_VALUE;
                    for (int k = lo; k <= hi; k++) if (x[k] > best) { best = x[k]; pos = k; }
                } else {
                    pos = Math.min(n - 1, lo);
                }
            } else {
                T = UNVOICED_PERIOD;
            }
            if (count == cap) {
                cap *= 2;
                marks = java.util.Arrays.copyOf(marks, cap);
                per = java.util.Arrays.copyOf(per, cap);
                mv = java.util.Arrays.copyOf(mv, cap);
            }
            marks[count] = pos; per[count] = T; mv[count] = voiced;
            count++;
            prevVoiced = voiced;
            t = pos + T;
        }
        return new Analysis(sr, n, f0,
                java.util.Arrays.copyOf(marks, count),
                java.util.Arrays.copyOf(per, count),
                java.util.Arrays.copyOf(mv, count));
    }
}
