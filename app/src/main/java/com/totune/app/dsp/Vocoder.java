package com.totune.app.dsp;

/**
 * Channel vocoder. The (tuned) voice shapes a synth carrier band by band.
 * The carrier is built from the take's own pitch track and key, so no
 * keyboard is needed: chords from the scale, the sung melody, a fixed
 * monotone robot note, or the melody an octave down.
 */
final class Vocoder {
    private Vocoder() {}

    static final int CHORDS = 0, MELODY = 1, MONOTONE = 2, DEEP = 3;
    private static final int BANDS = 16;
    private static final double F_LO = 180, F_HI = 7000;

    /**
     * @param voice     modulator (tuned lead), mono
     * @param outMidi   tuned pitch per analysis frame (NaN = unvoiced)
     * @param target    note per frame (-1 = none)
     * @param framePos  maps sample index -> fractional frame index
     */
    static float[] process(float[] voice, int sr, float[] outMidi, int[] target, FrameMap framePos,
                           Params p, boolean[] mask) {
        int n = voice.length;
        float[] carrier = carrier(n, sr, outMidi, target, framePos, p, mask);

        float[] out = new float[n];
        double ratio = Math.pow(F_HI / F_LO, 1.0 / (BANDS - 1));
        double q = 1.0 / (ratio - 1.0 / ratio) * 0.9; // wide, overlapping bands (formants only, not single harmonics)
        double atk = 1 - Math.exp(-1.0 / (sr * 0.004));
        double rel = 1 - Math.exp(-1.0 / (sr * 0.035));
        for (int b = 0; b < BANDS; b++) {
            double fc = F_LO * Math.pow(ratio, b);
            Biquad mf = Biquad.bandPass(sr, fc, q), mf2 = Biquad.bandPass(sr, fc, q);
            Biquad cf = Biquad.bandPass(sr, fc, q), cf2 = Biquad.bandPass(sr, fc, q);
            double env = 0;
            // higher bands get a little lift so the robot stays intelligible
            float tilt = (float) (0.7 + 0.6 * b / (BANDS - 1.0));
            for (int i = 0; i < n; i++) {
                float m = mf2.process(mf.process(voice[i]));
                double a = Math.abs(m);
                env += (a > env ? atk : rel) * (a - env);
                float c = cf2.process(cf.process(carrier[i]));
                out[i] += (float) (c * env) * tilt;
            }
        }
        // keep consonants (s, t, ch) from the real voice
        Biquad hp1 = Biquad.highPass(sr, 5500, 0.7071), hp2 = Biquad.highPass(sr, 5500, 0.7071);
        float[] air = new float[n];
        for (int i = 0; i < n; i++) air[i] = hp2.process(hp1.process(voice[i]));

        // match loudness to the dry voice
        double ev = 0, eo = 0;
        for (int i = 0; i < n; i++) { ev += voice[i] * voice[i]; eo += out[i] * out[i]; }
        float g = eo > 1e-12 ? (float) Math.sqrt(ev / eo) : 0f;
        for (int i = 0; i < n; i++) out[i] = out[i] * g + air[i] * 0.6f;
        return out;
    }

    interface FrameMap { double frameAt(int sample); }

    /** Synth carrier: detuned PolyBLEP saws following the chosen note source. */
    private static float[] carrier(int n, int sr, float[] outMidi, int[] target, FrameMap fm,
                                   Params p, boolean[] mask) {
        int frames = outMidi.length;
        // build per-frame note sets
        int voices = p.vocoderMode == CHORDS ? 4 : 2;
        double[][] notes = new double[frames][voices];
        double[] last = null;
        int root = 48 + p.key; // monotone note (C3..B3)
        for (int f = 0; f < frames; f++) {
            double[] set = null;
            boolean voiced = !Float.isNaN(outMidi[f]) && target[f] >= 0;
            switch (p.vocoderMode) {
                case CHORDS:
                    if (voiced) {
                        int t = target[f] + Math.round(p.transpose);
                        int third = p.scale == Scales.CHROMATIC ? t + 4 : Scales.shiftInScale(t, 2, mask);
                        int fifth = p.scale == Scales.CHROMATIC ? t + 7 : Scales.shiftInScale(t, 4, mask);
                        set = new double[]{t, third, fifth, t - 12};
                    }
                    break;
                case MONOTONE:
                    set = new double[]{root, root - 12};
                    break;
                case DEEP:
                    if (voiced) set = new double[]{outMidi[f] - 12, outMidi[f] - 24};
                    break;
                default: // MELODY
                    if (voiced) set = new double[]{outMidi[f], outMidi[f] - 12};
            }
            if (set == null) set = last != null ? last : new double[voices];
            if (last == null && set != null && voiced) {
                // fill the silence before the first note with the first chord
                for (int k = 0; k < f; k++) notes[k] = set;
            }
            notes[f] = set;
            if (voiced || p.vocoderMode == MONOTONE) last = set;
        }
        if (last == null) { // nothing sung: drone on the key root
            double[] d = new double[voices];
            for (int v = 0; v < voices; v++) d[v] = root - 12 * (v % 2);
            for (int f = 0; f < frames; f++) notes[f] = d;
        }

        float[] c = new float[n];
        double[] phase = new double[voices * 2];
        double[] curHz = new double[voices];
        double glide = 1 - Math.exp(-1.0 / (sr * 0.012));
        double ref = p.refHz;
        float amp = 0.5f / voices;
        for (int i = 0; i < n; i++) {
            int f = (int) Math.round(fm.frameAt(i));
            f = Math.max(0, Math.min(frames - 1, f));
            double[] set = frames > 0 ? notes[f] : new double[voices];
            float s = 0;
            for (int v = 0; v < voices; v++) {
                double hz = ref * Math.pow(2, (set[v] - 69) / 12.0);
                if (curHz[v] == 0) curHz[v] = hz;
                curHz[v] += glide * (hz - curHz[v]);
                for (int d = 0; d < 2; d++) {
                    double det = d == 0 ? 0.9965 : 1.0035; // +-6 cents for a thick synth
                    double inc = curHz[v] * det / sr;
                    int k = v * 2 + d;
                    phase[k] += inc;
                    if (phase[k] >= 1) phase[k] -= 1;
                    s += saw(phase[k], inc);
                }
            }
            c[i] = s * amp;
        }
        return c;
    }

    /** Band-limited sawtooth (PolyBLEP). */
    private static float saw(double t, double dt) {
        double v = 2 * t - 1;
        if (t < dt) { double x = t / dt; v -= x + x - x * x - 1; }
        else if (t > 1 - dt) { double x = (t - 1) / dt; v -= x * x + x + x + 1; }
        return (float) v;
    }
}
