package com.totune.app.dsp;

/** Guesses the key of a sung melody (Krumhansl-Schmuckler key profiles). */
public final class KeyDetect {
    private KeyDetect() {}

    private static final double[] MAJOR = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    private static final double[] MINOR = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};

    /** @param sungMidi sung pitch per frame (NaN = silence). @return {key, scale (0 major, 1 minor)} or null. */
    public static int[] detect(float[] sungMidi) {
        double[] hist = new double[12];
        int voiced = 0;
        for (float m : sungMidi) {
            if (Float.isNaN(m)) continue;
            hist[Scales.pc(Math.round(m))] += 1;
            voiced++;
        }
        if (voiced < 40) return null;
        // 1) the scale must contain the notes that were actually sung (coverage)
        // 2) among equally good scales, the key profile picks the tonic
        double best = -1e9;
        int[] res = {0, 0};
        for (int mode = 0; mode < 2; mode++) {
            double[] prof = mode == 0 ? MAJOR : MINOR;
            for (int key = 0; key < 12; key++) {
                boolean[] in = Scales.mask(key, mode);
                double cover = 0;
                for (int i = 0; i < 12; i++) if (in[i]) cover += hist[i];
                cover /= voiced;
                double[] rot = new double[12];
                for (int i = 0; i < 12; i++) rot[i] = prof[((i - key) % 12 + 12) % 12];
                double score = cover * 10 + corr(hist, rot);
                if (score > best) { best = score; res = new int[]{key, mode}; }
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
