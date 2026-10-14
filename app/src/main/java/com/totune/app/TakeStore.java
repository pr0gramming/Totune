package com.totune.app;

import android.content.Context;

import com.totune.app.dsp.Params;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Takes live in files/takes/<id>/ with vocal.wav, optional beat.wav and meta.json. */
public final class TakeStore {
    private TakeStore() {}

    public static final class Take {
        public final File dir;
        public String name = "Take";
        public long created;
        public double duration;
        public Params params = new Params();
        public Rack rack = Rack.defaultRack();

        Take(File dir) { this.dir = dir; }
        public String id() { return dir.getName(); }
        public File vocal() { return new File(dir, "vocal.wav"); }
        public File beat() { return new File(dir, "beat.wav"); }
        public boolean hasBeat() { return beat().exists(); }
        File meta() { return new File(dir, "meta.json"); }
    }

    static File root(Context c) {
        File r = new File(c.getFilesDir(), "takes");
        if (!r.exists()) r.mkdirs();
        return r;
    }

    public static Take create(Context c) {
        File d = new File(root(c), "t" + System.currentTimeMillis());
        d.mkdirs();
        Take t = new Take(d);
        t.created = System.currentTimeMillis();
        t.name = "Take " + (list(c).size() + 1);
        t.rack = Presets.lastRack(c);
        Presets.loadLastSong(c, t.params);
        return t;
    }

    public static List<Take> list(Context c) {
        List<Take> out = new ArrayList<>();
        File[] dirs = root(c).listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            Take t = load(d);
            if (t != null) out.add(t);
        }
        out.sort((a, b) -> Long.compare(b.created, a.created));
        return out;
    }

    public static Take get(Context c, String id) {
        return load(new File(root(c), id));
    }

    private static Take load(File d) {
        Take t = new Take(d);
        if (!t.vocal().exists() || !t.meta().exists()) return null;
        try {
            JSONObject o = new JSONObject(readText(t.meta()));
            t.name = o.optString("name", "Take");
            t.created = o.optLong("created", d.lastModified());
            t.duration = o.optDouble("duration", 0);
            JSONObject p = o.optJSONObject("params");
            if (p != null) t.params = paramsFromJson(p);
            org.json.JSONArray rk = o.optJSONArray("rack");
            if (rk != null) t.rack = Rack.fromJson(rk);
            else if (p != null) t.rack = Rack.fromOldParams(t.params);   // take made before the rack existed
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    public static void save(Take t) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", t.name);
            o.put("created", t.created);
            o.put("duration", t.duration);
            o.put("params", paramsToJson(t.params));
            o.put("rack", t.rack.toJson());
            File tmp = new File(t.dir, "meta.json.tmp");
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                fo.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
            tmp.renameTo(t.meta());
        } catch (Exception ignored) {
        }
    }

    public static void delete(Take t) {
        deleteRec(t.dir);
    }

    static void deleteRec(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        f.delete();
    }

    static String readText(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, r;
            while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
            return new String(b, 0, off, StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ params <-> JSON

    public static JSONObject paramsToJson(Params p) throws JSONException {
        JSONObject o = new JSONObject();
        for (Field f : Params.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                Class<?> t = f.getType();
                if (t == boolean.class) o.put(f.getName(), f.getBoolean(p));
                else if (t == int.class) o.put(f.getName(), f.getInt(p));
                else if (t == float.class) o.put(f.getName(), (double) f.getFloat(p));
            } catch (IllegalAccessException ignored) {
            }
        }
        return o;
    }

    public static Params paramsFromJson(JSONObject o) {
        Params p = new Params();
        copyFromJson(o, p, null);
        return p;
    }

    /** Copies fields present in JSON into p, skipping names in {@code skip}. */
    public static void copyFromJson(JSONObject o, Params p, java.util.Set<String> skip) {
        for (Field f : Params.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            String n = f.getName();
            if (!o.has(n) || (skip != null && skip.contains(n))) continue;
            try {
                Class<?> t = f.getType();
                if (t == boolean.class) f.setBoolean(p, o.getBoolean(n));
                else if (t == int.class) f.setInt(p, o.getInt(n));
                else if (t == float.class) f.setFloat(p, (float) o.getDouble(n));
            } catch (Exception ignored) {
            }
        }
    }
}
