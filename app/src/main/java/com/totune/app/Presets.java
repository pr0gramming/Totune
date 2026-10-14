package com.totune.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.totune.app.ModuleType.*;

/** Built-in and user presets. A preset is a whole rack: modules, order and settings. */
public final class Presets {
    private Presets() {}

    private static final String PREFS = "presets";

    public static final class Preset {
        public final String name, desc;
        public final Rack rack;
        Preset(String name, String desc, Rack rack) { this.name = name; this.desc = desc; this.rack = rack; }
    }

    // param index shortcuts (see ModuleType)
    private static final float STYLE = 0, SPEED = 1, AMOUNT = 2, KEEP = 3;
    private static final float TUNY = 0, CRISP = 1, WAVY = 2;

    private static Rack tune(float style, float speed, float... more) {
        Rack r = new Rack();
        float[] pairs = new float[4 + more.length];
        pairs[0] = STYLE; pairs[1] = style; pairs[2] = SPEED; pairs[3] = speed;
        System.arraycopy(more, 0, pairs, 4, more.length);
        return r.with(TUNE, pairs);
    }

    private static Rack eq(Rack r, float lowCut, float air, float airHz) {
        return r.with(EQ, 0, 1, 1, lowCut, 7, air, 8, airHz);
    }

    public static Map<String, List<Preset>> categories() {
        Map<String, List<Preset>> m = new LinkedHashMap<>();
        List<Preset> l;

        l = new ArrayList<>();
        l.add(new Preset("Black & Yellow", "Wiz Khalifa-style · hard tune, tight doubles, slap delay",
                eq(tune(TUNY, 0).with(DOUBLER, 0, 35, 1, 8, 2, 80), 110, 4, 7000)
                        .with(COMP, 0, 55).with(DELAY, 0, 10, 1, 4, 2, 20).with(REVERB, 0, 12, 1, 45, 2, 1.2f)));
        l.add(new Preset("Astro Rage", "Travis Scott-style · hard tune, chorus, dark huge space",
                tune(TUNY, 0).with(DOUBLER, 0, 35).with(CHORUS, 0, 40, 1, 60).with(GRIT, 0, 15)
                        .with(EQ, 0, 1, 1, 100, 7, 2, 9, 1, 10, 12000).with(COMP, 0, 50)
                        .with(DELAY, 0, 20, 1, 2, 2, 40).with(REVERB, 0, 30, 1, 88, 2, 4, 3, 60, 4, 30)));
        l.add(new Preset("Mask Off", "Future-style · slurred hard tune, gritty and dark",
                tune(CRISP, 3).with(GRIT, 0, 30).with(EQ, 0, 1, 1, 90, 2, 2, 7, -1).with(COMP, 0, 60)
                        .with(DELAY, 0, 14, 1, 4, 2, 30).with(REVERB, 0, 20, 1, 60, 2, 2, 3, 65)));
        l.add(new Preset("Melodic Trap", "Smooth tight tune, doubles and a long tail",
                eq(tune(TUNY, 10).with(DOUBLER, 0, 30), 100, 3, 8000).with(COMP, 0, 45)
                        .with(DELAY, 0, 12, 1, 1).with(REVERB, 0, 22, 1, 70, 2, 2.5f)));
        l.add(new Preset("Drill Slap", "Upfront, punchy and dry with light grit",
                tune(CRISP, 25, AMOUNT, 80).with(EQ, 0, 1, 1, 120, 4, 2, 5, 2500).with(COMP, 0, 70)
                        .with(GRIT, 0, 20).with(REVERB, 0, 7, 1, 30, 2, 0.8f)));
        m.put("Hip-hop / Trap", l);

        l = new ArrayList<>();
        l.add(new Preset("Radio Pop", "Polished, bright and present",
                tune(TUNY, 25).with(EQ, 0, 1, 1, 120, 4, 2, 5, 3000, 7, 4, 8, 8000).with(COMP, 0, 55)
                        .with(DELAY, 0, 10, 1, 1).with(REVERB, 0, 20, 1, 60, 2, 1.8f)));
        l.add(new Preset("Airy Pop", "Breathy top end, wide doubles, soft space",
                tune(WAVY, 35).with(EQ, 0, 1, 1, 100, 2, -2, 3, 300, 7, 6, 8, 9000).with(COMP, 0, 45)
                        .with(DOUBLER, 0, 25).with(DELAY, 0, 8, 1, 1).with(REVERB, 0, 22, 1, 55, 2, 1.6f, 4, 40)));
        l.add(new Preset("Big Chorus Stack", "Harmonies + doubles for a huge hook",
                eq(tune(TUNY, 15).with(HARMONY, 0, 2, 1, 45).with(DOUBLER, 0, 40), 100, 3, 8000)
                        .with(COMP, 0, 50).with(REVERB, 0, 30, 1, 80, 2, 3)));
        l.add(new Preset("Natural", "Gentle correction you barely hear",
                tune(WAVY, 90, AMOUNT, 80, KEEP, 40).with(COMP, 0, 40).with(REVERB, 0, 16, 1, 55, 2, 1.5f)));
        l.add(new Preset("Big Hall Ballad", "Soft tune in a long, lush hall",
                tune(WAVY, 60, KEEP, 30).with(DELAY, 0, 12, 1, 1, 2, 35)
                        .with(REVERB, 0, 35, 1, 92, 2, 4.5f, 3, 30, 4, 45)));
        m.put("Pop", l);

        l = new ArrayList<>();
        l.add(new Preset("T-Pain Robot", "Classic zero-speed autotune, nothing hidden",
                eq(tune(CRISP, 0), 100, 3, 8000).with(COMP, 0, 50).with(DELAY, 0, 10, 1, 4).with(REVERB, 0, 15, 1, 50, 2, 1.5f)));
        l.add(new Preset("Max Overtune", "Robot tune + octave-down + synth layer",
                tune(CRISP, 0).with(HARMONY, 0, 4, 1, 30).with(VOCODER, 0, 1, 1, 30).with(DOUBLER, 0, 30)
                        .with(CHORUS, 0, 25).with(COMP, 0, 60).with(REVERB, 0, 20)));
        l.add(new Preset("Glitch Snap", "Hard tune, grit, octave-up sparkle, fast echoes",
                tune(CRISP, 0).with(GRIT, 0, 45).with(CHORUS, 0, 20).with(HARMONY, 0, 5, 1, 25).with(COMP, 0, 60)
                        .with(DELAY, 0, 15, 1, 5, 2, 45).with(REVERB, 0, 12)));
        l.add(new Preset("Octave Stack", "Robot tune with a ghostly octave above",
                tune(TUNY, 0).with(HARMONY, 0, 5, 1, 40).with(DOUBLER, 0, 35).with(COMP, 0, 50)
                        .with(REVERB, 0, 25, 1, 75, 2, 2.5f)));
        m.put("Overuse Autotune", l);

        l = new ArrayList<>();
        l.add(new Preset("Daft Robot", "Full synth voice following your melody",
                tune(TUNY, 0).with(VOCODER, 0, 1, 1, 100).with(CHORUS, 0, 30).with(REVERB, 0, 15)));
        l.add(new Preset("Choir Pad", "Your voice sings chords from the key",
                tune(TUNY, 0).with(VOCODER, 0, 0, 1, 75).with(CHORUS, 0, 30).with(REVERB, 0, 45, 1, 92, 2, 5)));
        l.add(new Preset("Deep Robot", "Low, menacing machine voice",
                tune(TUNY, 0).with(VOCODER, 0, 3, 1, 100).with(GRIT, 0, 20).with(REVERB, 0, 12)));
        l.add(new Preset("Monotone Bot", "One flat robot note on the key's root",
                tune(TUNY, 0).with(VOCODER, 0, 2, 1, 90).with(DELAY, 0, 12, 1, 4).with(REVERB, 0, 10)));
        m.put("Robot / Vocoder", l);

        l = new ArrayList<>();
        l.add(new Preset("Clean (tune only)", "Just pitch correction, no effects", tune(TUNY, 40)));
        m.put("Basics", l);
        return m;
    }

    /** The preset's rack, keeping the singer's voice range from the current rack. */
    public static Rack applied(Rack preset, Rack current) {
        Rack r = preset.copy();
        Rack.Module cur = current.firstOfType(TUNE);
        if (cur != null) {
            for (Rack.Module m : r.modules) {
                if (m.type == TUNE) m.p[6] = cur.p[6];
                if (m.type == HARMONY) m.p[4] = cur.p[6];
            }
        }
        return r;
    }

    // ------------------------------------------------------------------ user presets

    public static Map<String, Rack> user(Context c) {
        Map<String, Rack> m = new LinkedHashMap<>();
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject o = new JSONObject(sp.getString("racks", "{}"));
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(k, Rack.fromJson(o.getJSONArray(k)));
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    public static void saveUser(Context c, String name, Rack r) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject o = new JSONObject(sp.getString("racks", "{}"));
            o.put(name, r.toJson());
            sp.edit().putString("racks", o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static void deleteUser(Context c, String name) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject o = new JSONObject(sp.getString("racks", "{}"));
            o.remove(name);
            sp.edit().putString("racks", o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ last used rack (for new recordings)

    public static Rack lastRack(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            if (!sp.contains("last")) return Rack.defaultRack();
            return Rack.fromJson(new JSONArray(sp.getString("last", "[]")));
        } catch (Exception e) {
            return Rack.defaultRack();
        }
    }

    /** Song settings (key, scale, reference, tempo) of the last take, reused for the next recording. */
    public static void saveLastSong(Context c, com.totune.app.dsp.Params p) {
        try {
            JSONObject o = new JSONObject();
            o.put("key", p.key); o.put("scale", p.scale); o.put("refHz", (double) p.refHz); o.put("bpm", (double) p.bpm);
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("song", o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static void loadLastSong(Context c, com.totune.app.dsp.Params p) {
        try {
            JSONObject o = new JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("song", "{}"));
            p.key = o.optInt("key", p.key); p.scale = o.optInt("scale", p.scale);
            p.refHz = (float) o.optDouble("refHz", p.refHz); p.bpm = (float) o.optDouble("bpm", p.bpm);
        } catch (Exception ignored) {
        }
    }

    public static void saveLastRack(Context c, Rack r) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last", r.toJson().toString()).apply();
        } catch (Exception ignored) {
        }
    }
}
