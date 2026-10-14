// Real-time pitch tracking and pitch shifting.
#pragma once
#include "dsp.h"

namespace tt {

// ================================================================= streaming YIN

/** YIN pitch detector running at sr/2, one estimate every 128 decimated samples. */
class PitchDetector {
public:
    void init(int sr, float fmin, float fmax) {
        sr_ = sr;
        dsr_ = sr / 2.0;
        tauMin_ = std::max(2, (int)std::floor(dsr_ / fmax));
        tauMax_ = (int)std::ceil(dsr_ / fmin);
        W_ = tauMax_ + 48;
        N_ = W_ + tauMax_ + 2;
        int f = 1; while (f < N_ + W_) f <<= 1;
        fft_.init(f);
        re_.assign(f, 0); im_.assign(f, 0); pr_.assign(f, 0); pi_.assign(f, 0);
        ring_.assign(4096, 0); rmask_ = 4095;
        frame_.assign(N_, 0); pre_.assign(N_ + 1, 0); cm_.assign(W_ + 1, 0);
        aa_.lowPass(sr, sr * 0.21, 0.7071); ab_.lowPass(sr, sr * 0.21, 0.7071);
        dpos_ = 0; phase_ = 0; sinceHop_ = 0;
        f0 = 0; conf = 0; prevF_ = 0; hist_[0] = hist_[1] = hist_[2] = 0; histN_ = 0; runMax_ = 0;
    }

    /** Push one input sample. Returns true when a new estimate is ready (every 2*HOP input samples). */
    inline bool push(float x) {
        float y = ab_.process(aa_.process(x));
        phase_ ^= 1;
        if (phase_) return false;
        ring_[dpos_ & rmask_] = y;
        dpos_++;
        if (++sinceHop_ < HOP || dpos_ < N_) return false;
        sinceHop_ = 0;
        analyze();
        return true;
    }

    static constexpr int HOP = 128;          // decimated samples per estimate
    int inputHop() const { return HOP * 2; }
    /** Input-rate distance from the newest sample back to the centre of the analysis window. */
    int centreDelay() const { return N_; }

    float f0 = 0;     // Hz, 0 = unvoiced
    float conf = 0;   // 0..1
    float rms = 0;

private:
    void analyze() {
        for (int j = 0; j < N_; j++) frame_[j] = ring_[(dpos_ - N_ + j) & rmask_];
        double s = 0;
        for (int j = 0; j < N_; j++) s += frame_[j] * frame_[j];
        rms = (float)std::sqrt(s / N_);
        runMax_ = std::max(runMax_ * 0.995f, rms);
        float gate = std::max(0.0025f, runMax_ * 0.025f);
        if (rms < gate) { setUnvoiced(); return; }

        int F = fft_.size();
        for (int j = 0; j < F; j++) {
            re_[j] = j < N_ ? frame_[j] : 0.f;
            im_[j] = j < W_ ? frame_[j] : 0.f;
        }
        fft_.run(re_.data(), im_.data(), false);
        for (int k = 0; k < F; k++) {
            int m = (F - k) & (F - 1);
            float ar = (re_[k] + re_[m]) * 0.5f, ai = (im_[k] - im_[m]) * 0.5f;
            float br = (im_[k] + im_[m]) * 0.5f, bi = -(re_[k] - re_[m]) * 0.5f;
            pr_[k] = ar * br + ai * bi;
            pi_[k] = ai * br - ar * bi;
        }
        fft_.run(pr_.data(), pi_.data(), true);   // pr_[tau] = sum_j x[j+tau] x[j]

        pre_[0] = 0;
        for (int j = 0; j < N_; j++) pre_[j + 1] = pre_[j] + (double)frame_[j] * frame_[j];
        double e1 = pre_[W_], run = 0;
        cm_[0] = 1;
        for (int tau = 1; tau <= tauMax_; tau++) {
            double e2 = pre_[tau + W_] - pre_[tau];
            double d = std::max(0.0, e1 + e2 - 2.0 * pr_[tau]);
            run += d;
            cm_[tau] = run > 0 ? (float)(d * tau / run) : 1.f;
        }
        bool wasVoiced = prevF_ > 0;
        float thresh = wasVoiced ? 0.22f : 0.14f;
        int best = -1;
        for (int tau = tauMin_; tau <= tauMax_; tau++) {
            if (cm_[tau] < thresh) {
                while (tau + 1 <= tauMax_ && cm_[tau + 1] < cm_[tau]) tau++;
                best = tau;
                break;
            }
        }
        // octave guard: prefer the period close to the previous one if it is also a good candidate
        if (wasVoiced) {
            int tp = (int)std::lround(dsr_ / prevF_);
            int lo = std::max(tauMin_, tp - 3), hi = std::min(tauMax_ - 1, tp + 3);
            int bp = -1; float bv = 1e9f;
            for (int t = lo; t <= hi; t++) if (cm_[t] < bv) { bv = cm_[t]; bp = t; }
            if (bp > 0 && bv < 0.3f) {
                if (best < 0) best = bp;
                else {
                    double r = std::log2((double)best / bp);
                    if (std::fabs(std::fabs(r) - 1.0) < 0.12) best = bp;
                }
            }
        }
        if (best < 0 || cm_[best] > 0.45f) { setUnvoiced(); return; }
        double t = best;
        if (best > 1 && best < tauMax_) {
            double a = cm_[best - 1], b = cm_[best], c = cm_[best + 1], den = a - 2 * b + c;
            if (std::fabs(den) > 1e-12) t = best + 0.5 * (a - c) / den;
        }
        float est = (float)(dsr_ / t);
        conf = 1.f - cm_[best];
        // 3-point median of recent voiced estimates (log domain)
        hist_[histN_ % 3] = std::log(est);
        histN_++;
        if (histN_ >= 3) {
            float a = hist_[0], b = hist_[1], c = hist_[2];
            float med = std::max(std::min(a, b), std::min(std::max(a, b), c));
            float cur = std::log(est);
            // keep the fresh value unless it is an outlier (keeps pitch changes responsive)
            if (std::fabs(cur - med) > 0.06f) est = std::exp(med);
        }
        f0 = est;
        prevF_ = est;
    }

    void setUnvoiced() { f0 = 0; conf = 0; prevF_ = 0; histN_ = 0; }

    int sr_ = 48000; double dsr_ = 24000;
    int tauMin_ = 24, tauMax_ = 300, W_ = 348, N_ = 650;
    FFT fft_;
    std::vector<float> re_, im_, pr_, pi_, ring_, frame_, cm_;
    std::vector<double> pre_;
    int rmask_ = 4095;
    int64_t dpos_ = 0; int phase_ = 0, sinceHop_ = 0;
    Biquad aa_, ab_;
    float prevF_ = 0, runMax_ = 0;
    float hist_[3]; int histN_ = 0;
};

// ================================================================= analyzer: input ring + marks + pitch history

class PitchAnalyzer {
public:
    static constexpr int RING = 1 << 15;
    static constexpr int MARKS = 4096;
    struct Mark { int64_t pos; int T; bool voiced; };

    void init(int sr, float fmin, float fmax, bool withMarks) {
        sr_ = sr; marksOn_ = withMarks;
        det.init(sr, fmin, fmax);
        ring_.assign(RING, 0.f);
        inPos = 0;
        mcount_ = 0; nextCand_ = 0; lastVoiced_ = false;
        hcount_ = 0;
        unvT_ = std::max(64, sr / 190);
        Tmax = (int)std::ceil(sr / fmin);
    }

    void push(const float* x, int n) {
        for (int i = 0; i < n; i++) {
            ring_[inPos & (RING - 1)] = x[i];
            inPos++;
            if (det.push(x[i])) {
                Hist& h = hist_[hcount_ % HIST];
                h.pos = inPos - det.centreDelay();
                h.f0 = det.f0;
                hcount_++;
            }
        }
        if (marksOn_) placeMarks();
    }

    inline float in(int64_t p) const { return p < 0 ? 0.f : ring_[p & (RING - 1)]; }
    /** 4-point cubic interpolation of the input at a fractional position. */
    inline float inFrac(double p) const {
        int64_t i = (int64_t)std::floor(p);
        float f = (float)(p - (double)i);
        float y0 = in(i - 1), y1 = in(i), y2 = in(i + 1), y3 = in(i + 2);
        float c1 = 0.5f * (y2 - y0), c2 = y0 - 2.5f * y1 + 2.f * y2 - 0.5f * y3, c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
        return ((c3 * f + c2) * f + c1) * f + y1;
    }

    /** Latest mark at or before t (nullptr if none). */
    const Mark* markAtOrBefore(double t) const {
        int64_t lo = std::max<int64_t>(0, mcount_ - MARKS + 1);
        for (int64_t k = mcount_ - 1; k >= lo; k--) {
            const Mark& m = marks_[k & (MARKS - 1)];
            if ((double)m.pos <= t) return &m;
        }
        return nullptr;
    }
    const Mark* lastMark() const { return mcount_ ? &marks_[(mcount_ - 1) & (MARKS - 1)] : nullptr; }

    /** Detected f0 (Hz) at input position t, interpolated; 0 when unvoiced. */
    double f0At(double t) const {
        if (hcount_ == 0) return 0;
        int64_t lo = std::max<int64_t>(0, hcount_ - HIST);
        const Hist* after = nullptr;
        for (int64_t k = hcount_ - 1; k >= lo; k--) {
            const Hist& h = hist_[k % HIST];
            if ((double)h.pos <= t) {
                if (!after) return h.f0;
                if (h.f0 <= 0 || after->f0 <= 0) return (t - h.pos < after->pos - t) ? h.f0 : after->f0;
                double fr = (t - h.pos) / (double)(after->pos - h.pos);
                return std::exp(std::log(h.f0) + (std::log(after->f0) - std::log(h.f0)) * fr);
            }
            after = &h;
        }
        return after ? after->f0 : 0;
    }

    PitchDetector det;
    int64_t inPos = 0;
    int Tmax = 600;

private:
    void placeMarks() {
        for (;;) {
            float f = det.f0;
            bool voiced = f > 0;
            int T = voiced ? std::max(20, (int)std::lround(sr_ / f)) : unvT_;
            int64_t cand = nextCand_;
            if (cand + T + T / 4 + 2 > inPos) break;
            int64_t pos = cand;
            if (voiced) {
                const Mark* prev = lastMark();
                if (!lastVoiced_ || !prev || cand - prev->pos > 2 * T) {
                    // voicing onset: anchor on the strongest peak of the coming period
                    float best = -1e9f;
                    for (int64_t k = cand; k < cand + T; k++) { float v = in(k); if (v > best) { best = v; pos = k; } }
                } else {
                    pos = refine(prev->pos, cand, T);
                }
            }
            Mark& m = marks_[mcount_ & (MARKS - 1)];
            m.pos = pos; m.T = T; m.voiced = voiced;
            mcount_++;
            lastVoiced_ = voiced;
            nextCand_ = pos + T;
        }
    }

    /** Waveform-similarity refinement: best match of the previous period around cand. */
    int64_t refine(int64_t prev, int64_t cand, int T) {
        int half = T / 2, R = std::max(2, T / 4);
        double e1 = 0;
        for (int j = -half; j < half; j++) { float a = in(prev + j); e1 += a * a; }
        double bestC = -2; int64_t best = cand;
        for (int64_t c = cand - R; c <= cand + R; c++) {
            double xy = 0, e2 = 0;
            for (int j = -half; j < half; j++) {
                float a = in(prev + j), b = in(c + j);
                xy += a * b; e2 += b * b;
            }
            double nc = xy / std::sqrt(e1 * e2 + 1e-12);
            if (nc > bestC) { bestC = nc; best = c; }
        }
        return bestC > 0.3 ? best : cand;
    }

    struct Hist { int64_t pos; float f0; };
    static constexpr int HIST = 256;
    int sr_ = 48000;
    bool marksOn_ = true;
    std::vector<float> ring_;
    Mark marks_[MARKS];
    int64_t mcount_ = 0, nextCand_ = 0;
    bool lastVoiced_ = false;
    int unvT_ = 256;
    Hist hist_[HIST];
    int64_t hcount_ = 0;
};

// ================================================================= note targeting

/** Turns a sung pitch into the pitch it should be corrected to. */
class TuneTracker {
public:
    static inline double TUNY_TAU = 0.0, WAVY_TAU = 0.005, TUNY_HYST = 0.3;
    // style: 0 Tuny (steady), 1 Crisp (snappy), 2 Wavy (loose)
    void reset() { prevNote_ = -1000; corr_ = 0; slow_ = 0; voiced_ = false; }

    /** m = sung pitch (MIDI, fractional). Returns corrected pitch (MIDI) including transpose. */
    double step(double m, double dt, const bool* mask, int style, float speedMs, float amount,
                float keepVibrato, float transpose, int* noteOut = nullptr) {
        double hyst = style == 0 ? TUNY_HYST : (style == 1 ? 0.03 : 0.15);
        double minTau = style == 0 ? TUNY_TAU : (style == 2 ? WAVY_TAU : 0.0);
        int cand = Scales::nearest(m, mask);
        if (voiced_ && cand != prevNote_ && mask[Scales::pc(prevNote_)] && std::fabs(m - prevNote_) < 0.5 + hyst) cand = prevNote_;
        if (!voiced_) slow_ = m;
        slow_ += (m - slow_) * (1 - std::exp(-dt / 0.12));
        double raw = cand - m + keepVibrato * (m - slow_);
        double tau = std::max<double>(speedMs / 1000.0, minTau);
        if (!voiced_) corr_ = tau < 0.002 ? raw : raw * 0.35;
        else if (tau < 0.0005) corr_ = raw;
        else corr_ += (raw - corr_) * (1 - std::exp(-dt / tau));
        voiced_ = true;
        prevNote_ = cand;
        if (noteOut) *noteOut = cand;
        return m + amount * corr_ + transpose;
    }
    void unvoiced() { voiced_ = false; }
    int note() const { return voiced_ ? prevNote_ : -1; }

private:
    int prevNote_ = -1000;
    double corr_ = 0, slow_ = 0;
    bool voiced_ = false;
};

// ================================================================= real-time TD-PSOLA synthesis

class PsolaSynth {
public:
    static inline bool NEAREST = false;
    static constexpr int RING = 1 << 15;

    void init(int sr, int latency) {
        sr_ = sr; L_ = latency;
        acc_.assign(RING, 0.f); w_.assign(RING, 0.f);
        outPos_ = -latency; ts_ = 0; started_ = false;
        unvH_ = std::max(64, sr / 190);
    }
    int latency() const { return L_; }

    /**
     * Produce n output samples (input delayed by latency, pitch-shifted).
     * hzAt(ts, mark) returns the desired output f0 for a voiced grain, or <= 0 for "no change".
     * grainScale < 1 gives shorter, crisper grains.
     */
    template <class F>
    void process(const PitchAnalyzer& a, float* out, int n, F&& hzAt, float grainScale) {
        const int64_t opEnd = outPos_ + n;
        if (!started_) {
            const PitchAnalyzer::Mark* m0 = a.lastMark();
            if (m0) { ts_ = (double)std::max<int64_t>(outPos_, 0); started_ = true; }
        }
        if (started_) synthesize(a, hzAt, grainScale);
        for (int i = 0; i < n; i++) {
            int64_t op = outPos_ + i;
            if (op < 0) { out[i] = 0; continue; }
            int idx = (int)(op & (RING - 1));
            float wv = w_[idx];
            out[i] = wv > 1e-3f ? acc_[idx] / std::max(wv, 0.5f) : a.in(op);
            acc_[idx] = 0; w_[idx] = 0;
        }
        outPos_ = opEnd;
        // never let synthesis fall behind the output
        if (started_ && ts_ < (double)outPos_) ts_ = (double)outPos_;
    }

private:
    template <class F>
    void synthesize(const PitchAnalyzer& a, F&& hzAt, float grainScale) {
        const PitchAnalyzer::Mark* last = a.lastMark();
        if (!last) return;
        for (int guard = 0; guard < 4096; guard++) {
            double look = NEAREST ? 0.5 * last->T : 0.0;
            if ((double)last->pos < ts_ + look) break;          // nearest mark not final yet
            const PitchAnalyzer::Mark* m = a.markAtOrBefore(ts_ + look);
            if (!m) { ts_ = (double)a.lastMark()->pos; continue; }
            if (!m->voiced) {
                int64_t c = (int64_t)std::llround(ts_);
                int H = unvH_;
                if (c + H > a.inPos) break;
                addGrain(a, (double)c, (double)c, H);
                ts_ = (double)c + H / 2;
                continue;
            }
            double hz = hzAt(ts_, *m);
            double S = hz > 0 ? sr_ / hz : (double)m->T;
            S = std::max(S, m->T * 0.3);
            int H = (int)std::ceil(std::max((double)m->T * grainScale, 0.75 * S));
            if (m->pos + H + 2 > a.inPos) break;
            addGrain(a, ts_, (double)m->pos, H);
            ts_ += S;
        }
    }

    /** Overlap-add a Hann-windowed grain of the input centred at src, placed at (fractional) dst. */
    void addGrain(const PitchAnalyzer& a, double dst, double src, int H) {
        int64_t k0 = (int64_t)std::ceil(dst - H), k1 = (int64_t)std::floor(dst + H);
        double off = src - dst;
        double invH = 1.0 / H;
        bool frac = std::fabs(off - std::round(off)) > 1e-6;
        for (int64_t k = k0; k <= k1; k++) {
            if (k < outPos_) continue;     // already emitted
            double d = (k - dst) * invH;
            float wv = (float)(0.5 + 0.5 * std::cos(PI * d));
            float x = frac ? a.inFrac(k + off) : a.in(k + (int64_t)std::llround(off));
            int idx = (int)(k & (RING - 1));
            acc_[idx] += x * wv;
            w_[idx] += wv;
        }
    }

    int sr_ = 48000, L_ = 1300, unvH_ = 252;
    std::vector<float> acc_, w_;
    int64_t outPos_ = 0;
    double ts_ = 0;
    bool started_ = false;
};

// ================================================================= spectral pitch shifter with formant preservation

/** Phase-vocoder pitch shift (75% overlap) that keeps the vocal formants in place. */
class SpectralShifter {
public:
    void init(int sr) {
        sr_ = sr;
        N_ = sr > 60000 ? 2048 : 1024;
        hop_ = N_ / 4;
        fft_.init(N_);
        cep_.init(N_);
        win_.resize(N_);
        for (int i = 0; i < N_; i++) win_[i] = (float)(0.5 - 0.5 * std::cos(2 * PI * i / N_));
        inBuf_.assign(N_, 0); outAcc_.assign(2 * N_, 0); outQ_.assign(N_, 0);
        re_.assign(N_, 0); im_.assign(N_, 0); cr_.assign(N_, 0); ci_.assign(N_, 0);
        lastPh_.assign(N_ / 2 + 1, 0); sumPh_.assign(N_ / 2 + 1, 0);
        mag_.assign(N_ / 2 + 1, 0); frq_.assign(N_ / 2 + 1, 0); env_.assign(N_ / 2 + 1, 0);
        smag_.assign(N_ / 2 + 1, 0); sfrq_.assign(N_ / 2 + 1, 0);
        fill_ = 0; outRead_ = 0;
    }
    int latency() const { return N_; }

    /** ratioFn() is asked once per hop for the current pitch ratio. */
    template <class F>
    void process(const float* x, float* out, int n, F&& ratioFn) {
        for (int i = 0; i < n; i++) {
            inBuf_[fill_++] = x[i];
            out[i] = outQ_[outRead_++];
            if (fill_ == N_) {
                frame((float)ratioFn());
                std::memmove(inBuf_.data(), inBuf_.data() + hop_, (N_ - hop_) * sizeof(float));
                fill_ = N_ - hop_;
            }
            if (outRead_ == hop_) outRead_ = 0;
        }
    }

private:
    void frame(float ratio) {
        const int half = N_ / 2;
        const double expct = 2 * PI * hop_ / N_;
        for (int i = 0; i < N_; i++) { re_[i] = inBuf_[i] * win_[i]; im_[i] = 0; }
        fft_.run(re_.data(), im_.data(), false);
        for (int k = 0; k <= half; k++) {
            float mg = std::sqrt(re_[k] * re_[k] + im_[k] * im_[k]);
            double ph = std::atan2(im_[k], re_[k]);
            double d = ph - lastPh_[k]; lastPh_[k] = ph;
            d -= k * expct;
            d -= 2 * PI * std::floor(d / (2 * PI) + 0.5);
            mag_[k] = mg;
            frq_[k] = (float)(k + d / expct);   // true frequency in bins
        }
        envelope();
        bool shift = std::fabs(ratio - 1.f) > 1e-4f;
        for (int k = 0; k <= half; k++) { smag_[k] = 0; sfrq_[k] = 0; }
        for (int k = 0; k <= half; k++) {
            int j = shift ? (int)std::lround(k * ratio) : k;
            if (j < 0 || j > half) continue;
            float flat = mag_[k] / std::max(env_[k], 1e-9f);
            if (flat * env_[j] >= smag_[j]) { smag_[j] = flat * env_[j]; sfrq_[j] = frq_[k] * ratio; }
        }
        for (int k = 0; k <= half; k++) {
            sumPh_[k] += sfrq_[k] * expct;
            re_[k] = smag_[k] * (float)std::cos(sumPh_[k]);
            im_[k] = smag_[k] * (float)std::sin(sumPh_[k]);
            if (k > 0 && k < half) { re_[N_ - k] = re_[k]; im_[N_ - k] = -im_[k]; }
        }
        im_[0] = 0; im_[half] = 0;
        fft_.run(re_.data(), im_.data(), true);
        const float norm = 1.f / 1.5f;     // hann^2 at 75% overlap sums to 1.5
        for (int i = 0; i < N_; i++) outAcc_[i] += re_[i] * win_[i] * norm;
        for (int i = 0; i < hop_; i++) outQ_[i] = outAcc_[i];
        std::memmove(outAcc_.data(), outAcc_.data() + hop_, (2 * N_ - hop_) * sizeof(float));
        std::fill(outAcc_.end() - hop_, outAcc_.end(), 0.f);
    }

    /** Cepstral spectral envelope (formants). */
    void envelope() {
        const int half = N_ / 2;
        for (int k = 0; k < N_; k++) {
            int kk = k <= half ? k : N_ - k;
            cr_[k] = std::log(std::max(mag_[kk], 1e-7f));
            ci_[k] = 0;
        }
        cep_.run(cr_.data(), ci_.data(), true);
        int lifter = (int)(sr_ * 0.0016);   // keep quefrencies below 1.6 ms
        for (int q = lifter; q <= N_ - lifter; q++) { cr_[q] = 0; ci_[q] = 0; }
        cep_.run(cr_.data(), ci_.data(), false);
        for (int k = 0; k <= half; k++) env_[k] = std::exp(cr_[k]);
    }

    int sr_ = 48000, N_ = 1024, hop_ = 256, fill_ = 0, outRead_ = 0;
    FFT fft_, cep_;
    std::vector<float> win_, inBuf_, outAcc_, outQ_, re_, im_, cr_, ci_;
    std::vector<double> lastPh_, sumPh_;
    std::vector<float> mag_, frq_, env_, smag_, sfrq_;
};

}  // namespace tt
