package com.totune.app;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import com.totune.app.dsp.Engine;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Decodes any audio file Android understands; encodes AAC/M4A. */
public final class AudioCodec {
    private AudioCodec() {}

    public static final int SR = 44100;

    private static final class Grow {
        float[] a = new float[1 << 16];
        int n;
        void add(float v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[n++] = v;
        }
        float[] get() { return java.util.Arrays.copyOf(a, n); }
    }

    /** Decodes to stereo float at 44.1 kHz. */
    public static float[][] decodeStereo(Context c, Uri uri, int maxSeconds) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(c, uri, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) throw new IOException("No audio track found in that file");
            ex.selectTrack(track);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            int sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int enc = AudioFormat.ENCODING_PCM_16BIT;
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            Grow l = new Grow(), r = new Grow();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            long maxFrames = (long) maxSeconds * sr;
            while (!outDone) {
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        int size = ib == null ? -1 : ex.readSampleData(ib, 0);
                        if (size < 0 || l.n > maxFrames) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) enc = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                } else if (oi >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ByteBuffer le = ob.slice().order(ByteOrder.LITTLE_ENDIAN);
                        if (enc == AudioFormat.ENCODING_PCM_FLOAT) {
                            int frames = info.size / 4 / ch;
                            for (int i = 0; i < frames; i++) {
                                float a = le.getFloat((i * ch) * 4);
                                float b = ch > 1 ? le.getFloat((i * ch + 1) * 4) : a;
                                l.add(a); r.add(b);
                            }
                        } else {
                            int frames = info.size / 2 / ch;
                            for (int i = 0; i < frames; i++) {
                                float a = le.getShort((i * ch) * 2) / 32768f;
                                float b = ch > 1 ? le.getShort((i * ch + 1) * 2) / 32768f : a;
                                l.add(a); r.add(b);
                            }
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
            float[] lf = Engine.resample(l.get(), sr, SR);
            float[] rf = Engine.resample(r.get(), sr, SR);
            int n = Math.min(lf.length, rf.length);
            if (n == 0) throw new IOException("The file contained no audio");
            return new float[][]{java.util.Arrays.copyOf(lf, n), java.util.Arrays.copyOf(rf, n)};
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                codec.release();
            }
            ex.release();
        }
    }

    /** Encodes stereo float audio to an AAC .m4a file. */
    public static void encodeM4a(short[] pcm, int sr, File out) throws IOException {
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, 2);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_BIT_RATE, 256000);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaMuxer mux = null;
        try {
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = -1;
            boolean started = false, inDone = false;
            int pos = 0, total = pcm.length / 2;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                if (!inDone) {
                    int ii = enc.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = enc.getInputBuffer(ii);
                        ib.clear();
                        ib.order(ByteOrder.LITTLE_ENDIAN);
                        int frames = Math.min(ib.capacity() / 4, total - pos);
                        long pts = (long) pos * 1_000_000L / sr;
                        for (int i = 0; i < frames; i++) {
                            ib.putShort(pcm[2 * (pos + i)]);
                            ib.putShort(pcm[2 * (pos + i) + 1]);
                        }
                        pos += frames;
                        boolean last = pos >= total;
                        enc.queueInputBuffer(ii, 0, frames * 4, pts, last ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                        if (last) inDone = true;
                    }
                }
                int oi = enc.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(enc.getOutputFormat());
                    mux.start();
                    started = true;
                } else if (oi >= 0) {
                    ByteBuffer ob = enc.getOutputBuffer(oi);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && started && ob != null) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        mux.writeSampleData(track, ob, info);
                    }
                    enc.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } finally {
            try { enc.stop(); } catch (Exception ignored) { }
            enc.release();
            if (mux != null) {
                try { mux.stop(); } catch (Exception ignored) { }
                mux.release();
            }
        }
    }

}
