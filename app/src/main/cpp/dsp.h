// Totune real-time DSP building blocks. Portable C++17, no platform headers.
#pragma once
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace tt {

constexpr double PI = 3.14159265358979323846;

inline float clampf(float v, float a, float b) { return v < a ? a : (v > b ? b : v); }
inline float dbToLin(float db) { return std::pow(10.f, db / 20.f); }
inline double midiToHz(double m, double ref) { return ref * std::pow(2.0, (m - 69.0) / 12.0); }
inline double hzToMidi(double hz, double ref) { return 69.0 + 12.0 * std::log2(hz / ref); }

// ---------------------------------------------------------------- biquad (RBJ cookbook)

struct Biquad {
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    float z1 = 0, z2 = 0;

    inline float process(float x) {
        float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
    void reset() { z1 = z2 = 0; }
    void identity() { b0 = 1; b1 = b2 = a1 = a2 = 0; }

    void set(double B0, double B1, double B2, double A0, double A1, double A2) {
        b0 = (float)(B0 / A0); b1 = (float)(B1 / A0); b2 = (float)(B2 / A0);
        a1 = (float)(A1 / A0); a2 = (float)(A2 / A0);
    }
    static double w(double sr, double f) { return 2 * PI * std::min(f, sr * 0.49) / sr; }

    void lowPass(double sr, double f, double q) {
        double W = w(sr, f), c = std::cos(W), al = std::sin(W) / (2 * q);
        set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al);
    }
    void highPass(double sr, double f, double q) {
        double W = w(sr, f), c = std::cos(W), al = std::sin(W) / (2 * q);
        set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al);
    }
    void bandPass(double sr, double f, double q) {
        double W = w(sr, f), c = std::cos(W), al = std::sin(W) / (2 * q);
        set(al, 0, -al, 1 + al, -2 * c, 1 - al);
    }
    void peak(double sr, double f, double q, double db) {
        double A = std::pow(10, db / 40), W = w(sr, f), c = std::cos(W), al = std::sin(W) / (2 * q);
        set(1 + al * A, -2 * c, 1 - al * A, 1 + al / A, -2 * c, 1 - al / A);
    }
    void lowShelf(double sr, double f, double db) {
        double A = std::pow(10, db / 40), W = w(sr, f), c = std::cos(W), s = std::sin(W);
        double sa = 2 * std::sqrt(A) * (s / 2 * std::sqrt(2.0));
        set(A * ((A + 1) - (A - 1) * c + sa), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - sa),
            (A + 1) + (A - 1) * c + sa, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - sa);
    }
    void highShelf(double sr, double f, double db) {
        double A = std::pow(10, db / 40), W = w(sr, f), c = std::cos(W), s = std::sin(W);
        double sa = 2 * std::sqrt(A) * (s / 2 * std::sqrt(2.0));
        set(A * ((A + 1) + (A - 1) * c + sa), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - sa),
            (A + 1) - (A - 1) * c + sa, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - sa);
    }
};

// ---------------------------------------------------------------- FFT (complex, radix 2)

class FFT {
public:
    explicit FFT(int n = 1024) { init(n); }
    void init(int n) {
        n_ = n;
        cs_.resize(n / 2); sn_.resize(n / 2); rev_.resize(n);
        for (int i = 0; i < n / 2; i++) { cs_[i] = (float)std::cos(-2 * PI * i / n); sn_[i] = (float)std::sin(-2 * PI * i / n); }
        int bits = 0; while ((1 << bits) < n) bits++;
        for (int i = 0; i < n; i++) {
            int r = 0; for (int b = 0; b < bits; b++) if (i & (1 << b)) r |= 1 << (bits - 1 - b);
            rev_[i] = r;
        }
    }
    int size() const { return n_; }
    // inverse = true scales by 1/n
    void run(float* re, float* im, bool inverse) const {
        int n = n_;
        for (int i = 0; i < n; i++) { int j = rev_[i]; if (j > i) { std::swap(re[i], re[j]); std::swap(im[i], im[j]); } }
        float sg = inverse ? -1.f : 1.f;
        for (int size = 2; size <= n; size <<= 1) {
            int half = size >> 1, step = n / size;
            for (int i = 0; i < n; i += size) {
                for (int j = 0, k = 0; j < half; j++, k += step) {
                    float wr = cs_[k], wi = sg * sn_[k];
                    int a = i + j, b = a + half;
                    float tr = re[b] * wr - im[b] * wi, ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti;
                    re[a] += tr; im[a] += ti;
                }
            }
        }
        if (inverse) { float s = 1.f / n; for (int i = 0; i < n; i++) { re[i] *= s; im[i] *= s; } }
    }
private:
    int n_ = 0;
    std::vector<float> cs_, sn_;
    std::vector<int> rev_;
};

// ---------------------------------------------------------------- scales

struct Scales {
    static void mask(int key, int scale, bool* m) {
        static const int steps[10][12] = {
            {0, 2, 4, 5, 7, 9, 11, -1}, {0, 2, 3, 5, 7, 8, 10, -1}, {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11},
            {0, 2, 3, 5, 7, 8, 11, -1}, {0, 2, 4, 7, 9, -1}, {0, 3, 5, 7, 10, -1}, {0, 3, 5, 6, 7, 10, -1},
            {0, 2, 3, 5, 7, 9, 10, -1}, {0, 1, 3, 5, 7, 8, 10, -1}, {0, 2, 4, 5, 7, 9, 10, -1}};
        int s = std::max(0, std::min(9, scale));
        for (int i = 0; i < 12; i++) m[i] = false;
        for (int i = 0; i < 12 && steps[s][i] >= 0; i++) {
            if (s != 2 && i > 0 && steps[s][i] == 0) break;
            m[((key + steps[s][i]) % 12 + 12) % 12] = true;
        }
    }
    static int pc(int midi) { return ((midi % 12) + 12) % 12; }
    static int nearest(double midi, const bool* m) {
        int best = (int)std::lround(midi);
        double bd = 1e9;
        for (int n = (int)std::floor(midi) - 7; n <= (int)std::ceil(midi) + 7; n++) {
            if (!m[pc(n)]) continue;
            double d = std::fabs(n - midi);
            if (d < bd) { bd = d; best = n; }
        }
        return best;
    }
    static int shift(int note, int steps, const bool* m) {
        int n = note, dir = steps >= 0 ? 1 : -1;
        for (int i = 0; i < std::abs(steps); i++) {
            int g = 0;
            do { n += dir; g++; } while (!m[pc(n)] && g < 12);
        }
        return n;
    }
};

// ---------------------------------------------------------------- shared song settings

struct Globals {
    std::atomic<int> key{0}, scale{0};
    std::atomic<float> refHz{440.f}, bpm{90.f};
};

// ---------------------------------------------------------------- small helpers

/** Fractional delay line (power-of-two ring). */
class DelayLine {
public:
    void init(int maxSamples) {
        int n = 1; while (n < maxSamples + 4) n <<= 1;
        buf_.assign(n, 0.f); mask_ = n - 1; w_ = 0;
    }
    inline void push(float x) { buf_[w_] = x; w_ = (w_ + 1) & mask_; }
    /** x[n - d] where x[n] is the newest pushed sample; fractional d, linear interpolation */
    inline float read(float d) const {
        float pos = (float)(w_ - 1) - d;
        int i = (int)std::floor(pos);
        float f = pos - (float)i;
        float a = buf_[i & mask_], b = buf_[(i + 1) & mask_];
        return a + (b - a) * f;
    }
    inline float tap(int d) const { return buf_[(w_ - 1 - d) & mask_]; }
    void clear() { std::fill(buf_.begin(), buf_.end(), 0.f); }
private:
    std::vector<float> buf_;
    int mask_ = 0, w_ = 0;
};

/** Smoothly varying random LFO in [-1, 1]. */
class DriftLfo {
public:
    void init(double sr, double rate, uint32_t seed) { sr_ = sr; rate_ = rate; s_ = seed ? seed : 1; next(); cur_ = tgt_; next(); }
    inline float tick() {
        ph_ += rate_ / sr_;
        if (ph_ >= 1) { ph_ -= 1; cur_ = tgt_; next(); }
        double t = 0.5 - 0.5 * std::cos(PI * ph_);
        return (float)(cur_ + (tgt_ - cur_) * t);
    }
private:
    void next() { s_ = s_ * 1664525u + 1013904223u; tgt_ = ((s_ >> 8) / 8388608.0) - 1.0; }
    double sr_ = 48000, rate_ = 0.5, ph_ = 0, cur_ = 0, tgt_ = 0;
    uint32_t s_ = 1;
};

}  // namespace tt
