#include "noise_reducer.h"

#include <algorithm>
#include <cmath>
#include <numeric>

namespace band {

namespace {

constexpr int kN = 1024;
constexpr int kHop = 256;  // chồng 75%
constexpr double kPi = 3.14159265358979323846;

class Fft {
public:
    explicit Fft(int n) : n_(n), cos_(n / 2), sin_(n / 2), rev_(n) {
        for (int k = 0; k < n / 2; ++k) {
            cos_[k] = static_cast<float>(std::cos(2.0 * kPi * k / n));
            sin_[k] = static_cast<float>(std::sin(2.0 * kPi * k / n));
        }
        int bits = 0;
        while ((1 << bits) < n) ++bits;
        for (int i = 0; i < n; ++i) {
            int r = 0;
            for (int b = 0; b < bits; ++b) r |= ((i >> b) & 1) << (bits - 1 - b);
            rev_[i] = r;
        }
    }

    void run(float* re, float* im, bool inverse) const {
        for (int i = 0; i < n_; ++i) {
            const int j = rev_[i];
            if (j > i) {
                std::swap(re[i], re[j]);
                std::swap(im[i], im[j]);
            }
        }
        for (int len = 2; len <= n_; len <<= 1) {
            const int half = len / 2;
            const int step = n_ / len;
            for (int i = 0; i < n_; i += len) {
                for (int k = 0; k < half; ++k) {
                    const float wr = cos_[k * step];
                    const float wi = inverse ? sin_[k * step] : -sin_[k * step];
                    const int a = i + k, b = a + half;
                    const float tr = re[b] * wr - im[b] * wi;
                    const float ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
        }
        if (inverse) {
            const float s = 1.0f / static_cast<float>(n_);
            for (int i = 0; i < n_; ++i) {
                re[i] *= s;
                im[i] *= s;
            }
        }
    }

private:
    int n_;
    std::vector<float> cos_, sin_;
    std::vector<int> rev_;
};

// Biquad thông cao 2 cực (Butterworth), áp dụng tại chỗ.
void highpass(std::vector<float>& x, int sampleRate, float fc) {
    if (fc <= 0.0f || fc >= 0.45f * sampleRate) return;
    const double w0 = 2.0 * kPi * fc / sampleRate;
    const double c = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * 0.70710678);
    const double a0 = 1.0 + alpha;
    const double b0 = (1.0 + c) / 2.0 / a0, b1 = -(1.0 + c) / a0, b2 = b0;
    const double a1 = -2.0 * c / a0, a2 = (1.0 - alpha) / a0;
    double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
    for (float& v : x) {
        const double in = v;
        const double y = b0 * in + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1;
        x1 = in;
        y2 = y1;
        y1 = y;
        v = static_cast<float>(y);
    }
}

// Phổ biên độ (kN/2+1 bin) của khung bắt đầu ở start trong sig (đã nhân cửa sổ).
void frameMagnitude(const Fft& fft, const std::vector<float>& win, const float* sig, std::vector<float>& re,
                    std::vector<float>& im, std::vector<float>& mag) {
    for (int i = 0; i < kN; ++i) {
        re[i] = sig[i] * win[i];
        im[i] = 0.0f;
    }
    fft.run(re.data(), im.data(), false);
    for (int k = 0; k <= kN / 2; ++k) mag[k] = std::sqrt(re[k] * re[k] + im[k] * im[k]);
}

}  // namespace

void denoise(std::vector<float>& x, int sampleRate, const DenoiseParams& params, const float* noiseOnly,
             int noiseCount) {
    highpass(x, sampleRate, params.highpassHz);

    const float strength = std::clamp(params.strength, 0.0f, 1.0f);
    const int n = static_cast<int>(x.size());
    if (strength <= 0.0f || n < 4 * kN) return;

    const int bins = kN / 2 + 1;
    const Fft fft(kN);
    std::vector<float> win(kN);
    for (int i = 0; i < kN; ++i) win[i] = std::sqrt(0.5f - 0.5f * std::cos(2.0f * static_cast<float>(kPi) * i / kN));

    std::vector<float> re(kN), im(kN), mag(bins);

    // ---- 1) Mẫu nhiễu ----
    std::vector<float> noise(bins, 0.0f);
    if (noiseOnly && noiseCount >= 2 * kN) {
        std::vector<float> nz(noiseOnly, noiseOnly + noiseCount);
        highpass(nz, sampleRate, params.highpassHz);
        int frames = 0;
        for (int s = 0; s + kN <= noiseCount; s += kHop, ++frames) {
            frameMagnitude(fft, win, nz.data() + s, re, im, mag);
            for (int k = 0; k < bins; ++k) noise[k] += mag[k];
        }
        for (float& v : noise) v /= static_cast<float>(std::max(1, frames));
    } else {
        const int frames = (n - kN) / kHop + 1;
        std::vector<float> energy(frames);
        for (int f = 0; f < frames; ++f) {
            const float* p = x.data() + static_cast<size_t>(f) * kHop;
            double e = 0.0;
            for (int i = 0; i < kN; ++i) e += static_cast<double>(p[i]) * p[i];
            energy[f] = static_cast<float>(e);
        }
        std::vector<int> order(frames);
        std::iota(order.begin(), order.end(), 0);
        const int take = std::max(4, frames * 15 / 100);
        std::partial_sort(order.begin(), order.begin() + std::min(take, frames), order.end(),
                          [&](int a, int b) { return energy[a] < energy[b]; });
        const int used = std::min(take, frames);
        for (int j = 0; j < used; ++j) {
            frameMagnitude(fft, win, x.data() + static_cast<size_t>(order[j]) * kHop, re, im, mag);
            for (int k = 0; k < bins; ++k) noise[k] += mag[k];
        }
        for (float& v : noise) v /= static_cast<float>(used);
    }

    // ---- 2) Trừ phổ, ghép lại bằng cộng chồng ----
    const float alpha = 1.0f + 2.2f * strength;                       // quá-trừ
    const float gMin = 0.3f * (1.0f - strength) + 0.03f * strength;  // sàn độ lợi (≈ -10 .. -30 dB)

    std::vector<float> padded(static_cast<size_t>(n) + 2 * kN, 0.0f);
    std::copy(x.begin(), x.end(), padded.begin() + kN);
    std::vector<float> out(padded.size(), 0.0f);

    std::vector<float> graw(bins), gs(bins), prev(bins, 1.0f);
    const int frames = static_cast<int>((padded.size() - kN) / kHop) + 1;
    for (int f = 0; f < frames; ++f) {
        const size_t start = static_cast<size_t>(f) * kHop;
        for (int i = 0; i < kN; ++i) {
            re[i] = padded[start + i] * win[i];
            im[i] = 0.0f;
        }
        fft.run(re.data(), im.data(), false);

        for (int k = 0; k < bins; ++k) {
            const float m = std::sqrt(re[k] * re[k] + im[k] * im[k]);
            graw[k] = std::max(gMin, 1.0f - alpha * noise[k] / (m + 1e-12f));
        }
        for (int k = 0; k < bins; ++k) {  // làm mượt theo tần số rồi theo thời gian (tấn công nhanh, nhả chậm)
            const float a = graw[std::max(0, k - 1)], b = graw[k], c = graw[std::min(bins - 1, k + 1)];
            const float g = 0.25f * a + 0.5f * b + 0.25f * c;
            gs[k] = g > prev[k] ? g : 0.5f * prev[k] + 0.5f * g;
            prev[k] = gs[k];
        }
        for (int k = 0; k < kN; ++k) {
            const float g = gs[k <= kN / 2 ? k : kN - k];
            re[k] *= g;
            im[k] *= g;
        }
        fft.run(re.data(), im.data(), true);
        for (int i = 0; i < kN; ++i) out[start + i] += re[i] * win[i] * 0.5f;  // Hann, chồng 75% -> tổng = 2
    }

    std::copy(out.begin() + kN, out.begin() + kN + n, x.begin());
}

}  // namespace band
