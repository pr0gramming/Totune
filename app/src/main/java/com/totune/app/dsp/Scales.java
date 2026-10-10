package com.totune.app.dsp;

/** Keys, scales and note helpers. Pure Java, no Android dependencies. */
public final class Scales {
    private Scales() {}

    public static final String[] NOTE_NAMES = {
            "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    public static final String[] NAMES = {
            "Major", "Minor", "Chromatic", "Harmonic minor", "Major pentatonic",
            "Minor pentatonic", "Blues", "Dorian", "Phrygian", "Mixolydian"};

    public static final int CHROMATIC = 2;

    private static final int[][] STEPS = {
            {0, 2, 4, 5, 7, 9, 11},
            {0, 2, 3, 5, 7, 8, 10},
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11},
            {0, 2, 3, 5, 7, 8, 11},
            {0, 2, 4, 7, 9},
            {0, 3, 5, 7, 10},
            {0, 3, 5, 6, 7, 10},
            {0, 2, 3, 5, 7, 9, 10},
            {0, 1, 3, 5, 7, 8, 10},
            {0, 2, 4, 5, 7, 9, 10}};

    /** Which of the 12 pitch classes are allowed for this key and scale. */
    public static boolean[] mask(int key, int scale) {
        boolean[] m = new boolean[12];
        int s = Math.max(0, Math.min(STEPS.length - 1, scale));
        for (int step : STEPS[s]) m[(key + step) % 12] = true;
        return m;
    }

    public static int pc(int midi) {
        return ((midi % 12) + 12) % 12;
    }

    /** Nearest allowed MIDI note to a fractional MIDI pitch. */
    public static int nearest(double midi, boolean[] mask) {
        int lo = (int) Math.floor(midi) - 7;
        int hi = (int) Math.ceil(midi) + 7;
        int best = (int) Math.round(midi);
        double bestD = Double.MAX_VALUE;
        for (int n = lo; n <= hi; n++) {
            if (!mask[pc(n)]) continue;
            double d = Math.abs(n - midi);
            if (d < bestD) { bestD = d; best = n; }
        }
        return best;
    }

    /** Move an in-scale note by a number of scale steps (positive = up). */
    public static int shiftInScale(int note, int steps, boolean[] mask) {
        int n = note;
        int dir = steps >= 0 ? 1 : -1;
        for (int i = 0; i < Math.abs(steps); i++) {
            int guard = 0;
            do { n += dir; guard++; } while (!mask[pc(n)] && guard < 12);
        }
        return n;
    }

    public static double hzToMidi(double hz, double refA4) {
        return 69.0 + 12.0 * Math.log(hz / refA4) / Math.log(2.0);
    }

    public static String noteName(int midi) {
        return NOTE_NAMES[pc(midi)] + (Math.floorDiv(midi, 12) - 1);
    }
}
