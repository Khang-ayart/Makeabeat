#include "vocal_dsp.h"

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace band {

namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr int kDecim = 4;
constexpr float kMinHz = 70.0f, kMaxHz = 900.0f;

// Giảm tốc độ lấy mẫu 4 lần: lọc FIR sinc cửa sổ Hann (cắt ~0,8 x Nyquist mới) rồi lấy mỗi mẫu thứ 4.
std::vector<float> decimate(const std::vector<float>& x) {
    const int taps = 4 * kDecim * 2 + 1;  // 33
    std::vector<float> h(static_cast<size_t>(taps));
    const double fc = 0.8 / (2.0 * kDecim);  // so với tốc độ gốc
    const int mid = taps / 2;
    double sum = 0;
    for (int i = 0; i < taps; ++i) {
        const double t = i - mid;
        const double sinc = t == 0 ? 2.0 * fc : std::sin(2.0 * kPi * fc * t) / (kPi * t);
        const double w = 0.5 - 0.5 * std::cos(2.0 * kPi * (i + 0.5) / taps);
        h[static_cast<size_t>(i)] = static_cast<float>(sinc * w);
        sum += sinc * w;
    }
    for (auto& v : h) v = static_cast<float>(v / sum);
    const size_t n = x.size() / kDecim;
    std::vector<float> y(n);
    for (size_t k = 0; k < n; ++k) {
        const long c = static_cast<long>(k) * kDecim;
        double acc = 0;
        for (int i = 0; i < taps; ++i) {
            const long j = c + i - mid;
            if (j >= 0 && j < static_cast<long>(x.size())) acc += h[static_cast<size_t>(i)] * x[static_cast<size_t>(j)];
        }
        y[k] = static_cast<float>(acc);
    }
    return y;
}

// YIN trên đoạn [start, start+w) của s với độ trễ tối đa tauMax; trả về tau (phân số) hoặc 0 nếu không có cao độ.
float yin(const float* s, long total, long start, int w, int tauMin, int tauMax, float thresh, float* conf) {
    if (start < 0 || start + w + tauMax >= total) return 0.0f;
    std::vector<float> d(static_cast<size_t>(tauMax + 1), 0.0f);
    for (int tau = 1; tau <= tauMax; ++tau) {
        double acc = 0;
        for (int j = 0; j < w; ++j) {
            const double df = s[start + j] - s[start + j + tau];
            acc += df * df;
        }
        d[static_cast<size_t>(tau)] = static_cast<float>(acc);
    }
    double run = 0;
    std::vector<float> cm(static_cast<size_t>(tauMax + 1), 1.0f);
    for (int tau = 1; tau <= tauMax; ++tau) {
        run += d[static_cast<size_t>(tau)];
        cm[static_cast<size_t>(tau)] = run > 0 ? static_cast<float>(d[static_cast<size_t>(tau)] * tau / run) : 1.0f;
    }
    int best = 0;
    for (int tau = std::max(2, tauMin); tau < tauMax; ++tau) {
        if (cm[static_cast<size_t>(tau)] < thresh) {
            int t = tau;
            while (t + 1 < tauMax && cm[static_cast<size_t>(t + 1)] < cm[static_cast<size_t>(t)]) ++t;
            best = t;
            break;
        }
    }
    if (best == 0) {  // không có đáy dưới ngưỡng: lấy đáy sâu nhất nếu đủ rõ
        float mn = 1.0f;
        for (int tau = std::max(2, tauMin); tau < tauMax; ++tau) {
            if (cm[static_cast<size_t>(tau)] < mn) { mn = cm[static_cast<size_t>(tau)]; best = tau; }
        }
        if (mn > 0.35f) return 0.0f;
    }
    // Nội suy parabol quanh đáy.
    float t = static_cast<float>(best);
    if (best > 1 && best < tauMax) {
        const float a = cm[static_cast<size_t>(best - 1)], b = cm[static_cast<size_t>(best)], c = cm[static_cast<size_t>(best + 1)];
        const float den = a - 2 * b + c;
        if (std::fabs(den) > 1e-9f) t += 0.5f * (a - c) / den;
    }
    if (conf) *conf = 1.0f - cm[static_cast<size_t>(best)];
    return t;
}

double midiOf(double hz) { return 69.0 + 12.0 * std::log2(hz / 440.0); }

// Nốt (MIDI, số thực) thuộc thang gần [midi] nhất.
bool inScale(int pc, const AutoTuneParams& p) {
    if (p.chromatic) return true;
    static const bool major[12] = {1, 0, 1, 0, 1, 1, 0, 1, 0, 1, 0, 1};
    static const bool minor[12] = {1, 0, 1, 1, 0, 1, 0, 1, 1, 0, 1, 0};
    const int rel = ((pc - p.keyRoot) % 12 + 12) % 12;
    return p.minor ? minor[rel] : major[rel];
}

}  // namespace

int pitchHop(int sampleRate) { return std::max(64, static_cast<int>(std::lround(512.0 * sampleRate / 44100.0 / kDecim)) * kDecim); }

std::vector<float> trackPitch(const std::vector<float>& x, int sr) {
    const int hop = pitchHop(sr);
    const size_t frames = x.size() / static_cast<size_t>(hop);
    std::vector<float> f0(frames, 0.0f);
    if (frames == 0) return f0;
    const std::vector<float> lo = decimate(x);
    const int srLo = sr / kDecim;
    const int hopLo = hop / kDecim;
    const int w = std::max(64, static_cast<int>(0.032 * srLo));          // cửa sổ ~32 ms
    const int tauMax = static_cast<int>(srLo / kMinHz) + 1;
    const int tauMin = std::max(2, static_cast<int>(srLo / kMaxHz));
    for (size_t k = 0; k < frames; ++k) {
        const long centerLo = static_cast<long>(k) * hopLo + hopLo / 2;
        const long start = centerLo - (w + tauMax) / 2;
        double e = 0;
        for (int j = 0; j < w; ++j) {
            const long idx = start + j;
            if (idx >= 0 && idx < static_cast<long>(lo.size())) e += lo[static_cast<size_t>(idx)] * lo[static_cast<size_t>(idx)];
        }
        if (std::sqrt(e / w) < 0.008) continue;  // quá nhỏ: coi như im lặng
        float conf = 0;
        const float tau = yin(lo.data(), static_cast<long>(lo.size()), start, w, tauMin, tauMax, 0.15f, &conf);
        if (tau <= 0 || conf < 0.6f) continue;
        // Tinh chỉnh ở tốc độ gốc: tìm cực tiểu của hiệu trung bình quanh chu kỳ thô.
        const double coarse = static_cast<double>(tau) * kDecim;
        const long cStart = static_cast<long>(k) * hop + hop / 2 - static_cast<long>(coarse) - hop / 2;
        const int wFull = static_cast<int>(std::max(512.0, std::min(2.0 * coarse, 2048.0)));
        double bestD = 1e30, bestT = coarse;
        std::vector<double> dv;
        const int span = 6;
        const long t0 = static_cast<long>(coarse) - span;
        for (long t = t0; t <= static_cast<long>(coarse) + span; ++t) {
            double acc = 0;
            for (int j = 0; j < wFull; ++j) {
                const long a = cStart + j, b = a + t;
                if (a < 0 || b >= static_cast<long>(x.size())) { acc += 1e6; break; }
                const double df = x[static_cast<size_t>(a)] - x[static_cast<size_t>(b)];
                acc += df * df;
            }
            dv.push_back(acc);
            if (acc < bestD) { bestD = acc; bestT = static_cast<double>(t); }
        }
        const long bi = static_cast<long>(bestT) - t0;
        if (bi > 0 && bi + 1 < static_cast<long>(dv.size())) {
            const double a = dv[static_cast<size_t>(bi - 1)], b = dv[static_cast<size_t>(bi)], c = dv[static_cast<size_t>(bi + 1)];
            const double den = a - 2 * b + c;
            if (std::fabs(den) > 1e-12) bestT += 0.5 * (a - c) / den;
        }
        f0[k] = static_cast<float>(sr / bestT);
        if (f0[k] < kMinHz * 0.9f || f0[k] > kMaxHz * 1.1f) f0[k] = 0.0f;
    }
    // Lọc trung vị 3 khung để bỏ gai (nhảy quãng tám lẻ loi).
    std::vector<float> out = f0;
    for (size_t k = 1; k + 1 < frames; ++k) {
        if (f0[k] > 0 && f0[k - 1] > 0 && f0[k + 1] > 0) {
            float a = f0[k - 1], b = f0[k], c = f0[k + 1];
            out[k] = std::max(std::min(a, b), std::min(std::max(a, b), c));
        } else if (f0[k] > 0 && f0[k - 1] == 0 && f0[k + 1] == 0) {
            out[k] = 0.0f;  // khung lẻ loi
        }
    }
    return out;
}

KeyGuess estimateKey(const std::vector<float>& pitchHz, int sr) {
    KeyGuess g;
    double hist[12] = {};
    double voiced = 0;
    for (float f : pitchHz) {
        if (f <= 0) continue;
        const double m = midiOf(f);
        const int pc = ((static_cast<int>(std::lround(m)) % 12) + 12) % 12;
        const double off = std::fabs(m - std::round(m));
        hist[pc] += 1.0 - off;  // nốt chuẩn (ít lệch) nặng hơn, nốt "lướt" ít hơn
        voiced += 1.0;
    }
    g.voicedSeconds = static_cast<float>(voiced * pitchHop(sr) / sr);
    if (voiced < 10) return g;
    static const double maj[12] = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    static const double mnr[12] = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};
    double best = -2;
    for (int root = 0; root < 12; ++root) {
        for (int m = 0; m < 2; ++m) {
            const double* prof = m ? mnr : maj;
            double mh = 0, mp = 0;
            for (int i = 0; i < 12; ++i) { mh += hist[i]; mp += prof[i]; }
            mh /= 12; mp /= 12;
            double num = 0, dh = 0, dp = 0;
            for (int i = 0; i < 12; ++i) {
                const double a = hist[(root + i) % 12] - mh, b = prof[i] - mp;
                num += a * b; dh += a * a; dp += b * b;
            }
            const double r = (dh > 0 && dp > 0) ? num / std::sqrt(dh * dp) : 0;
            if (r > best) { best = r; g.root = root; g.minor = m == 1; }
        }
    }
    g.score = static_cast<float>(std::clamp(best, 0.0, 1.0));
    return g;
}

void autoTune(std::vector<float>& x, int sr, const AutoTuneParams& p) {
    if (x.size() < 4096 || p.strength <= 0.0f) return;
    const int hop = pitchHop(sr);
    const std::vector<float> f0 = trackPitch(x, sr);
    const size_t frames = f0.size();
    // 1) Độ sửa (bán cung) cho từng khung: về nốt trong thang gần nhất, có "dính" nốt (hysteresis) và làm mượt theo tốc độ.
    std::vector<float> corr(frames, 0.0f);
    {
        const double frameSec = static_cast<double>(hop) / sr;
        const double tau = 0.150 * std::pow(0.03 / 0.150, std::clamp(p.speed, 0.0f, 1.0f));  // 150 ms (chậm) .. 30 ms (nhanh)
        const double a = 1.0 - std::exp(-frameSec / tau);
        double cur = 0;
        int target = -1000;
        bool prevVoiced = false;
        for (size_t k = 0; k < frames; ++k) {
            if (f0[k] <= 0) { prevVoiced = false; continue; }
            const double m = midiOf(f0[k]);
            auto best = [&](double mm) {
                int bestN = -1000;
                double bd = 1e9;
                const int c = static_cast<int>(std::lround(mm));
                for (int n = c - 3; n <= c + 3; ++n) {
                    if (!inScale(((n % 12) + 12) % 12, p)) continue;
                    const double d = std::fabs(mm - n);
                    if (d < bd) { bd = d; bestN = n; }
                }
                return bestN;
            };
            const int nb = best(m);
            if (!prevVoiced || target == -1000) { target = nb; cur = (target - m); }
            else if (nb != target && std::fabs(m - nb) + 0.25 < std::fabs(m - target)) target = nb;  // chỉ đổi nốt khi rõ ràng gần nốt mới hơn
            const double want = (target - m) * p.strength;
            cur += (want - cur) * (prevVoiced ? a : 1.0);
            corr[k] = static_cast<float>(cur);
            prevVoiced = true;
        }
    }
    // 2) TD-PSOLA trên từng đoạn có cao độ liên tục.
    std::vector<float> y(x.size(), 0.0f);
    std::vector<float> mask(x.size(), 0.0f);
    auto f0At = [&](double pos, bool* ok) {  // nội suy tuyến tính theo thời gian giữa tâm các khung
        const double fk = pos / hop - 0.5;
        long k0 = static_cast<long>(std::floor(fk));
        const double fr = fk - static_cast<double>(k0);
        k0 = std::clamp(k0, 0L, static_cast<long>(frames) - 1);
        const long k1 = std::min(k0 + 1, static_cast<long>(frames) - 1);
        const float a = f0[static_cast<size_t>(k0)], b = f0[static_cast<size_t>(k1)];
        *ok = a > 0 && b > 0;
        return std::pair<double, double>{a * (1 - fr) + b * fr,
                                         corr[static_cast<size_t>(k0)] * (1 - fr) + corr[static_cast<size_t>(k1)] * fr};
    };
    size_t k = 0;
    while (k < frames) {
        if (f0[k] <= 0) { ++k; continue; }
        size_t e = k;
        while (e < frames && f0[e] > 0) ++e;
        if (e - k < 4) { k = e; continue; }  // đoạn quá ngắn: bỏ
        const long segStart = static_cast<long>(k) * hop, segEnd = static_cast<long>(e) * hop;
        // Điểm phân tích: bước theo chu kỳ cục bộ, bám đỉnh dương gần nhất.
        std::vector<long> marks;
        std::vector<float> periods;
        double pos = static_cast<double>(segStart);
        while (pos < static_cast<double>(segEnd)) {
            bool ok;
            const double f = f0At(pos, &ok).first;
            if (f <= 0) { pos += 64; continue; }
            const double T = sr / f;
            long m = static_cast<long>(pos);
            const long r = static_cast<long>(T * 0.25);
            long bestM = m;
            float bv = -1e30f;
            for (long j = std::max(0L, m - r); j <= std::min(static_cast<long>(x.size()) - 1, m + r); ++j) {
                if (x[static_cast<size_t>(j)] > bv) { bv = x[static_cast<size_t>(j)]; bestM = j; }
            }
            if (!marks.empty() && bestM <= marks.back()) bestM = marks.back() + static_cast<long>(T * 0.5);
            marks.push_back(bestM);
            periods.push_back(static_cast<float>(T));
            pos = static_cast<double>(bestM) + T;
        }
        if (marks.size() >= 3) {
            double ts = static_cast<double>(marks.front());
            size_t ai = 0;
            while (ts < static_cast<double>(marks.back())) {
                bool ok;
                const auto fc = f0At(ts, &ok);
                const double Ts = sr / std::max(fc.first, 1.0);
                const double ratio = std::pow(2.0, fc.second / 12.0);
                while (ai + 1 < marks.size() && std::fabs(static_cast<double>(marks[ai + 1]) - ts) <= std::fabs(static_cast<double>(marks[ai]) - ts)) ++ai;
                const long T = std::max(8L, static_cast<long>(std::lround(periods[ai])));
                const long m = marks[ai];
                const double hopOut = Ts / ratio;
                const float scale = static_cast<float>(hopOut / T);
                const long c = static_cast<long>(std::lround(ts));
                for (long i = -T; i < T; ++i) {
                    const long src = m + i, dst = c + i;
                    if (src < 0 || src >= static_cast<long>(x.size()) || dst < 0 || dst >= static_cast<long>(y.size())) continue;
                    const float w = 0.5f - 0.5f * std::cos(static_cast<float>(2.0 * kPi * (i + T + 0.5) / (2.0 * T)));
                    y[static_cast<size_t>(dst)] += x[static_cast<size_t>(src)] * w * scale;
                }
                ts += hopOut;
            }
            // Mặt nạ: 1 trong đoạn, vào/ra mượt 6 ms ở hai đầu.
            const long a = std::max(0L, marks.front()), b = std::min(static_cast<long>(x.size()), marks.back());
            const long ramp = std::max(16L, static_cast<long>(0.006 * sr));
            for (long i = a; i < b; ++i) {
                const float up = std::min(1.0f, static_cast<float>(i - a) / ramp), dn = std::min(1.0f, static_cast<float>(b - i) / ramp);
                mask[static_cast<size_t>(i)] = std::min(up, dn);
            }
        }
        k = e;
    }
    for (size_t i = 0; i < x.size(); ++i) x[i] = x[i] * (1.0f - mask[i]) + y[i] * mask[i];
}

std::vector<float> timeStretch(const std::vector<float>& x, int sr, double ratio) {
    ratio = std::clamp(ratio, 0.5, 2.0);
    if (x.size() < 4096 || std::fabs(ratio - 1.0) < 1e-3) return x;
    const int N = std::max(512, static_cast<int>(0.04 * sr) / 2 * 2);  // cửa sổ ~40 ms
    const int Hs = N / 2;                                               // bước tổng hợp (Hann, chồng 50% -> tổng = 1)
    const double Ha = Hs / ratio;                                       // bước phân tích danh nghĩa
    const int tol = Hs / 2;                                             // dò lệch tối đa +-
    const size_t outLen = static_cast<size_t>(static_cast<double>(x.size()) * ratio);
    std::vector<float> y(outLen + static_cast<size_t>(N), 0.0f);
    std::vector<float> win(static_cast<size_t>(N));
    for (int i = 0; i < N; ++i) win[static_cast<size_t>(i)] = 0.5f - 0.5f * std::cos(static_cast<float>(2.0 * kPi * (i + 0.5) / N));
    const std::vector<float> lo = decimate(x);
    const long nlo = static_cast<long>(lo.size());
    const int Nlo = N / kDecim;
    long prev = 0;      // vị trí nguồn đã chọn cho khung trước
    bool first = true;
    for (size_t k = 0;; ++k) {
        const long outPos = static_cast<long>(k) * Hs;
        if (outPos >= static_cast<long>(outLen)) break;
        long pos = static_cast<long>(std::llround(static_cast<double>(k) * Ha));
        if (!first) {
            // Chọn lệch để đoạn mới nối liền mạch nhất với đoạn "tiếp theo tự nhiên" của khung trước (tương quan ở tốc độ đã giảm).
            const long ref = (prev + Hs) / kDecim;
            long best = pos, bestScore = 0;
            double bs = -1e300;
            const long c0 = pos / kDecim;
            for (long o = -tol / kDecim; o <= tol / kDecim; ++o) {
                const long a = c0 + o;
                if (a < 0 || a + Nlo >= nlo || ref < 0 || ref + Nlo >= nlo) continue;
                double dot = 0, na = 0;
                for (int j = 0; j < Nlo; j += 2) {
                    dot += static_cast<double>(lo[static_cast<size_t>(ref + j)]) * lo[static_cast<size_t>(a + j)];
                    na += static_cast<double>(lo[static_cast<size_t>(a + j)]) * lo[static_cast<size_t>(a + j)];
                }
                const double score = dot / std::sqrt(na + 1e-9);
                if (score > bs) { bs = score; bestScore = o; }
            }
            best = (c0 + bestScore) * kDecim;
            // tinh chỉnh ở tốc độ gốc quanh vị trí đã chọn
            const long refFull = prev + Hs;
            double bs2 = -1e300;
            long best2 = best;
            for (long o = -kDecim; o <= kDecim; ++o) {
                const long a = best + o;
                if (a < 0 || a + N >= static_cast<long>(x.size()) || refFull < 0 || refFull + N >= static_cast<long>(x.size())) continue;
                double dot = 0, na = 0;
                for (int j = 0; j < N; j += 4) {
                    dot += static_cast<double>(x[static_cast<size_t>(refFull + j)]) * x[static_cast<size_t>(a + j)];
                    na += static_cast<double>(x[static_cast<size_t>(a + j)]) * x[static_cast<size_t>(a + j)];
                }
                const double score = dot / std::sqrt(na + 1e-9);
                if (score > bs2) { bs2 = score; best2 = a; }
            }
            pos = best2;
        }
        first = false;
        prev = pos;
        for (int j = 0; j < N; ++j) {
            const long src = pos + j;
            if (src < 0 || src >= static_cast<long>(x.size())) break;
            y[static_cast<size_t>(outPos + j)] += x[static_cast<size_t>(src)] * win[static_cast<size_t>(j)];
        }
    }
    y.resize(outLen);
    return y;
}

}  // namespace band
