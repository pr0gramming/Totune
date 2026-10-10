package com.totune.app.dsp;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** WAV reading/writing (PCM 16/24/32-bit and 32-bit float). */
public final class WavIO {
    private WavIO() {}

    public static final class Audio {
        public final float[][] channels;
        public final int sampleRate;
        Audio(float[][] c, int sr) { channels = c; sampleRate = sr; }
        public float[] mono() {
            if (channels.length == 1) return channels[0];
            float[] m = new float[channels[0].length];
            for (float[] ch : channels) for (int i = 0; i < m.length; i++) m[i] += ch[i] / channels.length;
            return m;
        }
    }

    public static Audio read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] all = readAll(in);
            return parse(all);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
        return bo.toByteArray();
    }

    public static Audio parse(byte[] b) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        if (b.length < 12 || bb.getInt(0) != 0x46464952 || bb.getInt(8) != 0x45564157) {
            throw new IOException("Not a WAV file");
        }
        int pos = 12, fmt = 0, ch = 0, sr = 0, bits = 0, dataOff = -1, dataLen = 0;
        while (pos + 8 <= b.length) {
            int id = bb.getInt(pos), len = bb.getInt(pos + 4);
            int body = pos + 8;
            if (id == 0x20746d66) { // "fmt "
                fmt = bb.getShort(body) & 0xffff;
                ch = bb.getShort(body + 2);
                sr = bb.getInt(body + 4);
                bits = bb.getShort(body + 14);
                if (fmt == 0xFFFE && len >= 26) fmt = bb.getShort(body + 24) & 0xffff;
            } else if (id == 0x61746164) { // "data"
                dataOff = body;
                dataLen = (len < 0 || body + len > b.length) ? b.length - body : len;
                break;
            }
            pos = body + len + (len & 1);
        }
        if (dataOff < 0 || ch <= 0 || sr <= 0) throw new IOException("Unsupported WAV");
        int bps = bits / 8;
        int frames = dataLen / (bps * ch);
        float[][] out = new float[ch][frames];
        for (int i = 0; i < frames; i++) {
            for (int c = 0; c < ch; c++) {
                int o = dataOff + (i * ch + c) * bps;
                float v;
                if (fmt == 3 && bits == 32) v = bb.getFloat(o);
                else if (bits == 16) v = bb.getShort(o) / 32768f;
                else if (bits == 24) v = ((b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | (b[o + 2] << 16)) / 8388608f;
                else if (bits == 32) v = bb.getInt(o) / 2147483648f;
                else if (bits == 8) v = ((b[o] & 0xff) - 128) / 128f;
                else throw new IOException("Unsupported bit depth " + bits);
                out[c][i] = v;
            }
        }
        return new Audio(out, sr);
    }

    /** Writes 16-bit PCM. */
    public static void write(File f, float[][] ch, int sr) throws IOException {
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(f), 1 << 16)) {
            write(os, ch, sr);
        }
    }

    public static void write(OutputStream os, float[][] ch, int sr) throws IOException {
        int nch = ch.length, frames = ch[0].length;
        int dataLen = frames * nch * 2;
        os.write(header(nch, sr, dataLen));
        byte[] buf = new byte[4096 * nch * 2];
        int bi = 0;
        for (int i = 0; i < frames; i++) {
            for (int c = 0; c < nch; c++) {
                float v = Math.max(-1f, Math.min(1f, ch[c][i]));
                int s = Math.round(v * 32767f);
                buf[bi++] = (byte) s;
                buf[bi++] = (byte) (s >> 8);
                if (bi == buf.length) { os.write(buf); bi = 0; }
            }
        }
        if (bi > 0) os.write(buf, 0, bi);
    }

    /** Writes interleaved 16-bit PCM. */
    public static void writeInterleaved(File f, short[] pcm, int nch, int sr) throws IOException {
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(f), 1 << 16)) {
            os.write(header(nch, sr, pcm.length * 2));
            byte[] buf = new byte[1 << 15];
            int bi = 0;
            for (short v : pcm) {
                buf[bi++] = (byte) v;
                buf[bi++] = (byte) (v >> 8);
                if (bi == buf.length) { os.write(buf); bi = 0; }
            }
            if (bi > 0) os.write(buf, 0, bi);
        }
    }

    static byte[] header(int nch, int sr, int dataLen) {
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0x46464952).putInt(36 + dataLen).putInt(0x45564157);
        h.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) nch)
                .putInt(sr).putInt(sr * nch * 2).putShort((short) (nch * 2)).putShort((short) 16);
        h.putInt(0x61746164).putInt(dataLen);
        return h.array();
    }

    /** Streams 16-bit mono PCM to disk while recording; the header is fixed on close. */
    public static final class Writer implements AutoCloseable {
        private final RandomAccessFile raf;
        private final int sr;
        private int dataLen = 0;
        private byte[] buf = new byte[0];

        public Writer(File f, int sr) throws IOException {
            this.sr = sr;
            raf = new RandomAccessFile(f, "rw");
            raf.setLength(0);
            raf.write(header(1, sr, 0));
        }

        public void write(short[] s, int n) throws IOException {
            if (buf.length < n * 2) buf = new byte[n * 2];
            for (int i = 0; i < n; i++) {
                buf[2 * i] = (byte) s[i];
                buf[2 * i + 1] = (byte) (s[i] >> 8);
            }
            raf.write(buf, 0, n * 2);
            dataLen += n * 2;
        }

        @Override
        public void close() throws IOException {
            raf.seek(0);
            raf.write(header(1, sr, dataLen));
            raf.close();
        }
    }
}
