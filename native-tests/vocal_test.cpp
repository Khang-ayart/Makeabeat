// Kiểm thử phân tích / chỉnh giọng hát (vocal_dsp). Chạy:
//   g++ -std=c++17 -O2 -Iapp/src/main/cpp native-tests/vocal_test.cpp app/src/main/cpp/vocal_dsp.cpp -o vocal_test && ./vocal_test
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <vector>

#include "vocal_dsp.h"

using namespace band;

static int gFail = 0, gTests = 0;
#define CHECK(cond, ...) do { if (!(cond)) { std::printf("    FAIL (line %d): ", __LINE__); std::printf(__VA_ARGS__); std::printf("\n"); ++gFail; } } while (0)
static void begin(const char* name) { ++gTests; std::printf("[ RUN ] %s\n", name); }

static const int kSr = 44100;

// Giọng giả: tổng hài (1/h) với vibrato nhẹ, có biên độ lên xuống như nốt hát.
static std::vector<float> voice(const std::vector<std::pair<double, double>>& notes /*Hz, giây*/, double vibCents = 0) {
    std::vector<float> x;
    double phase = 0, t = 0;
    for (const auto& n : notes) {
        const size_t len = static_cast<size_t>(n.second * kSr);
        for (size_t i = 0; i < len; ++i, t += 1.0 / kSr) {
            const double f = n.first * std::pow(2.0, vibCents / 1200.0 * std::sin(2 * M_PI * 5.5 * t));
            phase += 2 * M_PI * f / kSr;
            double s = 0;
            for (int h = 1; h <= 12; ++h) s += std::sin(h * phase) / h;
            const double env = std::min({1.0, i / 800.0, (len - i) / 800.0});
            x.push_back(static_cast<float>(0.3 * s * env));
        }
    }
    return x;
}

static double medianHz(const std::vector<float>& f, size_t from, size_t to) {
    std::vector<float> v;
    for (size_t k = from; k < to && k < f.size(); ++k) if (f[k] > 0) v.push_back(f[k]);
    if (v.empty()) return 0;
    std::sort(v.begin(), v.end());
    return v[v.size() / 2];
}
static double cents(double a, double b) { return 1200.0 * std::log2(a / b); }

static void testTrackPitch() {
    begin("Dò cao độ: nốt đơn sai số vài cent, nhiều nốt, im lặng và nhiễu không có cao độ");
    for (double hz : {110.0, 196.0, 261.6256, 440.0, 600.0}) {
        const auto x = voice({{hz, 1.0}});
        const auto f = trackPitch(x, kSr);
        const double m = medianHz(f, 10, f.size() - 10);
        CHECK(m > 0 && std::fabs(cents(m, hz)) < 12.0, "%.1f Hz -> %.2f Hz", hz, m);
    }
    const auto seq = voice({{220, 0.6}, {293.66, 0.6}, {329.63, 0.6}});
    const auto f = trackPitch(seq, kSr);
    const size_t per = static_cast<size_t>(0.6 * kSr) / pitchHop(kSr);
    CHECK(std::fabs(cents(medianHz(f, 4, per - 4), 220)) < 15 && std::fabs(cents(medianHz(f, per + 4, 2 * per - 4), 293.66)) < 15 &&
              std::fabs(cents(medianHz(f, 2 * per + 4, 3 * per - 4), 329.63)) < 15, "sequence wrong");
    std::vector<float> silence(kSr, 0.0f), noise(kSr);
    std::mt19937 rng(1);
    std::uniform_real_distribution<float> u(-0.2f, 0.2f);
    for (auto& v : noise) v = u(rng);
    const auto fs = trackPitch(silence, kSr);
    const auto fn = trackPitch(noise, kSr);
    size_t voicedS = 0, voicedN = 0;
    for (float v : fs) voicedS += v > 0;
    for (float v : fn) voicedN += v > 0;
    CHECK(voicedS == 0, "silence voiced %zu", voicedS);
    CHECK(voicedN < fn.size() / 8, "noise voiced %zu of %zu", voicedN, fn.size());
}

static void testEstimateKey() {
    begin("Đoán tông: giai điệu C trưởng cho tập nốt của C trưởng / A thứ; G trưởng nhận ra F#");
    auto melody = [&](std::vector<int> midi) {
        std::vector<std::pair<double, double>> n;
        for (int m : midi) n.push_back({440.0 * std::pow(2.0, (m - 69) / 12.0), 0.35});
        return voice(n);
    };
    const auto c = estimateKey(trackPitch(melody({60, 64, 67, 64, 62, 65, 69, 65, 64, 67, 71, 67, 60, 62, 64, 60, 67, 64, 60}), kSr), kSr);
    const bool cSet = (c.root == 0 && !c.minor) || (c.root == 9 && c.minor);
    CHECK(cSet, "C major melody -> root %d minor %d", c.root, c.minor);
    const auto g = estimateKey(trackPitch(melody({67, 71, 74, 71, 69, 72, 76, 72, 71, 74, 78, 74, 67, 69, 71, 67, 74, 71, 67}), kSr), kSr);
    const bool gSet = (g.root == 7 && !g.minor) || (g.root == 4 && g.minor);
    CHECK(gSet, "G major melody -> root %d minor %d", g.root, g.minor);
    const auto none = estimateKey(std::vector<float>(50, 0.0f), kSr);
    CHECK(none.score == 0.0f, "empty should have no confidence");
}

static void testAutoTune() {
    begin("Auto-tune: giọng lệch 227 Hz (C trưởng) về A 220 Hz; đã đúng tông thì giữ nguyên; strength 0 không đổi; hơi thở không bị đụng");
    AutoTuneParams p;
    p.keyRoot = 0; p.minor = false; p.strength = 1.0f; p.speed = 1.0f;
    auto x = voice({{227.0, 1.5}});
    const size_t n = x.size();
    autoTune(x, kSr, p);
    CHECK(x.size() == n, "length changed");
    const auto f = trackPitch(x, kSr);
    const double m = medianHz(f, 20, f.size() - 20);
    CHECK(m > 0 && std::fabs(cents(m, 220.0)) < 20.0, "227 Hz tuned to %.2f Hz (want 220)", m);
    bool finite = true;
    float pk = 0;
    for (float v : x) { finite = finite && std::isfinite(v); pk = std::max(pk, std::fabs(v)); }
    CHECK(finite && pk < 1.5f, "output invalid (peak %f)", pk);

    // Đã đúng tông: giữ gần nguyên (tần số và năng lượng).
    auto y = voice({{220.0, 1.5}});
    const auto y0 = y;
    autoTune(y, kSr, p);
    const double my = medianHz(trackPitch(y, kSr), 20, 120);
    CHECK(std::fabs(cents(my, 220.0)) < 10.0, "in-tune note moved to %.2f", my);
    double e0 = 0, e1 = 0;
    for (size_t i = 3000; i + 3000 < y.size(); ++i) { e0 += y0[i] * y0[i]; e1 += y[i] * y[i]; }
    CHECK(e1 > 0.7 * e0 && e1 < 1.4 * e0, "energy changed %.3f", e1 / e0);

    // strength = 0: bit-exact.
    auto z = voice({{227.0, 1.0}});
    const auto z0 = z;
    AutoTuneParams off = p; off.strength = 0.0f;
    autoTune(z, kSr, off);
    CHECK(z == z0, "strength 0 changed the audio");

    // Nhiễu (hơi thở) không có cao độ: không đổi.
    std::vector<float> noise(kSr);
    std::mt19937 rng(7);
    std::uniform_real_distribution<float> u(-0.1f, 0.1f);
    for (auto& v : noise) v = u(rng);
    const auto n0 = noise;
    autoTune(noise, kSr, p);
    size_t diff = 0;
    for (size_t i = 0; i < noise.size(); ++i) diff += noise[i] != n0[i];
    CHECK(diff < noise.size() / 10, "noise altered in %zu samples", diff);

    // Chuỗi nhiều nốt lệch, chromatic: mỗi nốt về nốt gần nhất.
    AutoTuneParams chroma = p; chroma.chromatic = true;
    auto w = voice({{233.0 * 1.012, 0.7}, {262.0 * 0.988, 0.7}});  // ~ A#3 +20c, C4 -20c
    autoTune(w, kSr, chroma);
    const auto fw = trackPitch(w, kSr);
    const size_t per = static_cast<size_t>(0.7 * kSr) / pitchHop(kSr);
    CHECK(std::fabs(cents(medianHz(fw, 6, per - 6), 233.082)) < 15, "A#3 -> %.2f", medianHz(fw, 6, per - 6));
    CHECK(std::fabs(cents(medianHz(fw, per + 6, 2 * per - 6), 261.626)) < 15, "C4 -> %.2f", medianHz(fw, per + 6, 2 * per - 6));
}

static void testAutoTuneSoftKeepsVibrato() {
    begin("Auto-tune nhẹ (strength 0,5): kéo nửa đường về nốt; vibrato còn lại");
    AutoTuneParams p;
    p.strength = 0.5f; p.speed = 1.0f; p.keyRoot = 0;
    auto x = voice({{227.0, 1.5}});
    autoTune(x, kSr, p);
    const double m = medianHz(trackPitch(x, kSr), 20, 120);
    const double c = cents(m, 220.0), c0 = cents(227.0, 220.0);
    CHECK(c > 0.25 * c0 && c < 0.75 * c0, "soft tune moved %.1f of %.1f cents", c, c0);
}

int main() {
    testTrackPitch();
    testEstimateKey();
    testAutoTune();
    testAutoTuneSoftKeepsVibrato();
    if (gFail) { std::printf("%d CHECK(S) FAILED\n", gFail); return 1; }
    std::printf("ALL %d TESTS PASSED\n", gTests);
    return 0;
}
