package com.totune.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import com.totune.app.dsp.WavIO;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Saves mixes into Music/Totune and shares them. */
public final class Exporter {
    private Exporter() {}

    /** Renders the file (wav or m4a) and inserts it into the phone's Music library. */
    public static Uri save(Context c, short[] mix, String baseName, boolean m4a) throws IOException {
        File tmp = new File(c.getCacheDir(), "export." + (m4a ? "m4a" : "wav"));
        if (m4a) AudioCodec.encodeM4a(mix, AudioCodec.SR, tmp);
        else WavIO.writeInterleaved(tmp, mix, 2, AudioCodec.SR);

        String safe = baseName.replaceAll("[^A-Za-z0-9 _\\-]", "").trim();
        if (safe.isEmpty()) safe = "Totune";
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(new java.util.Date());
        String name = safe + " " + stamp + (m4a ? ".m4a" : ".wav");

        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Audio.Media.MIME_TYPE, m4a ? "audio/mp4" : "audio/x-wav");
        v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Totune");
        v.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        if (uri == null) throw new IOException("Couldn't create the file in Music");
        try (InputStream in = new FileInputStream(tmp); OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) throw new IOException("Couldn't write to Music");
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
        }
        v.clear();
        v.put(MediaStore.Audio.Media.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        tmp.delete();
        return uri;
    }

    public static void share(Context c, Uri uri, boolean m4a) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(m4a ? "audio/mp4" : "audio/x-wav");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        c.startActivity(Intent.createChooser(i, "Share your track"));
    }
}
