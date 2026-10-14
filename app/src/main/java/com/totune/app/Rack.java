package com.totune.app;

import com.totune.app.dsp.Params;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The ordered processing chain of a take. */
public final class Rack {
    public static final class Module {
        public int id;
        public int type;
        public boolean on = true;
        public float[] p;

        Module(int id, int type) {
            this.id = id; this.type = type;
            p = ModuleType.get(type).defaults();
        }

        public ModuleType def() { return ModuleType.get(type); }

        Module copy(int newId) {
            Module m = new Module(newId, type);
            m.on = on;
            m.p = p.clone();
            return m;
        }
    }

    public final List<Module> modules = new ArrayList<>();
    private int nextId = 1;

    public Module add(int type) {
        Module m = new Module(nextId++, type);
        modules.add(m);
        return m;
    }

    public Module duplicate(int index) {
        Module m = modules.get(index).copy(nextId++);
        modules.add(index + 1, m);
        return m;
    }

    public void move(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= modules.size() || to >= modules.size()) return;
        Module m = modules.remove(from);
        modules.add(to, m);
    }

    public Module firstOfType(int type) {
        for (Module m : modules) if (m.type == type) return m;
        return null;
    }

    public static Rack defaultRack() {
        Rack r = new Rack();
        r.add(ModuleType.TUNE);
        return r;
    }

    public Rack copy() {
        try {
            return fromJson(toJson());
        } catch (JSONException e) {
            return defaultRack();
        }
    }

    // ------------------------------------------------------------------ native form

    public static final class Flat {
        public int[] ids, types;
        public boolean[] on;
        public float[] params;
    }

    public Flat flatten() {
        int n = modules.size();
        Flat f = new Flat();
        f.ids = new int[n]; f.types = new int[n]; f.on = new boolean[n];
        f.params = new float[n * ModuleType.MAX_PARAMS];
        for (int i = 0; i < n; i++) {
            Module m = modules.get(i);
            f.ids[i] = m.id; f.types[i] = m.type; f.on[i] = m.on;
            System.arraycopy(m.p, 0, f.params, i * ModuleType.MAX_PARAMS, Math.min(m.p.length, ModuleType.MAX_PARAMS));
        }
        return f;
    }

    // ------------------------------------------------------------------ JSON

    public JSONArray toJson() throws JSONException {
        JSONArray a = new JSONArray();
        for (Module m : modules) {
            JSONObject o = new JSONObject();
            o.put("type", m.type);
            o.put("on", m.on);
            JSONArray p = new JSONArray();
            for (int i = 0; i < m.def().params.length; i++) p.put((double) m.p[i]);
            o.put("p", p);
            a.put(o);
        }
        return a;
    }

    public static Rack fromJson(JSONArray a) throws JSONException {
        Rack r = new Rack();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            int type = o.getInt("type");
            if (type < 0 || type >= ModuleType.ALL.length) continue;
            Module m = r.add(type);
            m.on = o.optBoolean("on", true);
            JSONArray p = o.optJSONArray("p");
            if (p != null) for (int k = 0; k < p.length() && k < m.p.length; k++) m.p[k] = (float) p.getDouble(k);
        }
        return r;
    }

    // ------------------------------------------------------------------ builders for presets / old takes

    /** Fluent helper: rack.with(TYPE, idx, value, idx, value, ...) */
    public Rack with(int type, float... pairs) {
        Module m = add(type);
        for (int i = 0; i + 1 < pairs.length; i += 2) m.p[(int) pairs[i]] = pairs[i + 1];
        return this;
    }

    /** Converts the settings of takes made before the rack existed. */
    public static Rack fromOldParams(Params o) {
        Rack r = new Rack();
        Module t = r.add(ModuleType.TUNE);
        t.on = o.tuneOn;
        t.p[1] = o.retuneMs; t.p[2] = o.amount * 100; t.p[4] = o.transpose;
        if (o.harmony > 0) {
            Module h = r.add(ModuleType.HARMONY);
            h.p[0] = o.harmony - 1; h.p[1] = o.harmonyLevel * 100;
        }
        if (o.vocoderOn) { Module v = r.add(ModuleType.VOCODER); v.p[0] = o.vocoderMode; v.p[1] = o.vocoderMix * 100; }
        if (o.doublerOn) { Module d = r.add(ModuleType.DOUBLER); d.p[0] = o.doublerLevel * 100; d.p[1] = o.doublerDetune; d.p[2] = o.doublerWidth * 100; }
        if (o.eqOn) {
            Module e = r.add(ModuleType.EQ);
            e.p[0] = o.hpOn ? 1 : 0; e.p[1] = o.hpFreq; e.p[2] = o.lowGain; e.p[3] = o.lowFreq;
            e.p[4] = o.midGain; e.p[5] = o.midFreq; e.p[6] = o.midQ; e.p[7] = o.highGain; e.p[8] = o.highFreq;
            e.p[9] = o.lpOn ? 1 : 0; e.p[10] = o.lpFreq;
        }
        if (o.compOn) { Module c = r.add(ModuleType.COMP); c.p[0] = Math.max(0, Math.min(100, (-6 - o.compThresh) / 30f * 100)); }
        if (o.gritOn) { Module g = r.add(ModuleType.GRIT); g.p[0] = o.grit * 100; }
        if (o.chorusOn) { Module c = r.add(ModuleType.CHORUS); c.p[0] = o.chorusMix * 100; c.p[1] = o.chorusDepth * 100; }
        if (o.delayOn) {
            Module d = r.add(ModuleType.DELAY);
            d.p[0] = o.delayMix * 100; d.p[1] = o.delayDiv; d.p[2] = o.delayFeedback * 100; d.p[3] = o.pingPong ? 1 : 0;
        }
        if (o.reverbOn) {
            Module v = r.add(ModuleType.REVERB);
            v.p[0] = o.revMix * 100; v.p[1] = o.revSize * 100; v.p[3] = o.revDamp * 100; v.p[4] = o.revPreDelay; v.p[5] = o.revWidth * 100;
        }
        return r;
    }
}
