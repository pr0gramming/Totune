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

    public static Map<String, Params> builtIns() {
        Map<String, Params> m = new LinkedHashMap<>();
        Params p;

        p = new Params();
        p.retuneMs = 0; p.amount = 1; p.hpFreq = 110; p.highGain = 3; p.highFreq = 7000;
        p.compThresh = -22; p.compRatio = 4; p.compMakeup = 5;
        p.delayOn = true; p.delayDiv = 4; p.delayMix = 0.14f; p.delayFeedback = 0.3f;
        p.revSize = 0.5f; p.revMix = 0.14f;
        m.put("Hard Tune (Trap)", p);

        p = new Params();
        p.retuneMs = 90; p.amount = 0.8f; p.compThresh = -18; p.compRatio = 2.5f;
        p.revSize = 0.55f; p.revMix = 0.16f;
        m.put("Natural", p);

        p = new Params();
        p.retuneMs = 25; p.hpFreq = 120; p.midGain = 2; p.midFreq = 3000; p.highGain = 4; p.highFreq = 8000;
        p.compThresh = -22; p.compRatio = 4; p.compMakeup = 5;
        p.delayOn = true; p.delayDiv = 1; p.delayMix = 0.1f;
        p.revSize = 0.6f; p.revMix = 0.2f;
        m.put("Radio Pop", p);

        p = new Params();
        p.retuneMs = 60; p.revSize = 0.92f; p.revMix = 0.35f; p.revPreDelay = 45; p.revDamp = 0.3f;
        p.delayOn = true; p.delayDiv = 1; p.delayMix = 0.12f; p.delayFeedback = 0.35f;
        m.put("Big Hall Ballad", p);

        p = new Params();
        p.retuneMs = 30; p.hpFreq = 450; p.lpOn = true; p.lpFreq = 3500; p.midGain = 6; p.midFreq = 1500;
        p.compRatio = 6; p.compThresh = -24; p.compMakeup = 6; p.revMix = 0.08f;
        m.put("Lo-fi Phone", p);

        p = new Params();
        p.retuneMs = 15; p.harmony = 3; p.harmonyLevel = 0.45f; p.doublerOn = true; p.doublerLevel = 0.45f;
        p.revSize = 0.8f; p.revMix = 0.3f;
        m.put("Choir Stack", p);

        p = new Params();
        p.retuneMs = 40; p.compOn = false; p.reverbOn = false; p.delayOn = false; p.hpOn = false;
        m.put("Clean (tune only)", p);
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
