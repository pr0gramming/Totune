// Rack chain + playback / monitoring engine (platform independent).
#pragma once
#include <mutex>
#include "modules.h"

namespace tt {

// ================================================================= chain

class Chain {
public:
    void prepare(int sr, Globals* g) { sr_ = sr; g_ = g; }

    /** Rebuilds the module order. Modules with the same id keep their state. Call from one (UI) thread. */
    void setRack(const int* ids, const int* types, const bool* on, const float* params, int count) {
        std::vector<std::unique_ptr<Module>> next;
        next.reserve(count);
        std::vector<std::unique_ptr<Module>> old;
        {
            std::lock_guard<std::mutex> lk(mtx);
            old.swap(mods);
        }
        for (int i = 0; i < count; i++) {
            std::unique_ptr<Module> m;
            for (auto& o : old) if (o && o->id == ids[i] && o->type == types[i]) { m = std::move(o); break; }
            bool fresh = !m;
            if (fresh) {
                m = makeModule(types[i]);
                if (!m) continue;
                m->id = ids[i]; m->type = types[i];
            }
            for (int k = 0; k < MAX_PARAMS; k++) m->setParam(k, params[i * MAX_PARAMS + k]);
            if (fresh) m->prepare(sr_, g_);
            m->on.store(on[i]);
            next.push_back(std::move(m));
        }
        std::lock_guard<std::mutex> lk(mtx);
        mods.swap(next);
        // modules left in `old` are destroyed here, outside the audio callback
    }

    void setParam(int id, int idx, float v) { for (auto& m : mods) if (m->id == id) m->setParam(idx, v); }
    void setOn(int id, bool on) { for (auto& m : mods) if (m->id == id) m->on.store(on); }

    /** Audio thread. Returns false (and leaves the buffer untouched) if the rack is being rebuilt. */
    bool process(float* l, float* r, int n) {
        std::unique_lock<std::mutex> lk(mtx, std::try_to_lock);
        if (!lk.owns_lock()) return false;
        for (int off = 0; off < n; off += MAX_BLOCK) {
            int k = std::min(MAX_BLOCK, n - off);
            for (auto& m : mods) {
                if (m->on.load(std::memory_order_relaxed)) m->process(l + off, r + off, k);
                else m->bypass(l + off, r + off, k);
            }
        }
        return true;
    }

    int latency() {
        int s = 0;
        for (auto& m : mods) s += m->latency();
        return s;
    }

    std::mutex mtx;
    std::vector<std::unique_ptr<Module>> mods;
private:
    int sr_ = 48000;
    Globals* g_ = nullptr;
};

// ================================================================= lock-free mono recorder ring (audio -> UI)

class RecordRing {
public:
    explicit RecordRing(int size = 1 << 19) : buf_(size), mask_(size - 1) {}
    void clear() { w_.store(0); r_.store(0); }
    void push(const float* x, int n) {
        int64_t w = w_.load(std::memory_order_relaxed), r = r_.load(std::memory_order_acquire);
        for (int i = 0; i < n; i++) {
            if (w - r >= (int64_t)buf_.size()) { dropped++; break; }
            float v = clampf(x[i], -1.f, 1.f);
            buf_[w & mask_] = (int16_t)std::lrint(v * 32767.f);
            w++;
        }
        w_.store(w, std::memory_order_release);
    }
    int pop(int16_t* out, int max) {
        int64_t r = r_.load(std::memory_order_relaxed), w = w_.load(std::memory_order_acquire);
        int n = (int)std::min<int64_t>(max, w - r);
        for (int i = 0; i < n; i++) out[i] = buf_[(r + i) & mask_];
        r_.store(r + n, std::memory_order_release);
        return n;
    }
    std::atomic<int64_t> dropped{0};
private:
    std::vector<int16_t> buf_;
    int64_t mask_;
    std::atomic<int64_t> w_{0}, r_{0};
};

// ================================================================= engine

class Engine {
public:
    explicit Engine(int sr) : sr(sr) {
        chain.prepare(sr, &globals);
        l_.assign(MAX_BLOCK, 0); r_.assign(MAX_BLOCK, 0); tmp_.assign(MAX_BLOCK * 4, 0);
        rawDelayL_.init(sr); rawDelayR_.init(sr);
    }

    const int sr;
    Globals globals;
    Chain chain;

    // ---- playback (editing)
    std::mutex dataMtx;
    std::vector<float> vocal, beatL, beatR;
    std::atomic<bool> playing{false}, original{false}, ended{false};
    std::atomic<int64_t> pos{0};              // song position (frames)
    std::atomic<bool> seekPending{true};
    std::atomic<float> vocalVol{1.f}, beatVol{0.8f};
    std::atomic<int> syncSamples{0};

    void seek(int64_t frames) { pos.store(std::max<int64_t>(0, frames)); seekPending.store(true); }

    int64_t length() {
        int64_t v = (int64_t)vocal.size() - syncSamples.load();
        return std::max<int64_t>(std::max<int64_t>(v, 0), (int64_t)beatL.size()) + sr * 2;   // room for FX tails
    }

    /** Fills an interleaved stereo float buffer while editing. */
    void renderPlayback(float* out, int n) {
        std::unique_lock<std::mutex> lk(dataMtx, std::try_to_lock);
        if (!lk.owns_lock() || !playing.load()) { std::fill(out, out + 2 * n, 0.f); return; }
        for (int off = 0; off < n; off += MAX_BLOCK) {
            int k = std::min(MAX_BLOCK, n - off);
            playBlock(out + 2 * off, k);
        }
    }

    // ---- recording (full duplex)
    RecordRing rec;
    std::atomic<bool> monitor{true}, recording{false};
    std::atomic<float> inPeak{0};
    std::atomic<int64_t> recBeatPos{0};

    /** in: mono mic block, out: interleaved stereo. */
    void renderDuplex(const float* in, float* out, int n) {
        float pk = 0;
        for (int i = 0; i < n; i++) pk = std::max(pk, std::fabs(in[i]));
        inPeak.store(std::max(pk, inPeak.load() * 0.9f));
        bool isRec = recording.load();
        if (isRec) rec.push(in, n);
        for (int off = 0; off < n; off += MAX_BLOCK) {
            int k = std::min(MAX_BLOCK, n - off);
            for (int i = 0; i < k; i++) { l_[i] = in[off + i]; r_[i] = in[off + i]; }
            bool mon = monitor.load();
            if (mon) { if (!chain.process(l_.data(), r_.data(), k)) { std::fill(l_.begin(), l_.begin() + k, 0.f); std::fill(r_.begin(), r_.begin() + k, 0.f); } }
            float vv = vocalVol.load(), bv = beatVol.load();
            std::unique_lock<std::mutex> lk(dataMtx, std::try_to_lock);
            bool beat = isRec && lk.owns_lock() && !beatL.empty();
            int64_t bp = recBeatPos.load();
            for (int i = 0; i < k; i++) {
                float a = mon ? l_[i] * vv : 0.f, b = mon ? r_[i] * vv : 0.f;
                if (beat && bp + i < (int64_t)beatL.size()) { a += beatL[bp + i] * bv; b += beatR[bp + i] * bv; }
                out[2 * (off + i)] = softClip(a);
                out[2 * (off + i) + 1] = softClip(b);
            }
            if (isRec) recBeatPos.store(bp + k);
        }
    }

    void startRecording() { rec.clear(); recBeatPos.store(0); recording.store(true); }
    void stopRecording() { recording.store(false); }

private:
    static inline float softClip(float x) {
        if (x > 0.95f) return 0.95f + 0.05f * std::tanh((x - 0.95f) / 0.05f);
        if (x < -0.95f) return -0.95f + 0.05f * std::tanh((x + 0.95f) / 0.05f);
        return x;
    }

    inline float vocalAt(int64_t i) const { return (i >= 0 && i < (int64_t)vocal.size()) ? vocal[i] : 0.f; }

    void playBlock(float* out, int n) {
        int lat = chain.latency();
        int sync = syncSamples.load();
        if (seekPending.exchange(false)) {
            // prime the chain so its output lines up with the playhead
            feed_ = pos.load() + sync;
            int left = lat;
            while (left > 0) {
                int k = std::min(left, MAX_BLOCK);
                for (int i = 0; i < k; i++) { l_[i] = r_[i] = vocalAt(feed_ + i); }
                chain.process(l_.data(), r_.data(), k);
                for (int i = 0; i < k; i++) { rawDelayL_.push(l_[i]); rawDelayR_.push(r_[i]); }
                feed_ += k; left -= k;
            }
            feedLat_ = lat;
        }
        if (lat != feedLat_) {   // a module changed its latency: keep the playhead, re-align the feed
            feed_ += lat - feedLat_;
            feedLat_ = lat;
        }
        for (int i = 0; i < n; i++) { float v = vocalAt(feed_ + i); l_[i] = v; r_[i] = v; tmp_[i] = v; }
        bool processed = chain.process(l_.data(), r_.data(), n);
        bool orig = original.load();
        int64_t p = pos.load();
        float vv = vocalVol.load(), bv = beatVol.load();
        for (int i = 0; i < n; i++) {
            rawDelayL_.push(tmp_[i]);
            float raw = rawDelayL_.tap(lat);
            float a, b;
            if (orig || !processed) { a = raw; b = raw; }
            else { a = l_[i]; b = r_[i]; }
            a *= vv; b *= vv;
            int64_t bi = p + i;
            if (bi < (int64_t)beatL.size()) { a += beatL[bi] * bv; b += beatR[bi] * bv; }
            out[2 * i] = softClip(a);
            out[2 * i + 1] = softClip(b);
        }
        feed_ += n;
        pos.store(p + n);
        if (p + n >= length()) { playing.store(false); ended.store(true); pos.store(0); seekPending.store(true); }
    }

    std::vector<float> l_, r_, tmp_;
    DelayLine rawDelayL_, rawDelayR_;
    int64_t feed_ = 0;
    int feedLat_ = 0;
};

// ================================================================= offline helpers

/** Renders the whole take through a fresh copy of the rack (for export). Returns interleaved stereo. */
inline std::vector<float> renderOffline(int sr, const std::vector<float>& vocal, const std::vector<float>& bl,
                                        const std::vector<float>& br, int key, int scale, float ref, float bpm,
                                        const int* ids, const int* types, const bool* on, const float* params, int count,
                                        float vocalVol, float beatVol, int sync, std::atomic<float>* progress) {
    Globals g; g.key = key; g.scale = scale; g.refHz = ref; g.bpm = bpm;
    Chain chain; chain.prepare(sr, &g);
    chain.setRack(ids, types, on, params, count);
    int lat = chain.latency();
    int64_t vlen = std::max<int64_t>(0, (int64_t)vocal.size() - sync);
    int64_t len = std::max<int64_t>(vlen, (int64_t)bl.size()) + (int64_t)sr * 3;   // tails
    std::vector<float> out(len * 2, 0.f);
    std::vector<float> l(MAX_BLOCK), r(MAX_BLOCK);
    int64_t feed = sync, written = -lat;
    while (written < len) {
        int k = MAX_BLOCK;
        for (int i = 0; i < k; i++) { int64_t vi = feed + i; float v = (vi >= 0 && vi < (int64_t)vocal.size()) ? vocal[vi] : 0.f; l[i] = r[i] = v; }
        chain.process(l.data(), r.data(), k);
        for (int i = 0; i < k; i++) {
            int64_t o = written + i;
            if (o < 0 || o >= len) continue;
            out[2 * o] = l[i] * vocalVol;
            out[2 * o + 1] = r[i] * vocalVol;
        }
        feed += k; written += k;
        if (progress) progress->store((float)std::max<int64_t>(0, written) / len);
    }
    for (int64_t i = 0; i < (int64_t)bl.size() && i < len; i++) { out[2 * i] += bl[i] * beatVol; out[2 * i + 1] += br[i] * beatVol; }
    // trim trailing silence, keep the beat length
    int64_t last = std::max<int64_t>((int64_t)bl.size(), vlen);
    for (int64_t i = len - 1; i > last; i--) {
        if (std::fabs(out[2 * i]) > 1e-4f || std::fabs(out[2 * i + 1]) > 1e-4f) { last = i; break; }
    }
    out.resize((last + 1) * 2);
    float peak = 0;
    for (float v : out) peak = std::max(peak, std::fabs(v));
    if (peak > 0.98f) { float gn = 0.98f / peak; for (float& v : out) v *= gn; }
    return out;
}

/** Sung pitch and corrected pitch (MIDI, NaN = silence) every 256 samples, for the pitch graph. */
inline void analyzeTake(int sr, const std::vector<float>& vocal, int key, int scale, float ref,
                        int style, float speed, float amount, float keep, float transpose, int range,
                        std::vector<float>& det, std::vector<float>& outM) {
    PitchAnalyzer a;
    a.init(sr, voiceRangeMin(range), 1100.f, false);
    TuneTracker tr;
    bool mask[12]; Scales::mask(key, scale, mask);
    const int hop = 256;
    int64_t frames = (int64_t)vocal.size() / hop;
    det.assign(frames, NAN); outM.assign(frames, NAN);
    // the detector reports pitch for a window centred centreDelay() samples back; shift it into place
    std::vector<float> f0(frames + 64, 0.f);
    int64_t est = 0;
    for (int64_t f = 0; f < frames + 32; f++) {
        float blk[hop];
        for (int i = 0; i < hop; i++) { int64_t s = f * hop + i; blk[i] = s < (int64_t)vocal.size() ? vocal[s] : 0.f; }
        a.push(blk, hop);
        int64_t centre = a.inPos - a.det.centreDelay();
        int64_t idx = centre / hop;
        if (idx >= 0 && idx < frames) { f0[idx] = a.det.f0; est = idx; }
    }
    (void)est;
    for (int64_t f = 0; f < frames; f++) {
        if (f0[f] <= 0) { tr.unvoiced(); continue; }
        double m = hzToMidi(f0[f], ref);
        det[f] = (float)m;
        outM[f] = (float)tr.step(m, (double)hop / sr, mask, style, speed, amount, keep, transpose);
    }
}

}  // namespace tt
