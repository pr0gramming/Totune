package com.totune.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.totune.app.dsp.Params;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Built-in and user presets. Presets never touch key/scale/reference/tempo/mix levels. */
public final class Presets {
    private Presets() {}

    /** Settings that belong to the song, not the sound — kept when a preset is applied. */
    public static final Set<String> KEEP = new HashSet<>(Arrays.asList(
            "key", "scale", "refHz", "bpm", "vocalVol", "beatVol", "latencyMs"));

    private static final String PREFS = "presets";

    /** A built-in preset with a one-line description. */
    public static final class Preset {
        public final String name, desc;
        public final Params params;
        Preset(String name, String desc, Params params) { this.name = name; this.desc = desc; this.params = params; }
    }

    private interface Setup { void apply(Params p); }

    private static Preset make(String name, String desc, Setup s) {
        Params p = new Params();
        s.apply(p);
        return new Preset(name, desc, p);
    }

    /** Small helper so presets read like a mixing recipe. */
    private static void squash(Params p, float k) {
        p.compOn = true; p.compThresh = -6 - 30 * k; p.compRatio = 2 + 6 * k; p.compMakeup = 1 + 7 * k;
    }

    /** Built-in presets grouped by style, in display order. */
    public static Map<String, java.util.List<Preset>> categories() {
        Map<String, java.util.List<Preset>> m = new LinkedHashMap<>();

        java.util.List<Preset> hh = new ArrayList<>();
        hh.add(make("Black & Yellow", "Wiz Khalifa-style · hard tune, tight doubles, slap delay", p -> {
            p.retuneMs = 0; p.amount = 1; p.doublerOn = true; p.doublerLevel = 0.35f; p.doublerDetune = 8; p.doublerWidth = 0.8f;
            p.hpFreq = 110; p.highGain = 4; p.highFreq = 7000; squash(p, 0.55f);
            p.delayOn = true; p.delayDiv = 3; p.delayMix = 0.1f; p.delayFeedback = 0.2f;
            p.revSize = 0.45f; p.revMix = 0.12f;
        }));
        hh.add(make("Astro Rage", "Travis Scott-style · hard tune, chorus, dark huge space", p -> {
            p.retuneMs = 0; p.amount = 1; p.chorusOn = true; p.chorusMix = 0.4f; p.chorusDepth = 0.6f;
            p.doublerOn = true; p.doublerLevel = 0.35f; p.gritOn = true; p.grit = 0.15f;
            p.hpFreq = 100; p.highGain = 2; p.lpOn = true; p.lpFreq = 12000; squash(p, 0.5f);
            p.delayOn = true; p.delayDiv = 2; p.delayMix = 0.2f; p.delayFeedback = 0.4f;
            p.revSize = 0.88f; p.revDamp = 0.6f; p.revMix = 0.3f; p.revPreDelay = 30;
        }));
        hh.add(make("Mask Off", "Future-style · slurred hard tune, gritty and dark", p -> {
            p.retuneMs = 3; p.gritOn = true; p.grit = 0.3f; p.lowGain = 2; p.highGain = -1; squash(p, 0.6f);
            p.delayOn = true; p.delayDiv = 3; p.delayMix = 0.14f; p.delayFeedback = 0.3f;
            p.revSize = 0.6f; p.revDamp = 0.65f; p.revMix = 0.2f;
        }));
        hh.add(make("Melodic Trap", "Smooth tight tune, doubles and a long tail", p -> {
            p.retuneMs = 10; p.doublerOn = true; p.doublerLevel = 0.3f; p.highGain = 3; squash(p, 0.45f);
            p.delayOn = true; p.delayDiv = 1; p.delayMix = 0.12f; p.revSize = 0.7f; p.revMix = 0.22f;
        }));
        hh.add(make("Drill Slap", "Upfront, punchy and dry with light grit", p -> {
            p.retuneMs = 25; p.amount = 0.8f; p.hpFreq = 120; p.midGain = 2; p.midFreq = 2500; squash(p, 0.7f);
            p.gritOn = true; p.grit = 0.2f; p.revSize = 0.3f; p.revMix = 0.07f;
        }));
        m.put("Hip-hop / Trap", hh);

        java.util.List<Preset> pop = new ArrayList<>();
        pop.add(make("Radio Pop", "Polished, bright and present", p -> {
            p.retuneMs = 25; p.hpFreq = 120; p.midGain = 2; p.midFreq = 3000; p.highGain = 4; p.highFreq = 8000; squash(p, 0.55f);
            p.delayOn = true; p.delayDiv = 1; p.delayMix = 0.1f; p.revSize = 0.6f; p.revMix = 0.2f;
        }));
        pop.add(make("Airy Pop", "Breathy top end, wide doubles, plate-style verb", p -> {
            p.retuneMs = 35; p.highGain = 6; p.highFreq = 9000; p.lowGain = -2; p.lowFreq = 300; squash(p, 0.45f);
            p.doublerOn = true; p.doublerLevel = 0.25f; p.delayOn = true; p.delayDiv = 1; p.delayMix = 0.08f;
            p.revSize = 0.55f; p.revMix = 0.22f; p.revPreDelay = 40;
        }));
        pop.add(make("Big Chorus Stack", "Harmonies + doubles for a huge hook", p -> {
            p.retuneMs = 15; p.harmony = 3; p.harmonyLevel = 0.45f; p.doublerOn = true; p.doublerLevel = 0.4f;
            p.highGain = 3; squash(p, 0.5f); p.revSize = 0.8f; p.revMix = 0.3f;
        }));
        pop.add(make("Natural", "Gentle correction you barely hear", p -> {
            p.retuneMs = 90; p.amount = 0.8f; squash(p, 0.4f); p.revSize = 0.55f; p.revMix = 0.16f;
        }));
        pop.add(make("Big Hall Ballad", "Soft tune in a long, lush hall", p -> {
            p.retuneMs = 60; p.revSize = 0.92f; p.revMix = 0.35f; p.revPreDelay = 45; p.revDamp = 0.3f;
            p.delayOn = true; p.delayDiv = 1; p.delayMix = 0.12f; p.delayFeedback = 0.35f;
        }));
        m.put("Pop", pop);

        java.util.List<Preset> over = new ArrayList<>();
        over.add(make("T-Pain Robot", "Classic zero-speed autotune, nothing hidden", p -> {
            p.retuneMs = 0; p.amount = 1; p.highGain = 3; squash(p, 0.5f);
            p.delayOn = true; p.delayDiv = 3; p.delayMix = 0.1f; p.revMix = 0.15f;
        }));
        over.add(make("Max Overtune", "Robot tune + octave-down + synth layer", p -> {
            p.retuneMs = 0; p.harmony = 5; p.harmonyLevel = 0.3f; p.vocoderOn = true; p.vocoderMode = 1; p.vocoderMix = 0.3f;
            p.doublerOn = true; p.doublerLevel = 0.3f; p.chorusOn = true; p.chorusMix = 0.25f; squash(p, 0.6f);
            p.revMix = 0.2f;
        }));
        over.add(make("Glitch Snap", "Hard tune, grit, octave-up sparkle, fast echoes", p -> {
            p.retuneMs = 0; p.gritOn = true; p.grit = 0.45f; p.chorusOn = true; p.chorusMix = 0.2f;
            p.harmony = 6; p.harmonyLevel = 0.25f; squash(p, 0.6f);
            p.delayOn = true; p.delayDiv = 6; p.delayMix = 0.15f; p.delayFeedback = 0.45f; p.revMix = 0.12f;
        }));
        over.add(make("Octave Stack", "Robot tune with a ghostly octave above", p -> {
            p.retuneMs = 0; p.harmony = 6; p.harmonyLevel = 0.4f; p.doublerOn = true; p.doublerLevel = 0.35f;
            squash(p, 0.5f); p.revSize = 0.75f; p.revMix = 0.25f;
        }));
        m.put("Overuse Autotune", over);

        java.util.List<Preset> robot = new ArrayList<>();
        robot.add(make("Daft Robot", "Full synth voice following your melody", p -> {
            p.retuneMs = 0; p.vocoderOn = true; p.vocoderMode = 1; p.vocoderMix = 1f;
            p.chorusOn = true; p.chorusMix = 0.3f; p.revMix = 0.15f;
        }));
        robot.add(make("Choir Pad", "Your voice sings chords from the key", p -> {
            p.retuneMs = 0; p.vocoderOn = true; p.vocoderMode = 0; p.vocoderMix = 0.75f;
            p.chorusOn = true; p.chorusMix = 0.3f; p.revSize = 0.92f; p.revMix = 0.45f;
        }));
        robot.add(make("Deep Robot", "Low, menacing machine voice", p -> {
            p.retuneMs = 0; p.vocoderOn = true; p.vocoderMode = 3; p.vocoderMix = 1f;
            p.gritOn = true; p.grit = 0.2f; p.revMix = 0.12f;
        }));
        robot.add(make("Monotone Bot", "One flat robot note on the key's root", p -> {
            p.retuneMs = 0; p.vocoderOn = true; p.vocoderMode = 2; p.vocoderMix = 0.9f;
            p.delayOn = true; p.delayDiv = 3; p.delayMix = 0.12f; p.revMix = 0.1f;
        }));
        m.put("Robot / Vocoder", robot);

        java.util.List<Preset> basic = new ArrayList<>();
        basic.add(make("Clean (tune only)", "Just pitch correction, no effects", p -> {
            p.retuneMs = 40; p.compOn = false; p.reverbOn = false; p.delayOn = false; p.hpOn = false;
        }));
        m.put("Basics", basic);
        return m;
    }

    public static Map<String, Params> builtIns() {
        Map<String, Params> m = new LinkedHashMap<>();
        for (java.util.List<Preset> l : categories().values()) for (Preset pr : l) m.put(pr.name, pr.params);
        return m;
    }

    public static Map<String, Params> user(Context c) {
        Map<String, Params> m = new LinkedHashMap<>();
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject o = new JSONObject(sp.getString("user", "{}"));
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(k, TakeStore.paramsFromJson(o.getJSONObject(k)));
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    public static void saveUser(Context c, String name, Params p) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject o = new JSONObject(sp.getString("user", "{}"));
            o.put(name, TakeStore.paramsToJson(p));
            sp.edit().putString("user", o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static void deleteUser(Context c, String name) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject o = new JSONObject(sp.getString("user", "{}"));
            o.remove(name);
            sp.edit().putString("user", o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Applies the sound settings of {@code preset} onto {@code target}. */
    public static void apply(Params preset, Params target) {
        try {
            TakeStore.copyFromJson(TakeStore.paramsToJson(preset), target, KEEP);
        } catch (Exception ignored) {
        }
    }

    public static List<String> names(Context c) {
        List<String> l = new ArrayList<>(builtIns().keySet());
        l.addAll(user(c).keySet());
        return l;
    }
}
