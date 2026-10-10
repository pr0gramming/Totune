package com.totune.app.dsp;

/** Every user-adjustable setting. Plain fields so presets can copy them easily. */
public final class Params implements Cloneable {
    // Tune
    public boolean tuneOn = true;
    public int key = 0;            // 0 = C
    public int scale = 0;          // index into Scales.NAMES
    public float refHz = 440f;     // A4 reference
    public float retuneMs = 20f;   // 0 = hard robotic snap, 400 = slow/natural
    public float amount = 1f;      // 0..1 correction strength
    public float transpose = 0f;   // semitones

    // Voices
    public int harmony = 0;        // index into HARMONY_NAMES
    public float harmonyLevel = 0.5f;
    public boolean doublerOn = false;
    public float doublerLevel = 0.5f;
    public float doublerDetune = 10f; // cents
    public float doublerWidth = 1f;   // 0..1 stereo spread

    // Vocoder
    public boolean vocoderOn = false;
    public int vocoderMode = 0;       // index into VOCODER_MODES
    public float vocoderMix = 0.8f;   // 0 = dry voice, 1 = full robot

    // Grit (saturation)
    public boolean gritOn = false;
    public float grit = 0.3f;

    // Chorus
    public boolean chorusOn = false;
    public float chorusMix = 0.35f;
    public float chorusDepth = 0.5f;

    // EQ
    public boolean eqOn = true;
    public boolean hpOn = true;
    public float hpFreq = 80f;
    public boolean lpOn = false;
    public float lpFreq = 16000f;
    public float lowFreq = 200f, lowGain = 0f;
    public float midFreq = 1500f, midGain = 0f, midQ = 1f;
    public float highFreq = 6000f, highGain = 0f;

    // Compressor
    public boolean compOn = true;
    public float compThresh = -18f;
    public float compRatio = 3f;
    public float compAttack = 10f;   // ms
    public float compRelease = 120f; // ms
    public float compMakeup = 3f;    // dB

    // Delay
    public boolean delayOn = false;
    public float bpm = 90f;
    public int delayDiv = 3;         // index into DELAY_DIVS
    public float delayFeedback = 0.3f;
    public float delayMix = 0.2f;
    public boolean pingPong = true;

    // Reverb
    public boolean reverbOn = true;
    public float revSize = 0.6f;
    public float revDamp = 0.4f;
    public float revMix = 0.18f;
    public float revPreDelay = 20f; // ms
    public float revWidth = 1f;

    // Mix
    public float vocalVol = 1f;
    public float beatVol = 0.8f;
    public float latencyMs = 0f;    // shifts vocal earlier to line up with the beat

    public static final String[] HARMONY_NAMES = {
            "Off", "3rd up", "5th up", "3rd + 5th up", "3rd down", "Octave down", "Octave up"};

    public static final String[] VOCODER_MODES = {"Chords", "Melody robot", "Monotone", "Deep robot"};

    public static final String[] DELAY_DIVS = {
            "1/2", "1/4", "1/4 dotted", "1/8", "1/8 dotted", "1/8 triplet", "1/16"};
    public static final double[] DELAY_BEATS = {2, 1, 1.5, 0.5, 0.75, 1.0 / 3.0, 0.25};

    @Override
    public Params clone() {
        try {
            return (Params) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }
}
