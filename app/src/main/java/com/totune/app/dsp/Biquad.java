package com.totune.app.dsp;

/** RBJ cookbook biquad filter (transposed direct form II). */
public final class Biquad {
    private double b0, b1, b2, a1, a2;
    private double z1, z2;

    private Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
        this.b0 = b0 / a0; this.b1 = b1 / a0; this.b2 = b2 / a0;
        this.a1 = a1 / a0; this.a2 = a2 / a0;
    }

    public float process(float x) {
        double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return (float) y;
    }

    public void process(float[] buf) {
        for (int i = 0; i < buf.length; i++) buf[i] = process(buf[i]);
    }

    private static double w(double sr, double f) {
        return 2 * Math.PI * Math.min(f, sr * 0.49) / sr;
    }

    public static Biquad lowPass(double sr, double f, double q) {
        double w = w(sr, f), c = Math.cos(w), al = Math.sin(w) / (2 * q);
        return new Biquad((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al);
    }

    public static Biquad highPass(double sr, double f, double q) {
        double w = w(sr, f), c = Math.cos(w), al = Math.sin(w) / (2 * q);
        return new Biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al);
    }

    /** Band-pass with 0 dB peak gain. */
    public static Biquad bandPass(double sr, double f, double q) {
        double w = w(sr, f), c = Math.cos(w), al = Math.sin(w) / (2 * q);
        return new Biquad(al, 0, -al, 1 + al, -2 * c, 1 - al);
    }

    public static Biquad peak(double sr, double f, double q, double gainDb) {
        double A = Math.pow(10, gainDb / 40), w = w(sr, f), c = Math.cos(w), al = Math.sin(w) / (2 * q);
        return new Biquad(1 + al * A, -2 * c, 1 - al * A, 1 + al / A, -2 * c, 1 - al / A);
    }

    public static Biquad lowShelf(double sr, double f, double gainDb) {
        double A = Math.pow(10, gainDb / 40), w = w(sr, f), c = Math.cos(w), s = Math.sin(w);
        double al = s / 2 * Math.sqrt(2), sa = 2 * Math.sqrt(A) * al;
        return new Biquad(A * ((A + 1) - (A - 1) * c + sa), 2 * A * ((A - 1) - (A + 1) * c),
                A * ((A + 1) - (A - 1) * c - sa), (A + 1) + (A - 1) * c + sa,
                -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - sa);
    }

    public static Biquad highShelf(double sr, double f, double gainDb) {
        double A = Math.pow(10, gainDb / 40), w = w(sr, f), c = Math.cos(w), s = Math.sin(w);
        double al = s / 2 * Math.sqrt(2), sa = 2 * Math.sqrt(A) * al;
        return new Biquad(A * ((A + 1) + (A - 1) * c + sa), -2 * A * ((A - 1) + (A + 1) * c),
                A * ((A + 1) + (A - 1) * c - sa), (A + 1) - (A - 1) * c + sa,
                2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - sa);
    }

    /** Magnitude response in dB at frequency f (for drawing the EQ curve). */
    public double responseDb(double sr, double f) {
        double wv = 2 * Math.PI * f / sr;
        double cr = Math.cos(wv), ci = -Math.sin(wv), c2r = Math.cos(2 * wv), c2i = -Math.sin(2 * wv);
        double nr = b0 + b1 * cr + b2 * c2r, ni = b1 * ci + b2 * c2i;
        double dr = 1 + a1 * cr + a2 * c2r, di = a1 * ci + a2 * c2i;
        double mag = Math.sqrt((nr * nr + ni * ni) / (dr * dr + di * di));
        return 20 * Math.log10(Math.max(mag, 1e-9));
    }
}
