package com.totune.app.dsp;

/** Guesses the key of a sung melody (Krumhansl-Schmuckler key profiles). */
public final class KeyDetect {
    private KeyDetect() {}

    private static final double[] MAJOR = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    private static final double[] MINOR = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};

    /** @return {key, scale} with scale 0 = major, 1 = minor; null if too little singing. */
    public static int[] detect(Analysis a, float refHz) {
        double[] hist = new double[12];
        int voiced = 0;
        for (float f : a.f0) {
            if (f <= 0) continue;
            double m = Scales.hzToMidi(f, refHz);
            hist[Scales.pc((int) Math.round(m))] += 1;
            voiced++;
        }
        if (voiced < 20) return null;
        double best = -2;
        int[] res = {0, 0};
        for (int mode = 0; mode < 2; mode++) {
            double[] prof = mode == 0 ? MAJOR : MINOR;
            for (int key = 0; key < 12; key++) {
                double[] rot = new double[12];
                for (int i = 0; i < 12; i++) rot[i] = prof[((i - key) % 12 + 12) % 12];
                double c = corr(hist, rot);
                if (c > best) { best = c; res = new int[]{key, mode}; }
            }
        }
        return res;
    }

    private static double corr(double[] a, double[] b) {
        double ma = 0, mb = 0;
        for (int i = 0; i < 12; i++) { ma += a[i]; mb += b[i]; }
        ma /= 12; mb /= 12;
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < 12; i++) {
            num += (a[i] - ma) * (b[i] - mb);
            da += (a[i] - ma) * (a[i] - ma);
            db += (b[i] - mb) * (b[i] - mb);
        }
        return num / Math.sqrt(da * db + 1e-12);
    }
}
