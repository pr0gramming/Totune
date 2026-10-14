// Rack modules. Every module processes a stereo block in place.
// Parameter indices must match ModuleType.java.
#pragma once
#include <memory>
#include "pitch.h"

namespace tt {

enum ModuleTypeId { M_TUNE = 0, M_HARMONY, M_VOCODER, M_DOUBLER, M_EQ, M_COMP, M_GRIT, M_CHORUS, M_DELAY, M_REVERB, M_COUNT };

constexpr int MAX_PARAMS = 16;
constexpr int MAX_BLOCK = 4096;

class Module {
public:
    virtual ~Module() = default;
    virtual void prepare(int sr, Globals* g) { sr_ = sr; g_ = g; }
    virtual void process(float* l, float* r, int n) = 0;
    virtual int latency() const { return 0; }
    /** Called instead of process() when the module is switched off. Default: pass through. */
    virtual void bypass(float* l, float* r, int n) { (void)l; (void)r; (void)n; }
    void setParam(int i, float v) { if (i >= 0 && i < MAX_PARAMS) p_[i].store(v, std::memory_order_relaxed); }
    float param(int i) const { return p_[i].load(std::memory_order_relaxed); }
    std::atomic<bool> on{true};
    int id = 0, type = 0;
protected:
    int sr_ = 48000;
    Globals* g_ = nullptr;
    std::atomic<float> p_[MAX_PARAMS] = {};
    void scaleMask(bool* m) const { Scales::mask(g_->key.load(), g_->scale.load(), m); }
};

/** Keeps dry signal aligned with a module's latency. */
class DryDelay {
public:
    void init(int maxD) { l_.init(maxD + 8); r_.init(maxD + 8); }
    inline void push(float l, float r) { l_.push(l); r_.push(r); }
    inline float l(int d) const { return l_.tap(d); }
    inline float r(int d) const { return r_.tap(d); }
private:
    DelayLine l_, r_;
};

inline float voiceRangeMin(int range) { return range == 0 ? 75.f : (range == 1 ? 105.f : 150.f); }

// ================================================================= TUNE
// 0 style (0 Tuny, 1 Crisp, 2 Wavy), 1 speed ms, 2 amount %, 3 keep vibrato %, 4 transpose st, 5 mix %, 6 range (0 low,1 mid,2 high)
class TuneModule : public Module {
public:
    static inline double LATF = 2.9;
    void prepare(int sr, Globals* g) override {
        Module::prepare(sr, g);
        range_ = -1;
        configure();
        dry_.init(sr);
        mono_.assign(MAX_BLOCK, 0); wet_.assign(MAX_BLOCK, 0);
    }
    int latency() const override { return lat_; }
    void bypass(float* l, float* r, int n) override { off_ = true; process(l, r, n); off_ = false; }

    void process(float* l, float* r, int n) override {
        int range = (int)param(6);
        if (range != range_) configure();
        int style = (int)param(0);
        float mix = off_ ? 0.f : clampf(param(5) / 100.f, 0, 1);
        for (int i = 0; i < n; i++) mono_[i] = 0.5f * (l[i] + r[i]);
        bool mask[12]; scaleMask(mask);
        double ref = g_->refHz.load();
        float speed = param(1), amount = param(2) / 100.f, keep = param(3) / 100.f, tr = param(4);

        an_.push(mono_.data(), n);
        if (style == 2) {
            spec_.process(mono_.data(), wet_.data(), n, [&]() {
                double hz = an_.det.f0;
                if (hz <= 0) { tracker_.unvoiced(); return 1.0; }
                double m = hzToMidi(hz, ref);
                double o = tracker_.step(m, spec_.latency() / 4.0 / sr_, mask, 2, speed, amount, keep, tr);
                return std::pow(2.0, (o - m) / 12.0);
            });
            // align with the PSOLA latency so switching style doesn't shift timing
            for (int i = 0; i < n; i++) { wetDelay_.push(wet_[i]); wet_[i] = wetDelay_.tap(lat_ - spec_.latency()); }
        } else {
            float gs = style == 1 ? 0.82f : 1.0f;
            psola_.process(an_, wet_.data(), n, [&](double ts, const PitchAnalyzer::Mark& mk) {
                double hz = an_.f0At(ts);
                if (hz <= 0) hz = (double)sr_ / mk.T;
                double m = hzToMidi(hz, ref);
                if (ts - lastTs_ > 2.5 * mk.T) tracker_.unvoiced();
                double dt = std::max(1.0, ts - lastTs_) / sr_;
                lastTs_ = ts;
                double o = tracker_.step(m, std::min(dt, 0.02), mask, style, speed, amount, keep, tr);
                return midiToHz(o, ref);
            }, gs);
        }
        for (int i = 0; i < n; i++) {
            dry_.push(l[i], r[i]);
            float dl = dry_.l(lat_), dr = dry_.r(lat_);
            float w = wet_[i];
            if (AIR && style != 2) {
                // breath and sibilance above ~7 kHz carry almost no pitch: keep them from the original
                float dm = 0.5f * (dl + dr);
                float hd = airD2_.process(airD1_.process(dm)), hw = airW2_.process(airW1_.process(w));
                w += hd - hw;
            }
            l[i] = dl + (w - dl) * mix;
            r[i] = dr + (w - dr) * mix;
        }
    }
    static inline bool AIR = false;

private:
    void configure() {
        airD1_.highPass(sr_, 7000, 0.7071); airD2_.highPass(sr_, 7000, 0.7071);
        airW1_.highPass(sr_, 7000, 0.7071); airW2_.highPass(sr_, 7000, 0.7071);
        range_ = (int)param(6);
        float fmin = voiceRangeMin(range_);
        an_.init(sr_, fmin, 1100.f, true);
        int T = (int)std::ceil(sr_ / fmin);
        spec_.init(sr_);
        lat_ = std::max((int)std::ceil(LATF * T), spec_.latency());
        psola_.init(sr_, lat_);
        wetDelay_.init(sr_);
        tracker_.reset();
        lastTs_ = -1e9;
    }
    PitchAnalyzer an_;
    PsolaSynth psola_;
    SpectralShifter spec_;
    TuneTracker tracker_;
    DryDelay dry_;
    DelayLine wetDelay_;
    Biquad airD1_, airD2_, airW1_, airW2_;
    std::vector<float> mono_, wet_;
    int range_ = -1, lat_ = 1300;
    double lastTs_ = -1e9;
    bool off_ = false;
};

// ================================================================= HARMONY
// 0 voices (0 3rd up,1 5th up,2 3rd+5th up,3 3rd down,4 octave down,5 octave up), 1 level %, 2 width %, 3 tightness %, 4 range
class HarmonyModule : public Module {
public:
    void prepare(int sr, Globals* g) override {
        Module::prepare(sr, g);
        range_ = -1; configure();
        dry_.init(sr);
        mono_.assign(MAX_BLOCK, 0); v_[0].assign(MAX_BLOCK, 0); v_[1].assign(MAX_BLOCK, 0);
    }
    int latency() const override { return lat_; }
    void bypass(float* l, float* r, int n) override { off_ = true; process(l, r, n); off_ = false; }
    void process(float* l, float* r, int n) override {
        if ((int)param(4) != range_) configure();
        static const int modes[6][2] = {{2, 0}, {4, 0}, {2, 4}, {-2, 0}, {-100, 0}, {100, 0}};
        int mode = std::max(0, std::min(5, (int)param(0)));
        int voices = modes[mode][1] != 0 ? 2 : 1;
        float level = off_ ? 0.f : param(1) / 100.f, width = param(2) / 100.f, tight = param(3) / 100.f;
        bool mask[12]; scaleMask(mask);
        int scale = g_->scale.load();
        double ref = g_->refHz.load();
        for (int i = 0; i < n; i++) mono_[i] = 0.5f * (l[i] + r[i]);
        an_.push(mono_.data(), n);
        for (int v = 0; v < voices; v++) {
            int steps = modes[mode][v];
            syn_[v].process(an_, v_[v].data(), n, [&](double ts, const PitchAnalyzer::Mark& mk) {
                double hz = an_.f0At(ts);
                if (hz <= 0) hz = (double)sr_ / mk.T;
                double m = hzToMidi(hz, ref);
                if (ts - last_[v] > 2.5 * mk.T) tr_[v].unvoiced();
                last_[v] = ts;
                int note = 0;
                tr_[v].step(m, 0.005, mask, 0, 0, 1, 0, 0, &note);
                int h;
                if (steps == 100) h = note + 12;
                else if (steps == -100) h = note - 12;
                else if (scale == 2) h = note + (steps == 2 ? 4 : steps == 4 ? 7 : -4);
                else h = Scales::shift(note, steps, mask);
                double o = h + (m - note) * (1 - tight);
                return midiToHz(o, ref);
            }, 1.0f);
        }
        for (int i = 0; i < n; i++) {
            dry_.push(l[i], r[i]);
            float dl = dry_.l(lat_), dr = dry_.r(lat_);
            float hl = 0, hr = 0;
            for (int v = 0; v < voices; v++) {
                float pan = voices == 1 ? 0.3f * width : (v == 0 ? -width : width) * 0.8f;
                float gl = (float)std::cos((pan + 1) * PI / 4) * 1.414f, gr = (float)std::sin((pan + 1) * PI / 4) * 1.414f;
                hl += v_[v][i] * gl; hr += v_[v][i] * gr;
            }
            l[i] = dl + hl * level * 0.8f;
            r[i] = dr + hr * level * 0.8f;
        }
    }
private:
    void configure() {
        range_ = (int)param(4);
        float fmin = voiceRangeMin(range_);
        an_.init(sr_, fmin, 1100.f, true);
        int T = (int)std::ceil(sr_ / fmin);
        lat_ = (int)std::ceil(3.4 * T);     // room for downward shifts
        for (int v = 0; v < 2; v++) { syn_[v].init(sr_, lat_); tr_[v].reset(); last_[v] = -1e9; }
    }
    PitchAnalyzer an_;
    PsolaSynth syn_[2];
    TuneTracker tr_[2];
    double last_[2] = {-1e9, -1e9};
    DryDelay dry_;
    std::vector<float> mono_, v_[2];
    int range_ = -1, lat_ = 1500;
    bool off_ = false;
};

// ================================================================= VOCODER
// 0 mode (0 chords,1 melody,2 monotone,3 deep), 1 mix %, 2 air %
class VocoderModule : public Module {
public:
    static constexpr int BANDS = 16;
    void prepare(int sr, Globals* g) override {
        Module::prepare(sr, g);
        an_.init(sr, 75.f, 1100.f, false);
        double lo = 180, hi = 7000, ratio = std::pow(hi / lo, 1.0 / (BANDS - 1));
        double q = 1.0 / (ratio - 1.0 / ratio) * 0.9;
        for (int b = 0; b < BANDS; b++) {
            double fc = lo * std::pow(ratio, b);
            for (int k = 0; k < 2; k++) { mf_[b][k].bandPass(sr, fc, q); cf_[b][k].bandPass(sr, fc, q); }
            env_[b] = 0;
            tilt_[b] = 0.7f + 0.6f * b / (BANDS - 1.f);
        }
        atk_ = 1 - std::exp(-1.0 / (sr * 0.004)); rel_ = 1 - std::exp(-1.0 / (sr * 0.035));
        air1_.highPass(sr, 5500, 0.7071); air2_.highPass(sr, 5500, 0.7071);
        for (int v = 0; v < 8; v++) { ph_[v] = 0.13 * v; hz_[v] = 0; }
        for (int i = 0; i < 4; i++) notes_[i] = 48;
        mono_.assign(MAX_BLOCK, 0);
        gain_ = 1; ev_ = eo_ = 1e-6;
    }
    void process(float* l, float* r, int n) override {
        int mode = (int)param(0);
        float mix = clampf(param(1) / 100.f, 0, 1), air = param(2) / 100.f;
        bool mask[12]; scaleMask(mask);
        double ref = g_->refHz.load();
        int key = g_->key.load(), scale = g_->scale.load();
        for (int i = 0; i < n; i++) mono_[i] = 0.5f * (l[i] + r[i]);
        an_.push(mono_.data(), n);
        // carrier notes from the latest pitch
        double f = an_.det.f0;
        int voices = mode == 0 ? 4 : 2;
        if (f > 0) {
            double m = hzToMidi(f, ref);
            int note = 0;
            double o = tr_.step(m, (double)n / sr_, mask, 0, 0, 1, 0, 0, &note);
            if (mode == 0) {
                int third = scale == 2 ? note + 4 : Scales::shift(note, 2, mask);
                int fifth = scale == 2 ? note + 7 : Scales::shift(note, 4, mask);
                notes_[0] = note; notes_[1] = third; notes_[2] = fifth; notes_[3] = note - 12;
            } else if (mode == 1) { notes_[0] = o; notes_[1] = o - 12; }
            else if (mode == 3) { notes_[0] = o - 12; notes_[1] = o - 24; }
        } else tr_.unvoiced();
        if (mode == 2) { notes_[0] = 48 + key; notes_[1] = 36 + key; }
        double glide = 1 - std::exp(-1.0 / (sr_ * 0.012));
        for (int i = 0; i < n; i++) {
            float x = mono_[i];
            float c = 0;
            for (int v = 0; v < voices; v++) {
                double hz = midiToHz(notes_[v], ref);
                if (hz_[v] <= 0) hz_[v] = hz;
                hz_[v] += glide * (hz - hz_[v]);
                for (int d = 0; d < 2; d++) {
                    int k = v * 2 + d;
                    double inc = hz_[v] * (d ? 1.0035 : 0.9965) / sr_;
                    ph_[k] += inc; if (ph_[k] >= 1) ph_[k] -= 1;
                    c += saw(ph_[k], inc);
                }
            }
            c *= 0.5f / voices;
            float y = 0;
            for (int b = 0; b < BANDS; b++) {
                float mv = mf_[b][1].process(mf_[b][0].process(x));
                float a = std::fabs(mv);
                env_[b] += (a > env_[b] ? atk_ : rel_) * (a - env_[b]);
                y += cf_[b][1].process(cf_[b][0].process(c)) * env_[b] * tilt_[b];
            }
            // running loudness match to the dry voice
            ev_ = ev_ * 0.9995 + x * x * 0.0005; eo_ = eo_ * 0.9995 + y * y * 0.0005;
            gain_ += 0.0005f * ((float)std::sqrt(ev_ / (eo_ + 1e-12)) - gain_);
            float a2 = air2_.process(air1_.process(x));
            float robot = y * std::min(gain_, 40.f) + a2 * air;
            l[i] = l[i] + (robot - l[i]) * mix;
            r[i] = r[i] + (robot - r[i]) * mix;
        }
    }
private:
    static inline float saw(double t, double dt) {
        double v = 2 * t - 1;
        if (t < dt) { double x = t / dt; v -= x + x - x * x - 1; }
        else if (t > 1 - dt) { double x = (t - 1) / dt; v -= x * x + x + x + 1; }
        return (float)v;
    }
    PitchAnalyzer an_;
    TuneTracker tr_;
    Biquad mf_[BANDS][2], cf_[BANDS][2], air1_, air2_;
    float env_[BANDS], tilt_[BANDS];
    double atk_ = 0, rel_ = 0, ph_[8], hz_[8], notes_[4], ev_ = 0, eo_ = 0;
    float gain_ = 1;
    std::vector<float> mono_;
};

// ================================================================= DOUBLER
// 0 level %, 1 detune cents, 2 width %, 3 delay ms
class DoublerModule : public Module {
public:
    void prepare(int sr, Globals* g) override {
        Module::prepare(sr, g);
        for (int v = 0; v < 2; v++) { d_[v].init((int)(sr * 0.1)); lfo_[v].init(sr, 0.35 + 0.17 * v, 11 + v * 7); lp_[v].lowPass(sr, 9000, 0.7071); }
    }
    void process(float* l, float* r, int n) override {
        float level = param(0) / 100.f * 0.7f, det = param(1), width = param(2) / 100.f, base = param(3) / 1000.f;
        for (int i = 0; i < n; i++) {
            float x = 0.5f * (l[i] + r[i]);
            float o[2];
            for (int v = 0; v < 2; v++) {
                d_[v].push(x);
                // depth chosen so the drift reaches about +-det cents
                double depth = det * 0.000578 / (2 * PI * (0.35 + 0.17 * v)) * sr_;
                float dl = (float)(base * sr_ * (v ? 1.6 : 1.0) + depth * (1.2 + lfo_[v].tick()));
                o[v] = lp_[v].process(d_[v].read(std::max(1.f, dl)));
            }
            l[i] += (o[0] * (0.5f + 0.5f * width) + o[1] * (0.5f - 0.5f * width)) * level;
            r[i] += (o[1] * (0.5f + 0.5f * width) + o[0] * (0.5f - 0.5f * width)) * level;
        }
    }
private:
    DelayLine d_[2];
    DriftLfo lfo_[2];
    Biquad lp_[2];
};

// ================================================================= EQ
// 0 lowcut on, 1 lowcut Hz, 2 low dB, 3 low Hz, 4 mid dB, 5 mid Hz, 6 mid Q, 7 high dB, 8 high Hz, 9 highcut on, 10 highcut Hz
class EqModule : public Module {
public:
    void prepare(int sr, Globals* g) override { Module::prepare(sr, g); for (float& v : last_) v = -12345; }
    void process(float* l, float* r, int n) override {
        update();
        for (int i = 0; i < n; i++) {
            float a = l[i], b = r[i];
            for (int k = 0; k < 6; k++) if (act_[k]) { a = f_[k][0].process(a); b = f_[k][1].process(b); }
            l[i] = a; r[i] = b;
        }
    }
private:
    void update() {
        bool changed = false;
        for (int i = 0; i < 11; i++) if (param(i) != last_[i]) { changed = true; last_[i] = param(i); }
        if (!changed) return;
        for (int c = 0; c < 2; c++) {
            f_[0][c].highPass(sr_, param(1), 0.7071);
            f_[1][c].lowShelf(sr_, param(3), param(2));
            f_[2][c].peak(sr_, param(5), std::max(0.1f, param(6)), param(4));
            f_[3][c].highShelf(sr_, param(8), param(7));
            f_[4][c].lowPass(sr_, param(10), 0.7071);
        }
        act_[0] = param(0) > 0.5f; act_[1] = std::fabs(param(2)) > 0.05f; act_[2] = std::fabs(param(4)) > 0.05f;
        act_[3] = std::fabs(param(7)) > 0.05f; act_[4] = param(9) > 0.5f; act_[5] = false;
    }
    Biquad f_[6][2];
    bool act_[6] = {};
    float last_[11];
};

// ================================================================= COMP
// 0 amount %, 1 attack ms, 2 release ms, 3 output dB
class CompModule : public Module {
public:
    void process(float* l, float* r, int n) override {
        float k = clampf(param(0) / 100.f, 0, 1);
        double thresh = -6 - 30 * k, slope = 1 - 1 / (2 + 6 * k), makeup = 1 + 7 * k + param(3);
        double atk = std::exp(-1.0 / (sr_ * std::max(0.1f, param(1)) / 1000.0));
        double rel = std::exp(-1.0 / (sr_ * std::max(1.f, param(2)) / 1000.0));
        const double knee = 6;
        for (int i = 0; i < n; i++) {
            double pk = std::max(std::fabs(l[i]), std::fabs(r[i]));
            double lvl = 20 * std::log10(pk + 1e-9), over = lvl - thresh, tgt;
            if (over <= -knee / 2) tgt = 0;
            else if (over >= knee / 2) tgt = over * slope;
            else { double o = over + knee / 2; tgt = slope * o * o / (2 * knee); }
            g_db_ = tgt > g_db_ ? atk * g_db_ + (1 - atk) * tgt : rel * g_db_ + (1 - rel) * tgt;
            float gain = (float)std::pow(10.0, (makeup - g_db_) / 20.0);
            l[i] *= gain; r[i] *= gain;
        }
    }
private:
    double g_db_ = 0;
};

// ================================================================= GRIT
// 0 drive %, 1 tone %, 2 mix %
class GritModule : public Module {
public:
    void process(float* l, float* r, int n) override {
        float d = clampf(param(0) / 100.f, 0, 1), mix = clampf(param(2) / 100.f, 0, 1);
        float tone = 3000 + 12000 * clampf(param(1) / 100.f, 0, 1);
        if (tone != lastTone_) { lp_[0].lowPass(sr_, tone, 0.7071); lp_[1].lowPass(sr_, tone, 0.7071); lastTone_ = tone; }
        float g = 1 + d * 14, norm = 1.f / std::tanh(g * 0.5f) * 0.5f;
        for (int i = 0; i < n; i++) {
            float wl = lp_[0].process(std::tanh(l[i] * g) * norm), wr = lp_[1].process(std::tanh(r[i] * g) * norm);
            l[i] += (wl - l[i]) * mix;
            r[i] += (wr - r[i]) * mix;
        }
    }
private:
    Biquad lp_[2];
    float lastTone_ = -1;
};

// ================================================================= CHORUS
// 0 mix %, 1 depth %, 2 rate Hz
class ChorusModule : public Module {
public:
    void prepare(int sr, Globals* g) override { Module::prepare(sr, g); d_[0].init(sr / 10); d_[1].init(sr / 10); }
    void process(float* l, float* r, int n) override {
        float mix = clampf(param(0) / 100.f, 0, 1), depth = clampf(param(1) / 100.f, 0, 1), rate = std::max(0.05f, param(2));
        double base = sr_ * 0.014, dep = sr_ * (0.0015 + 0.004 * depth);
        for (int i = 0; i < n; i++) {
            d_[0].push(l[i]); d_[1].push(r[i]);
            ph_ += rate / sr_; if (ph_ >= 1) ph_ -= 1;
            float wl = 0, wr = 0;
            for (int v = 0; v < 2; v++) {
                double a = 2 * PI * (ph_ * (v ? 1.37 : 1.0) + v * 0.33);
                wl += d_[0].read((float)(base + dep * (1 + std::sin(a)) * 0.5));
                wr += d_[1].read((float)(base + dep * (1 + std::sin(a + PI / 2)) * 0.5));
            }
            l[i] = l[i] * (1 - mix * 0.5f) + wl * 0.5f * mix;
            r[i] = r[i] * (1 - mix * 0.5f) + wr * 0.5f * mix;
        }
    }
private:
    DelayLine d_[2];
    double ph_ = 0;
};

// ================================================================= DELAY
// 0 mix %, 1 division, 2 feedback %, 3 ping-pong, 4 tone Hz
class DelayModule : public Module {
public:
    void prepare(int sr, Globals* g) override {
        Module::prepare(sr, g);
        bl_.init(sr * 4); br_.init(sr * 4);
        hp_[0].highPass(sr, 180, 0.7071); hp_[1].highPass(sr, 180, 0.7071);
        cur_ = -1;
    }
    void process(float* l, float* r, int n) override {
        static const double beats[7] = {2, 1, 1.5, 0.5, 0.75, 1.0 / 3.0, 0.25};
        int div = std::max(0, std::min(6, (int)param(1)));
        double d = 60.0 / std::max(30.f, g_->bpm.load()) * beats[div] * sr_;
        d = std::max(1.0, std::min(d, sr_ * 3.9));
        if (cur_ < 0) cur_ = d;
        float mix = clampf(param(0) / 100.f, 0, 1), fb = clampf(param(2) / 100.f, 0, 0.95f);
        bool pp = param(3) > 0.5f;
        float tone = param(4);
        if (tone != lastTone_) { lp_[0].lowPass(sr_, tone, 0.7071); lp_[1].lowPass(sr_, tone, 0.7071); lastTone_ = tone; }
        for (int i = 0; i < n; i++) {
            cur_ += (d - cur_) * 0.0008;
            float yl = bl_.read((float)cur_ - 1), yr = br_.read((float)cur_ - 1);
            float fl = hp_[0].process(lp_[0].process(yl)), fr = hp_[1].process(lp_[1].process(yr));
            float inL = l[i], inR = r[i];
            if (pp) { bl_.push(0.5f * (inL + inR) + fb * fr); br_.push(fb * fl); }
            else { bl_.push(inL + fb * fl); br_.push(inR + fb * fr); }
            l[i] += yl * mix; r[i] += yr * mix;
        }
    }
private:
    DelayLine bl_, br_;
    Biquad lp_[2], hp_[2];
    double cur_ = -1;
    float lastTone_ = -1;
};

// ================================================================= REVERB (8-line feedback delay network)
// 0 mix %, 1 size %, 2 decay s, 3 damping %, 4 pre-delay ms, 5 width %, 6 low cut Hz
class ReverbModule : public Module {
public:
    static constexpr int N = 8;
    void prepare(int sr, Globals* g) override {
        Module::prepare(sr, g);
        pre_.init(sr / 4);
        static const int ap[4] = {142, 107, 379, 277};
        for (int i = 0; i < 4; i++) { apd_[i] = (int)(ap[i] * sr / 48000.0); apb_[i].assign(apd_[i] + 1, 0); api_[i] = 0; }
        for (int i = 0; i < N; i++) { line_[i].init((int)(sr * 0.25)); lfo_[i].init(sr, 0.11 + 0.07 * i, 101 + i * 13); lp_[i] = 0; }
        last_[0] = last_[1] = last_[2] = last_[3] = -1;
        hp_[0].highPass(sr, 180, 0.7071); hp_[1].highPass(sr, 180, 0.7071);
        inLp_.lowPass(sr, 9000, 0.7071);
    }
    void process(float* l, float* r, int n) override {
        update();
        float mix = clampf(param(0) / 100.f, 0, 1), width = clampf(param(5) / 100.f, 0, 1);
        int pd = (int)(clampf(param(4), 0, 200) * sr_ / 1000.f);
        float dry = 1.f - 0.45f * mix;
        float w1 = mix * (0.5f + 0.5f * width), w2 = mix * (0.5f - 0.5f * width);
        for (int i = 0; i < n; i++) {
            float x = 0.5f * (l[i] + r[i]);
            pre_.push(x);
            float in = inLp_.process(pre_.tap(pd));
            in = hpIn_.process(in);
            // input diffusion
            for (int a = 0; a < 4; a++) {
                float bo = apb_[a][api_[a]];
                float v = in + 0.6f * bo;
                apb_[a][api_[a]] = v;
                in = bo - 0.6f * v;
                if (++api_[a] > apd_[a] - 1) api_[a] = 0;
            }
            // read lines (slightly modulated to avoid metallic ringing)
            float y[N];
            for (int k = 0; k < N; k++) {
                float d = len_[k] + (k < 4 ? 6.f * lfo_[k].tick() : 0.f);
                float v = line_[k].read(d);
                lp_[k] += (v - lp_[k]) * damp_;   // high-frequency damping
                y[k] = lp_[k] * gain_[k];
            }
            // Hadamard mix
            float h[N];
            hadamard(y, h);
            for (int k = 0; k < N; k++) line_[k].push(h[k] + in * ((k & 1) ? 0.6f : -0.6f));
            float ol = (y[0] - y[2] + y[4] - y[6]) * 0.6f, orr = (y[1] - y[3] + y[5] - y[7]) * 0.6f;
            ol = hp_[0].process(ol); orr = hp_[1].process(orr);
            float a = l[i], b = r[i];
            l[i] = a * dry + ol * w1 + orr * w2;
            r[i] = b * dry + orr * w1 + ol * w2;
        }
    }
private:
    static void hadamard(const float* x, float* o) {
        float a[8];
        for (int i = 0; i < 8; i++) a[i] = x[i];
        for (int s = 1; s < 8; s <<= 1)
            for (int i = 0; i < 8; i += 2 * s)
                for (int j = i; j < i + s; j++) { float u = a[j], v = a[j + s]; a[j] = u + v; a[j + s] = u - v; }
        const float k = 0.35355339f;   // 1/sqrt(8)
        for (int i = 0; i < 8; i++) o[i] = a[i] * k;
    }
    void update() {
        float size = clampf(param(1) / 100.f, 0, 1), decay = std::max(0.2f, param(2)), dampP = clampf(param(3) / 100.f, 0, 1), lc = param(6);
        if (size == last_[0] && decay == last_[1] && dampP == last_[2] && lc == last_[3]) return;
        last_[0] = size; last_[1] = decay; last_[2] = dampP; last_[3] = lc;
        static const int base[N] = {1123, 1291, 1451, 1607, 1801, 1949, 2111, 2293};
        double scale = (0.35 + 1.25 * size) * sr_ / 48000.0;
        for (int k = 0; k < N; k++) {
            len_[k] = (float)(base[k] * scale);
            gain_[k] = (float)std::pow(10.0, -3.0 * len_[k] / (sr_ * decay));
        }
        damp_ = (float)(1 - std::exp(-2 * PI * (14000 - 11000 * dampP) / sr_));
        hp_[0].highPass(sr_, lc, 0.7071); hp_[1].highPass(sr_, lc, 0.7071);
        hpIn_.highPass(sr_, lc, 0.7071);
    }
    DelayLine pre_, line_[N];
    std::vector<float> apb_[4];
    int apd_[4], api_[4];
    DriftLfo lfo_[N];
    float lp_[N], len_[N], gain_[N], damp_ = 0.5f, last_[4];
    Biquad hp_[2], hpIn_, inLp_;
};

inline std::unique_ptr<Module> makeModule(int type) {
    switch (type) {
        case M_TUNE: return std::make_unique<TuneModule>();
        case M_HARMONY: return std::make_unique<HarmonyModule>();
        case M_VOCODER: return std::make_unique<VocoderModule>();
        case M_DOUBLER: return std::make_unique<DoublerModule>();
        case M_EQ: return std::make_unique<EqModule>();
        case M_COMP: return std::make_unique<CompModule>();
        case M_GRIT: return std::make_unique<GritModule>();
        case M_CHORUS: return std::make_unique<ChorusModule>();
        case M_DELAY: return std::make_unique<DelayModule>();
        case M_REVERB: return std::make_unique<ReverbModule>();
        default: return nullptr;
    }
}

}  // namespace tt
