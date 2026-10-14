package com.totune.app;

import java.util.Locale;

/** Every rack module type and its parameters. Order of params must match modules.h. */
public final class ModuleType {
    public static final int TUNE = 0, HARMONY = 1, VOCODER = 2, DOUBLER = 3, EQ = 4, COMP = 5, GRIT = 6,
            CHORUS = 7, DELAY = 8, REVERB = 9;
    public static final int MAX_PARAMS = 16;

    public enum Kind { KNOB, CHOICE, TOGGLE }

    public interface Fmt { String f(float v); }

    public static final class Param {
        public final String name;
        public final Kind kind;
        public final float min, max, def, step;
        public final boolean log;
        public final String[] choices;
        public final Fmt fmt;

        Param(String name, Kind kind, float min, float max, float def, boolean log, float step, String[] choices, Fmt fmt) {
            this.name = name; this.kind = kind; this.min = min; this.max = max; this.def = def;
            this.log = log; this.step = step; this.choices = choices; this.fmt = fmt;
        }

        public String format(float v) {
            if (kind == Kind.CHOICE) return choices[Math.max(0, Math.min(choices.length - 1, Math.round(v)))];
            if (kind == Kind.TOGGLE) return v > 0.5f ? "On" : "Off";
            return fmt.f(v);
        }
    }

    public final int id;
    public final String name, blurb;
    public final Param[] params;
    public final int main;   // parameter shown on the card's knob

    private ModuleType(int id, String name, String blurb, int main, Param... params) {
        this.id = id; this.name = name; this.blurb = blurb; this.main = main; this.params = params;
    }

    public float[] defaults() {
        float[] d = new float[MAX_PARAMS];
        for (int i = 0; i < params.length; i++) d[i] = params[i].def;
        return d;
    }

    // ------------------------------------------------------------------ helpers

    private static Param knob(String n, float min, float max, float def, Fmt f) {
        return new Param(n, Kind.KNOB, min, max, def, false, 1, null, f);
    }
    private static Param knobStep(String n, float min, float max, float def, float step, Fmt f) {
        return new Param(n, Kind.KNOB, min, max, def, false, step, null, f);
    }
    private static Param logKnob(String n, float min, float max, float def, Fmt f) {
        return new Param(n, Kind.KNOB, min, max, def, true, 0, null, f);
    }
    private static Param choice(String n, float def, String... c) {
        return new Param(n, Kind.CHOICE, 0, c.length - 1, def, false, 1, c, null);
    }
    private static Param toggle(String n, boolean def) {
        return new Param(n, Kind.TOGGLE, 0, 1, def ? 1 : 0, false, 1, null, null);
    }

    static Fmt pct() { return v -> String.format(Locale.US, "%.0f%%", v); }
    static Fmt db() { return v -> String.format(Locale.US, "%+.1f dB", v); }
    static Fmt ms() { return v -> String.format(Locale.US, "%.0f ms", v); }
    static Fmt hz() { return v -> v >= 1000 ? String.format(Locale.US, "%.1fk", v / 1000) : String.format(Locale.US, "%.0f Hz", v); }
    static Fmt f1(String unit) { return v -> String.format(Locale.US, "%.1f%s", v, unit); }

    public static final String[] STYLES = {"Tuny", "Crisp", "Wavy"};
    public static final String[] RANGES = {"Low voice", "Mid voice", "High voice"};
    public static final String[] HARMONY_VOICES = {"3rd up", "5th up", "3rd + 5th up", "3rd down", "Octave down", "Octave up"};
    public static final String[] VOCODER_MODES = {"Chords", "Melody robot", "Monotone", "Deep robot"};
    public static final String[] DELAY_DIVS = {"1/2", "1/4", "1/4 dotted", "1/8", "1/8 dotted", "1/8 triplet", "1/16"};

    public static final ModuleType[] ALL = {
            new ModuleType(TUNE, "Autotune", "Pitch correction · Tuny, Crisp or Wavy", 1,
                    choice("Style", 0, STYLES),
                    knob("Retune speed", 0, 400, 20, v -> v < 1 ? "Robot" : String.format(Locale.US, "%.0f ms", v)),
                    knob("Amount", 0, 100, 100, pct()),
                    knob("Keep vibrato", 0, 100, 0, pct()),
                    knobStep("Transpose", -12, 12, 0, 1, v -> String.format(Locale.US, "%+.0f st", v)),
                    knob("Mix", 0, 100, 100, pct()),
                    choice("Voice range", 1, RANGES)),
            new ModuleType(HARMONY, "Harmony", "Extra voices that follow your key", 1,
                    choice("Voices", 2, HARMONY_VOICES),
                    knob("Level", 0, 100, 50, pct()),
                    knob("Width", 0, 100, 70, pct()),
                    knob("Tightness", 0, 100, 80, pct()),
                    choice("Voice range", 1, RANGES)),
            new ModuleType(VOCODER, "Vocoder", "Your voice plays a synth", 1,
                    choice("Mode", 1, VOCODER_MODES),
                    knob("Robot mix", 0, 100, 80, pct()),
                    knob("Air", 0, 100, 60, pct())),
            new ModuleType(DOUBLER, "Doubler", "Two wide, slightly detuned copies", 0,
                    knob("Level", 0, 100, 50, pct()),
                    knob("Detune", 0, 30, 10, v -> String.format(Locale.US, "%.0f ct", v)),
                    knob("Width", 0, 100, 100, pct()),
                    knob("Delay", 5, 40, 18, ms())),
            new ModuleType(EQ, "EQ", "Cuts, shelves and a mid band", 7,
                    toggle("Low cut", true),
                    logKnob("Low cut Hz", 20, 600, 80, hz()),
                    knobStep("Low", -15, 15, 0, 0.5f, db()),
                    logKnob("Low Hz", 60, 600, 200, hz()),
                    knobStep("Mid", -15, 15, 0, 0.5f, db()),
                    logKnob("Mid Hz", 200, 8000, 1500, hz()),
                    logKnob("Mid width (Q)", 0.3f, 6, 1, f1("")),
                    knobStep("Air", -15, 15, 0, 0.5f, db()),
                    logKnob("Air Hz", 2000, 16000, 8000, hz()),
                    toggle("High cut", false),
                    logKnob("High cut Hz", 2000, 20000, 16000, hz())),
            new ModuleType(COMP, "Compressor", "Evens out loud and quiet words", 0,
                    knob("Squash", 0, 100, 40, pct()),
                    logKnob("Attack", 0.5f, 100, 8, f1(" ms")),
                    logKnob("Release", 20, 1000, 120, ms()),
                    knobStep("Output", -12, 12, 0, 0.5f, db())),
            new ModuleType(GRIT, "Grit", "Saturation, warm to dirty", 0,
                    knob("Drive", 0, 100, 30, pct()),
                    knob("Tone", 0, 100, 50, pct()),
                    knob("Mix", 0, 100, 100, pct())),
            new ModuleType(CHORUS, "Chorus", "Wide, wobbly shimmer", 0,
                    knob("Mix", 0, 100, 35, pct()),
                    knob("Depth", 0, 100, 50, pct()),
                    logKnob("Rate", 0.1f, 3, 0.5f, f1(" Hz"))),
            new ModuleType(DELAY, "Delay", "Echoes synced to the tempo", 0,
                    knob("Mix", 0, 100, 20, pct()),
                    choice("Time", 3, DELAY_DIVS),
                    knob("Feedback", 0, 90, 30, pct()),
                    toggle("Ping-pong", true),
                    logKnob("Tone", 1000, 12000, 5000, hz())),
            new ModuleType(REVERB, "Reverb", "Smooth room to huge hall", 0,
                    knob("Mix", 0, 100, 20, pct()),
                    knob("Size", 0, 100, 60, pct()),
                    logKnob("Decay", 0.3f, 8, 1.8f, f1(" s")),
                    knob("Damping", 0, 100, 40, pct()),
                    knob("Pre-delay", 0, 150, 20, ms()),
                    knob("Width", 0, 100, 100, pct()),
                    logKnob("Low cut", 20, 600, 180, hz())),
    };

    public static ModuleType get(int id) {
        return ALL[Math.max(0, Math.min(ALL.length - 1, id))];
    }
}
