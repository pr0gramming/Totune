package com.totune.app.dsp;

/**
 * TD-PSOLA pitch shifter. Keeps the timing of the recording intact and moves
 * pitch by re-spacing two-period grains taken at the analysis pitch marks.
 */
final class Psola {
    private Psola() {}

    /**
     * @param ratio pitch ratio for every analysis mark (output f0 / input f0).
     *              Ignored for unvoiced marks.
     */
    static float[] render(float[] x, Analysis a, float[] ratio) {
        int n = x.length;
        float[] acc = new float[n];
        float[] ws = new float[n];
        int m = a.marks.length;
        if (m == 0) return x.clone();

        // Hann tables are cached per grain half-length
        java.util.HashMap<Integer, float[]> windows = new java.util.HashMap<>();

        double ts = a.marks[0];
        int k = 0;
        while (ts < n) {
            while (k + 1 < m && a.marks[k + 1] <= ts) k++;
            int kk = k;
            if (k + 1 < m && (a.marks[k + 1] - ts) < (ts - a.marks[k])) kk = k + 1;

            int T = a.periods[kk];
            double r = a.markVoiced[kk] ? ratio[kk] : 1.0;
            if (!(r > 0.25 && r < 4)) r = 1.0;
            int c = a.marks[kk];
            int s = (int) Math.round(ts);
            float g = (float) (r < 1 ? 1.0 / r : 1.0);

            float[] win = windows.get(T);
            if (win == null) {
                win = new float[2 * T + 1];
                for (int j = 0; j <= 2 * T; j++) {
                    win[j] = (float) (0.5 - 0.5 * Math.cos(Math.PI * j / T));
                }
                windows.put(T, win);
            }
            int jLo = Math.max(-T, Math.max(-c, -s));
            int jHi = Math.min(T, Math.min(n - 1 - c, n - 1 - s));
            for (int j = jLo; j <= jHi; j++) {
                float w = win[j + T];
                acc[s + j] += x[c + j] * w * g;
                ws[s + j] += w;
            }
            ts += T / r;
        }
        // the first grain only covers half its window; leave the leading samples untouched
        for (int i = 0; i < n; i++) {
            float w = ws[i];
            acc[i] = w > 1f ? acc[i] / w : acc[i];
        }
        int start = a.marks[0];
        for (int i = 0; i < Math.min(start, n); i++) acc[i] = x[i];
        return acc;
    }
}
