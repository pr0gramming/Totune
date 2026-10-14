// JNI bridge + AAudio low-latency streams for com.totune.app.NativeEngine.
#include <jni.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <time.h>
#include "engine.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "Totune", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "Totune", __VA_ARGS__)

using namespace tt;

namespace {

struct NoDenormals {
#if defined(__aarch64__)
    uint64_t old;
    NoDenormals() { asm volatile("mrs %0, fpcr" : "=r"(old)); uint64_t v = old | (1ull << 24); asm volatile("msr fpcr, %0" ::"r"(v)); }
    ~NoDenormals() { asm volatile("msr fpcr, %0" ::"r"(old)); }
#endif
};

int64_t nowNanos() {
    timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

struct Host {
    explicit Host(int sr) : engine(sr) { inBuf.assign(1 << 15, 0.f); }
    Engine engine;
    AAudioStream* out = nullptr;
    AAudioStream* in = nullptr;
    bool duplex = false;
    std::atomic<bool> drain{false}, broken{false};
    std::vector<float> inBuf;
};

aaudio_data_callback_result_t dataCb(AAudioStream* s, void* user, void* data, int32_t n) {
    (void)s;
    NoDenormals nd;
    Host* h = (Host*)user;
    float* out = (float*)data;
    if (n > (int32_t)h->inBuf.size()) { std::fill(out, out + 2 * n, 0.f); return AAUDIO_CALLBACK_RESULT_CONTINUE; }
    if (h->duplex && h->in) {
        if (h->drain.exchange(false)) {
            while (AAudioStream_read(h->in, h->inBuf.data(), (int32_t)h->inBuf.size(), 0) > 0) {}
        }
        int32_t got = AAudioStream_read(h->in, h->inBuf.data(), n, 0);
        if (got < 0) got = 0;
        for (int32_t i = got; i < n; i++) h->inBuf[i] = 0.f;
        h->engine.renderDuplex(h->inBuf.data(), out, n);
    } else {
        h->engine.renderPlayback(out, n);
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void errorCb(AAudioStream* s, void* user, aaudio_result_t err) {
    (void)s;
    LOGE("stream error %d", err);
    ((Host*)user)->broken.store(true);
}

void closeStreams(Host* h) {
    if (h->out) { AAudioStream_requestStop(h->out); AAudioStream_close(h->out); h->out = nullptr; }
    if (h->in) { AAudioStream_requestStop(h->in); AAudioStream_close(h->in); h->in = nullptr; }
    h->broken.store(false);
}

bool openStreams(Host* h, bool duplex) {
    closeStreams(h);
    h->duplex = duplex;
    AAudioStreamBuilder* b = nullptr;
    if (duplex) {
        if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return false;
        AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_INPUT);
        AAudioStreamBuilder_setSampleRate(b, h->engine.sr);
        AAudioStreamBuilder_setChannelCount(b, 1);
        AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_EXCLUSIVE);
        AAudioStreamBuilder_setInputPreset(b, AAUDIO_INPUT_PRESET_VOICE_PERFORMANCE);
        aaudio_result_t r = AAudioStreamBuilder_openStream(b, &h->in);
        AAudioStreamBuilder_delete(b);
        if (r != AAUDIO_OK) { LOGE("input open failed %d", r); h->in = nullptr; return false; }
    }
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) { closeStreams(h); return false; }
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(b, h->engine.sr);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setDataCallback(b, dataCb, h);
    AAudioStreamBuilder_setErrorCallback(b, errorCb, h);
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &h->out);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) { LOGE("output open failed %d", r); h->out = nullptr; closeStreams(h); return false; }
    // two bursts of buffering: low latency without crackles
    int32_t burst = AAudioStream_getFramesPerBurst(h->out);
    if (burst > 0) AAudioStream_setBufferSizeInFrames(h->out, burst * 2);
    if (duplex) {
        if (AAudioStream_requestStart(h->in) != AAUDIO_OK) { closeStreams(h); return false; }
        h->drain.store(true);
    }
    if (AAudioStream_requestStart(h->out) != AAUDIO_OK) { closeStreams(h); return false; }
    LOGI("streams open: duplex=%d sr=%d burst=%d", duplex, AAudioStream_getSampleRate(h->out), burst);
    return true;
}

double outputLatencyMs(AAudioStream* s, int sr) {
    int64_t pos = 0, t = 0;
    if (AAudioStream_getTimestamp(s, CLOCK_MONOTONIC, &pos, &t) != AAUDIO_OK) return -1;
    int64_t written = AAudioStream_getFramesWritten(s);
    double presentAt = t + (written - pos) * 1e9 / sr;
    return (presentAt - nowNanos()) / 1e6;
}

double inputLatencyMs(AAudioStream* s, int sr) {
    int64_t pos = 0, t = 0;
    if (AAudioStream_getTimestamp(s, CLOCK_MONOTONIC, &pos, &t) != AAUDIO_OK) return -1;
    int64_t read = AAudioStream_getFramesRead(s);
    double capturedAt = t + (read - pos) * 1e9 / sr;
    return (nowNanos() - capturedAt) / 1e6;
}

std::vector<float> toVec(JNIEnv* env, jfloatArray a) {
    std::vector<float> v;
    if (!a) return v;
    jsize n = env->GetArrayLength(a);
    v.resize(n);
    env->GetFloatArrayRegion(a, 0, n, v.data());
    return v;
}

struct RackArgs {
    std::vector<int> ids, types;
    std::unique_ptr<bool[]> on;
    std::vector<float> params;
    int count = 0;
};

RackArgs rackArgs(JNIEnv* env, jintArray ids, jintArray types, jbooleanArray on, jfloatArray params) {
    RackArgs r;
    r.count = env->GetArrayLength(ids);
    r.ids.resize(r.count); r.types.resize(r.count);
    env->GetIntArrayRegion(ids, 0, r.count, r.ids.data());
    env->GetIntArrayRegion(types, 0, r.count, r.types.data());
    r.on.reset(new bool[r.count > 0 ? r.count : 1]);
    std::vector<jboolean> ob(r.count);
    if (r.count) env->GetBooleanArrayRegion(on, 0, r.count, ob.data());
    for (int i = 0; i < r.count; i++) r.on[i] = ob[i] != 0;
    r.params.assign((size_t)r.count * MAX_PARAMS, 0.f);
    jsize pn = env->GetArrayLength(params);
    env->GetFloatArrayRegion(params, 0, std::min<jsize>(pn, (jsize)r.params.size()), r.params.data());
    return r;
}

inline Host* H(jlong h) { return reinterpret_cast<Host*>(h); }

}  // namespace

#define JN(name) Java_com_totune_app_NativeEngine_##name

extern "C" {

JNIEXPORT jlong JNICALL JN(nCreate)(JNIEnv*, jclass, jint sr) { return reinterpret_cast<jlong>(new Host(sr)); }

JNIEXPORT void JNICALL JN(nDestroy)(JNIEnv*, jclass, jlong h) {
    if (!h) return;
    closeStreams(H(h));
    delete H(h);
}

JNIEXPORT void JNICALL JN(nSetGlobals)(JNIEnv*, jclass, jlong h, jint key, jint scale, jfloat ref, jfloat bpm) {
    Globals& g = H(h)->engine.globals;
    g.key = key; g.scale = scale; g.refHz = ref; g.bpm = bpm;
}

JNIEXPORT void JNICALL JN(nSetRack)(JNIEnv* env, jclass, jlong h, jintArray ids, jintArray types, jbooleanArray on, jfloatArray params) {
    RackArgs r = rackArgs(env, ids, types, on, params);
    H(h)->engine.chain.setRack(r.ids.data(), r.types.data(), r.on.get(), r.params.data(), r.count);
}

JNIEXPORT void JNICALL JN(nSetParam)(JNIEnv*, jclass, jlong h, jint id, jint idx, jfloat v) { H(h)->engine.chain.setParam(id, idx, v); }
JNIEXPORT void JNICALL JN(nSetOn)(JNIEnv*, jclass, jlong h, jint id, jboolean on) { H(h)->engine.chain.setOn(id, on != 0); }
JNIEXPORT jint JNICALL JN(nLatency)(JNIEnv*, jclass, jlong h) { return H(h)->engine.chain.latency(); }

JNIEXPORT void JNICALL JN(nLoad)(JNIEnv* env, jclass, jlong h, jfloatArray vocal, jfloatArray bl, jfloatArray br) {
    Engine& e = H(h)->engine;
    std::vector<float> v = toVec(env, vocal), l = toVec(env, bl), r = toVec(env, br);
    if (r.size() != l.size()) r = l;
    std::lock_guard<std::mutex> lk(e.dataMtx);
    e.vocal.swap(v); e.beatL.swap(l); e.beatR.swap(r);
    e.seekPending.store(true);
}

JNIEXPORT void JNICALL JN(nSetMix)(JNIEnv*, jclass, jlong h, jfloat vv, jfloat bv, jint sync) {
    Engine& e = H(h)->engine;
    e.vocalVol = vv; e.beatVol = bv;
    if (e.syncSamples.exchange(sync) != sync) e.seekPending.store(true);
}

JNIEXPORT jboolean JNICALL JN(nOpenPlayback)(JNIEnv*, jclass, jlong h) {
    Host* x = H(h);
    if (x->out && !x->duplex && !x->broken.load()) return JNI_TRUE;
    return openStreams(x, false) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JN(nOpenDuplex)(JNIEnv*, jclass, jlong h) {
    Host* x = H(h);
    if (x->out && x->duplex && !x->broken.load()) return JNI_TRUE;
    return openStreams(x, true) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JN(nClose)(JNIEnv*, jclass, jlong h) {
    H(h)->engine.playing = false;
    closeStreams(H(h));
}

JNIEXPORT jboolean JNICALL JN(nNeedsRestart)(JNIEnv*, jclass, jlong h) { return H(h)->broken.load() ? JNI_TRUE : JNI_FALSE; }

JNIEXPORT void JNICALL JN(nPlay)(JNIEnv*, jclass, jlong h, jboolean play) {
    Engine& e = H(h)->engine;
    if (play) { e.ended = false; e.seekPending = true; }
    e.playing = play != 0;
}
JNIEXPORT jboolean JNICALL JN(nIsPlaying)(JNIEnv*, jclass, jlong h) { return H(h)->engine.playing.load() ? JNI_TRUE : JNI_FALSE; }
JNIEXPORT void JNICALL JN(nSeek)(JNIEnv*, jclass, jlong h, jlong f) { H(h)->engine.seek(f); }
JNIEXPORT jlong JNICALL JN(nPosition)(JNIEnv*, jclass, jlong h) { return H(h)->engine.pos.load(); }
JNIEXPORT jlong JNICALL JN(nLength)(JNIEnv*, jclass, jlong h) { return H(h)->engine.length(); }
JNIEXPORT void JNICALL JN(nSetOriginal)(JNIEnv*, jclass, jlong h, jboolean o) { H(h)->engine.original = o != 0; }

JNIEXPORT void JNICALL JN(nSetMonitor)(JNIEnv*, jclass, jlong h, jboolean on) { H(h)->engine.monitor = on != 0; }
JNIEXPORT void JNICALL JN(nRecord)(JNIEnv*, jclass, jlong h, jboolean rec) {
    if (rec) H(h)->engine.startRecording(); else H(h)->engine.stopRecording();
}
JNIEXPORT jint JNICALL JN(nReadRecorded)(JNIEnv* env, jclass, jlong h, jshortArray buf) {
    jsize n = env->GetArrayLength(buf);
    std::vector<int16_t> tmp(n);
    int got = H(h)->engine.rec.pop(tmp.data(), n);
    if (got > 0) env->SetShortArrayRegion(buf, 0, got, tmp.data());
    return got;
}
JNIEXPORT jfloat JNICALL JN(nInputPeak)(JNIEnv*, jclass, jlong h) { return H(h)->engine.inPeak.load(); }
JNIEXPORT jlong JNICALL JN(nRecFrames)(JNIEnv*, jclass, jlong h) { return H(h)->engine.recBeatPos.load(); }

JNIEXPORT jfloat JNICALL JN(nRoundTripMs)(JNIEnv*, jclass, jlong h) {
    Host* x = H(h);
    if (!x->out || !x->in) return -1;
    double o = outputLatencyMs(x->out, x->engine.sr), i = inputLatencyMs(x->in, x->engine.sr);
    if (o < 0 || i < 0) return -1;
    return (jfloat)(o + i);
}

JNIEXPORT jshortArray JNICALL JN(nRender)(JNIEnv* env, jclass, jint sr, jfloatArray vocal, jfloatArray bl, jfloatArray br,
                                          jint key, jint scale, jfloat ref, jfloat bpm,
                                          jintArray ids, jintArray types, jbooleanArray on, jfloatArray params,
                                          jfloat vv, jfloat bv, jint sync) {
    std::vector<float> v = toVec(env, vocal), l = toVec(env, bl), r = toVec(env, br);
    if (r.size() != l.size()) r = l;
    RackArgs ra = rackArgs(env, ids, types, on, params);
    std::vector<float> out = renderOffline(sr, v, l, r, key, scale, ref, bpm, ra.ids.data(), ra.types.data(), ra.on.get(),
                                           ra.params.data(), ra.count, vv, bv, sync, nullptr);
    std::vector<float>().swap(v);
    jshortArray res = env->NewShortArray((jsize)out.size());
    if (!res) return nullptr;
    const size_t chunk = 1 << 16;
    std::vector<int16_t> tmp(chunk);
    for (size_t off = 0; off < out.size(); off += chunk) {
        size_t k = std::min(chunk, out.size() - off);
        for (size_t i = 0; i < k; i++) tmp[i] = (int16_t)std::lrint(clampf(out[off + i], -1.f, 1.f) * 32767.f);
        env->SetShortArrayRegion(res, (jsize)off, (jsize)k, tmp.data());
    }
    return res;
}

JNIEXPORT jfloatArray JNICALL JN(nAnalyze)(JNIEnv* env, jclass, jint sr, jfloatArray vocal, jint key, jint scale, jfloat ref,
                                           jint style, jfloat speed, jfloat amount, jfloat keep, jfloat transpose, jint range) {
    std::vector<float> v = toVec(env, vocal), det, out;
    analyzeTake(sr, v, key, scale, ref, style, speed, amount, keep, transpose, range, det, out);
    jfloatArray res = env->NewFloatArray((jsize)(det.size() * 2));
    if (!res) return nullptr;
    env->SetFloatArrayRegion(res, 0, (jsize)det.size(), det.data());
    env->SetFloatArrayRegion(res, (jsize)det.size(), (jsize)out.size(), out.data());
    return res;
}

}  // extern "C"
