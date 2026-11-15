// Kiểm thử lõi âm thanh trên máy tính (không cần Android / Oboe).
// Chạy (một dòng, từ thư mục gốc dự án):
//   g++ -std=c++17 -O2 -Iapp/src/main/cpp native-tests/core_test.cpp app/src/main/cpp/engine_core.cpp -o core_test && ./core_test

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

#include "engine_core.h"

using band::EngineCore;

static int g_failures = 0;
static int g_tests = 0;

#define CHECK(cond, ...)                                  \
    do {                                                  \
        if (!(cond)) {                                    \
            std::printf("    FAIL (line %d): ", __LINE__); \
            std::printf(__VA_ARGS__);                     \
            std::printf("\n");                            \
            ++g_failures;                                 \
        }                                                 \
    } while (0)

static void begin(const char* name) {
    ++g_tests;
    std::printf("[ RUN ] %s\n", name);
}

static std::vector<float> renderAll(EngineCore& core, int channels, int totalFrames, int chunk) {
    std::vector<float> out(static_cast<size_t>(totalFrames) * channels);
    int done = 0;
    while (done < totalFrames) {
        const int n = std::min(chunk, totalFrames - done);
        core.render(out.data() + static_cast<size_t>(done) * channels, n);
        done += n;
    }
    return out;
}

// Mốc bắt đầu mỗi tiếng click (đầu ra mono): mẫu "to" đầu tiên sau một khoảng lặng dài.
static std::vector<int64_t> findOnsets(const std::vector<float>& x, int64_t frameOffset = 0) {
    std::vector<int64_t> onsets;
    int64_t lastLoud = -1000000;
    for (size_t i = 0; i < x.size(); ++i) {
        if (std::fabs(x[i]) > 1e-4f) {
            if (static_cast<int64_t>(i) - lastLoud > 2000) onsets.push_back(frameOffset + static_cast<int64_t>(i));
            lastLoud = static_cast<int64_t>(i);
        }
    }
    return onsets;
}

static float peak(const std::vector<float>& x, size_t from, size_t count) {
    float p = 0.0f;
    for (size_t i = from; i < from + count && i < x.size(); ++i) p = std::max(p, std::fabs(x[i]));
    return p;
}

static void testExactBeatsAt120() {
    begin("120 BPM @ 48 kHz: mọi phách rơi đúng khung hình k * 24000");
    EngineCore core;
    core.prepare(48000, 1);
    core.setBpm(120.0f);
    core.setPlaying(true);
    const auto out = renderAll(core, 1, 48000 * 10, 480);
    const auto onsets = findOnsets(out);
    CHECK(onsets.size() == 20, "expected 20 beats, got %zu", onsets.size());
    for (size_t k = 0; k < onsets.size(); ++k) {
        CHECK(onsets[k] == static_cast<int64_t>(k) * 24000, "beat %zu at %lld", k, static_cast<long long>(onsets[k]));
    }
}

static void testNoDriftAtOddTempo() {
    begin("133 BPM @ 44.1 kHz trong 60 giây: không trôi nhịp (sai số < 1 khung hình)");
    EngineCore core;
    core.prepare(44100, 1);
    core.setBpm(133.0f);
    core.setPlaying(true);
    const auto out = renderAll(core, 1, 44100 * 60, 441);
    const auto onsets = findOnsets(out);
    CHECK(onsets.size() == 133, "expected 133 beats, got %zu", onsets.size());
    const double framesPerBeat = 44100.0 * 60.0 / 133.0;
    for (size_t k = 0; k < onsets.size(); ++k) {
        const double d = static_cast<double>(onsets[k]) - static_cast<double>(k) * framesPerBeat;
        CHECK(d > -1e-3 && d < 1.0 + 1e-3, "beat %zu off by %.4f frames", k, d);
    }
}

static void testChunkInvariance() {
    begin("Kết quả giống hệt từng bit dù callback có kích thước 480 / 137 / 1 khung hình");
    auto run = [](int chunk) {
        EngineCore core;
        core.prepare(48000, 1);
        core.setBpm(97.0f);
        core.noteOn(64, 100);
        core.noteOn(67, 90);
        core.setPlaying(true);
        return renderAll(core, 1, 48000 * 5, chunk);
    };
    const auto a = run(480);
    const auto b = run(137);
    const auto c = run(1);
    CHECK(a.size() == b.size() && std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) == 0, "480 vs 137 differ");
    CHECK(a.size() == c.size() && std::memcmp(a.data(), c.data(), a.size() * sizeof(float)) == 0, "480 vs 1 differ");
}

static void testTempoChange() {
    begin("Đổi tempo 120 -> 60 giữa chừng: vị trí phách liên tục, phách kế đúng chỗ");
    EngineCore core;
    core.prepare(48000, 1);
    core.setBpm(120.0f);
    core.setPlaying(true);
    std::vector<float> out = renderAll(core, 1, 36000, 36000);  // tới phách 1.5
    core.setBpm(60.0f);
    const auto tail = renderAll(core, 1, 84000, 480);
    out.insert(out.end(), tail.begin(), tail.end());
    const auto onsets = findOnsets(out);
    const int64_t expected[] = {0, 24000, 60000, 108000};
    CHECK(onsets.size() == 4, "expected 4 beats, got %zu", onsets.size());
    for (size_t k = 0; k < std::min<size_t>(onsets.size(), 4); ++k) {
        CHECK(onsets[k] == expected[k], "beat %zu at %lld, expected %lld", k, static_cast<long long>(onsets[k]),
              static_cast<long long>(expected[k]));
    }
}

static void testAccent() {
    begin("Phách đầu mỗi ô nhịp to hơn các phách còn lại");
    EngineCore core;
    core.prepare(48000, 1);
    core.setBpm(120.0f);
    core.setPlaying(true);
    const auto out = renderAll(core, 1, 24000 * 5 + 2000, 480);
    const float p0 = peak(out, 0, 2000);
    const float p1 = peak(out, 24000, 2000);
    const float p4 = peak(out, 24000 * 4, 2000);
    CHECK(p0 > p1 * 1.2f, "accent %.3f vs normal %.3f", p0, p1);
    CHECK(std::fabs(p0 - p4) < 1e-6f, "bar 2 accent %.3f differs from bar 1 %.3f", p4, p0);
}

static void testMetronomeOffAndStop() {
    begin("Tắt metronome thì im lặng; dừng transport thì beatPosition về 0");
    EngineCore core;
    core.prepare(48000, 1);
    core.setMetronomeEnabled(false);
    core.setPlaying(true);
    const auto out = renderAll(core, 1, 48000, 480);
    CHECK(peak(out, 0, out.size()) == 0.0f, "metronome off but output not silent");
    CHECK(core.beatPosition() > 1.0, "beat position should advance, got %.3f", core.beatPosition());
    core.setPlaying(false);
    renderAll(core, 1, 480, 480);
    CHECK(core.beatPosition() == 0.0, "beat position should reset, got %.3f", core.beatPosition());
}

static void testNoteLifecycle() {
    begin("Nốt: bật có tiếng, nhả thì tắt hẳn và giải phóng voice");
    EngineCore core;
    core.prepare(48000, 1);
    core.noteOn(60, 100);
    const auto on = renderAll(core, 1, 4800, 480);
    CHECK(peak(on, 0, on.size()) > 0.05f, "note produced no sound");
    CHECK(core.activeVoiceCount() == 1, "expected 1 voice, got %d", core.activeVoiceCount());
    core.noteOff(60);
    const auto off = renderAll(core, 1, 48000 * 2, 480);
    CHECK(core.activeVoiceCount() == 0, "voice not released, %d active", core.activeVoiceCount());
    CHECK(off.back() == 0.0f, "tail not silent");
}

static void testVoiceStealingAndBounds() {
    begin("40 nốt cùng lúc: tối đa 16 voice, đầu ra hữu hạn và nằm trong [-1, 1]");
    EngineCore core;
    core.prepare(48000, 1);
    for (int i = 0; i < 40; ++i) core.noteOn(36 + i, 127);
    const auto out = renderAll(core, 1, 48000, 256);
    CHECK(core.activeVoiceCount() == 16, "expected 16 voices, got %d", core.activeVoiceCount());
    bool bounded = true;
    for (float s : out) bounded = bounded && std::isfinite(s) && std::fabs(s) <= 1.0f;
    CHECK(bounded, "output out of range or non-finite");
}

static void testStereoDuplicates() {
    begin("Đầu ra stereo: hai kênh giống nhau");
    EngineCore core;
    core.prepare(48000, 2);
    core.noteOn(60, 100);
    core.setPlaying(true);
    const auto out = renderAll(core, 2, 4800, 480);
    bool same = true;
    for (size_t i = 0; i + 1 < out.size(); i += 2) same = same && out[i] == out[i + 1];
    CHECK(same, "channels differ");
}

static void testQueueOverflow() {
    begin("Hàng đợi đầy: từ chối lệnh thay vì làm hỏng bộ nhớ");
    EngineCore core;
    core.prepare(48000, 1);
    int rejected = 0;
    for (int i = 0; i < 1000; ++i) rejected += core.noteOn(60, 100) ? 0 : 1;
    CHECK(rejected > 0, "queue never filled");
    renderAll(core, 1, 480, 480);  // phải chạy ổn
    CHECK(core.noteOn(60, 100), "queue should accept commands after being drained");
}

int main() {
    testExactBeatsAt120();
    testNoDriftAtOddTempo();
    testChunkInvariance();
    testTempoChange();
    testAccent();
    testMetronomeOffAndStop();
    testNoteLifecycle();
    testVoiceStealingAndBounds();
    testStereoDuplicates();
    testQueueOverflow();

    if (g_failures == 0) {
        std::printf("\nALL %d TESTS PASSED\n", g_tests);
        return 0;
    }
    std::printf("\n%d CHECK(S) FAILED\n", g_failures);
    return 1;
}
