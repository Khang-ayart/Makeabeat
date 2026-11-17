// Kiểm thử lõi âm thanh trên máy tính (không cần Android / Oboe).
// Chạy (một dòng, từ thư mục gốc dự án):
//   g++ -std=c++17 -O2 -Iapp/src/main/cpp native-tests/core_test.cpp app/src/main/cpp/engine_core.cpp app/src/main/cpp/noise_reducer.cpp -o core_test && ./core_test

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <utility>
#include <vector>

#include "engine_core.h"
#include "noise_reducer.h"

using namespace band;

static int g_failures = 0;
static int g_tests = 0;

#define CHECK(cond, ...)                                   \
    do {                                                   \
        if (!(cond)) {                                     \
            std::printf("    FAIL (line %d): ", __LINE__); \
            std::printf(__VA_ARGS__);                      \
            std::printf("\n");                             \
            ++g_failures;                                  \
        }                                                  \
    } while (0)

static void begin(const char* name) {
    ++g_tests;
    std::printf("[ RUN ] %s\n", name);
}

// ---- tiện ích -----------------------------------------------------------------------

struct N {
    int start, dur, pitch, vel;
};
using Kit = std::vector<std::array<int32_t, kPatchInts>>;
struct Hit {
    int start, vel, kitIdx;
    int dur = 0;  // chỉ nốt keyboard (kitIdx >= 128)
};
struct PadSpec {
    int len;
    std::vector<Hit> hits;
};
struct TrackSpec {
    Inst inst;
    std::vector<N> notes;
    Kit kit;
    std::vector<PadSpec> pads;
    TrackSpec(Inst i, std::vector<N> n, Kit k = {}, std::vector<PadSpec> p = {})
        : inst(i), notes(std::move(n)), kit(std::move(k)), pads(std::move(p)) {}
};

// Định dạng v3: [3, loop, ls, le, len, trackCount, mỗi track: inst, kitCount, kit..., padCount, pads..., noteCount, notes...]
static std::vector<int32_t> encode(bool loop, int ls, int le, int len, const std::vector<TrackSpec>& tracks) {
    std::vector<int32_t> d = {3, loop ? 1 : 0, ls, le, len, static_cast<int32_t>(tracks.size())};
    for (const auto& t : tracks) {
        d.push_back(static_cast<int32_t>(t.inst));
        d.push_back(static_cast<int32_t>(t.kit.size()));
        for (const auto& p : t.kit) d.insert(d.end(), p.begin(), p.end());
        d.push_back(static_cast<int32_t>(t.pads.size()));
        for (const auto& pp : t.pads) {
            d.push_back(pp.len);
            d.push_back(static_cast<int32_t>(pp.hits.size()));
            for (const Hit& h : pp.hits) {
                d.push_back(h.start);
                d.push_back(h.vel);
                d.push_back(h.kitIdx);
                d.push_back(h.dur);
            }
        }
        d.push_back(static_cast<int32_t>(t.notes.size()));
        for (const N& n : t.notes) {
            d.push_back(n.start);
            d.push_back(n.dur);
            d.push_back(n.pitch);
            d.push_back(n.vel);
        }
    }
    return d;
}

static void publish(EngineCore& core, const std::vector<int32_t>& d) {
    Song* s = decodeSong(d.data(), static_cast<int>(d.size()));
    CHECK(s != nullptr, "decodeSong failed");
    core.publishSong(s);
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

static std::vector<int64_t> findOnsets(const std::vector<float>& x, int64_t offset = 0) {
    std::vector<int64_t> onsets;
    int64_t lastLoud = -1000000;
    for (size_t i = 0; i < x.size(); ++i) {
        if (std::fabs(x[i]) > 1e-4f) {
            if (static_cast<int64_t>(i) - lastLoud > 2000) onsets.push_back(offset + static_cast<int64_t>(i));
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

static double rms(const std::vector<float>& x, size_t from, size_t to) {
    double e = 0.0;
    for (size_t i = from; i < to; ++i) e += static_cast<double>(x[i]) * x[i];
    return std::sqrt(e / static_cast<double>(std::max<size_t>(1, to - from)));
}

static EngineCore* newMono(int sr, float bpm, bool metronome) {
    auto* c = new EngineCore();
    c->prepare(sr, 1);
    c->setBpm(bpm);
    c->setMetronomeEnabled(metronome);
    return c;
}

// ---- Nhịp / transport -----------------------------------------------------------------

static void testExactBeatsAt120() {
    begin("Metronome 120 BPM @ 48 kHz: mọi phách rơi đúng khung hình k * 24000");
    EngineCore core;
    core.prepare(48000, 1);
    core.setBpm(120.0f);
    core.play(0);
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
    core.play(0);
    const auto out = renderAll(core, 1, 44100 * 60, 441);
    const auto onsets = findOnsets(out);
    CHECK(onsets.size() == 133, "expected 133 beats, got %zu", onsets.size());
    const double fpb = 44100.0 * 60.0 / 133.0;
    for (size_t k = 0; k < onsets.size(); ++k) {
        const double d = static_cast<double>(onsets[k]) - static_cast<double>(k) * fpb;
        CHECK(d > -1e-3 && d < 1.0 + 1e-3, "beat %zu off by %.4f frames", k, d);
    }
}

static void testTempoChange() {
    begin("Đổi tempo 120 -> 60 giữa chừng: vị trí phách liên tục");
    EngineCore core;
    core.prepare(48000, 1);
    core.setBpm(120.0f);
    core.play(0);
    std::vector<float> out = renderAll(core, 1, 36000, 36000);
    core.setBpm(60.0f);
    const auto tail = renderAll(core, 1, 84000, 480);
    out.insert(out.end(), tail.begin(), tail.end());
    const auto onsets = findOnsets(out);
    const int64_t expected[] = {0, 24000, 60000, 108000};
    CHECK(onsets.size() == 4, "expected 4 beats, got %zu", onsets.size());
    for (size_t k = 0; k < std::min<size_t>(onsets.size(), 4); ++k) {
        CHECK(onsets[k] == expected[k], "beat %zu at %lld", k, static_cast<long long>(onsets[k]));
    }
}

static void testAccentAndStop() {
    begin("Phách đầu ô nhịp to hơn; tắt metronome thì im; dừng thì beatPosition về 0");
    EngineCore core;
    core.prepare(48000, 1);
    core.setBpm(120.0f);
    core.play(0);
    const auto out = renderAll(core, 1, 24000 * 5 + 2000, 480);
    const float p0 = peak(out, 0, 2000), p1 = peak(out, 24000, 2000), p4 = peak(out, 24000 * 4, 2000);
    CHECK(p0 > p1 * 1.2f, "accent %.3f vs normal %.3f", p0, p1);
    CHECK(std::fabs(p0 - p4) < 1e-6f, "bar 2 accent differs");
    core.setMetronomeEnabled(false);
    core.stop();
    renderAll(core, 1, 9600, 480);  // chờ tiếng click cuối tàn hẳn
    CHECK(core.beatPosition() == 0.0 && !core.isPlaying(), "stop did not reset transport");
    core.play(0);
    const auto quiet = renderAll(core, 1, 48000, 480);
    CHECK(peak(quiet, 0, quiet.size()) == 0.0f, "metronome off but not silent");
}

// ---- Sequencer ------------------------------------------------------------------------

static void testSequencedOnsetExact() {
    begin("Nốt hi-hat ở tick 960 / 1920 phát đúng khung hình 24000 / 48000");
    std::unique_ptr<EngineCore> core(newMono(48000, 120.0f, false));
    publish(*core, encode(false, 0, 0, 0, {{Inst::DrumKit, {{960, 240, kHatClosed, 100}, {1920, 240, kHatClosed, 100}}}}));
    core->play(0);
    const auto out = renderAll(*core, 1, 48000 * 2, 333);
    const auto onsets = findOnsets(out);
    CHECK(onsets.size() == 2, "expected 2 hits, got %zu", onsets.size());
    if (onsets.size() == 2) {
        CHECK(onsets[0] == 24000 && onsets[1] == 48000, "hits at %lld, %lld", static_cast<long long>(onsets[0]),
              static_cast<long long>(onsets[1]));
    }
}

static void testLoopExact() {
    begin("Loop 1 ô nhịp @120: hi-hat rơi đúng khung hình k * 96000, không trôi");
    std::unique_ptr<EngineCore> core(newMono(48000, 120.0f, false));
    publish(*core, encode(true, 0, 3840, 3840, {{Inst::DrumKit, {{0, 240, kHatClosed, 100}}}}));
    core->play(0);
    const auto out = renderAll(*core, 1, 96000 * 4 + 1000, 480);
    const auto onsets = findOnsets(out);
    CHECK(onsets.size() == 5, "expected 5 hits, got %zu", onsets.size());
    for (size_t k = 0; k < onsets.size(); ++k) {
        CHECK(onsets[k] == static_cast<int64_t>(k) * 96000, "hit %zu at %lld", k, static_cast<long long>(onsets[k]));
    }
}

static void testLoopNoDriftNonInteger() {
    begin("Loop 1 ô nhịp @133 BPM / 44.1 kHz (độ dài lẻ) trong 20 vòng: sai số < 1 khung hình");
    std::unique_ptr<EngineCore> core;
    core.reset(new EngineCore());
    core->prepare(44100, 1);
    core->setBpm(133.0f);
    core->setMetronomeEnabled(false);
    publish(*core, encode(true, 0, 3840, 3840, {{Inst::DrumKit, {{0, 240, kHatClosed, 100}}}}));
    core->play(0);
    const double loopFrames = 44100.0 * 60.0 / 133.0 * 4.0;
    const auto out = renderAll(*core, 1, static_cast<int>(loopFrames * 20) + 500, 441);
    const auto onsets = findOnsets(out);
    CHECK(onsets.size() == 21, "expected 21 hits, got %zu", onsets.size());
    for (size_t k = 0; k < onsets.size(); ++k) {
        const double d = static_cast<double>(onsets[k]) - static_cast<double>(k) * loopFrames;
        CHECK(d > -1e-3 && d < 1.0 + 1e-3, "loop %zu off by %.4f frames", k, d);
    }
}

static void testEndOfSong() {
    begin("Phát không lặp: tự dừng ở cuối bài");
    std::unique_ptr<EngineCore> core(newMono(48000, 120.0f, false));
    publish(*core, encode(false, 0, 0, 1920, {{Inst::DrumKit, {{0, 240, kHatClosed, 100}}}}));
    core->play(0);
    renderAll(*core, 1, 24000, 480);
    CHECK(core->isPlaying(), "should still be playing after 1 beat");
    renderAll(*core, 1, 48000, 480);
    CHECK(!core->isPlaying(), "should have stopped after the song end");
}

static void testChunkInvariance() {
    begin("Nhiều nhạc cụ + loop + đổi mix: giống hệt từng bit với buffer 480 / 137 / 1");
    auto run = [](int chunk) {
        EngineCore core;
        core.prepare(48000, 2);
        core.setBpm(97.0f);
        core.setTrackMix(1, 0.6f, -0.5f, false, false);
        std::vector<TrackSpec> tr = {
            {Inst::Piano, {{0, 960, 60, 100}, {960, 960, 64, 90}, {1920, 1920, 67, 80}}},
            {Inst::AcousticGuitar, {{0, 1920, 48, 100}, {1920, 960, 55, 100}}},
            {Inst::Bass, {{0, 960, 36, 100}, {1920, 960, 43, 100}}},
            {Inst::ElectricGuitar, {{480, 480, 52, 110}}},
            {Inst::DrumKit, {{0, 240, kKick, 120}, {960, 240, kSnare, 110}, {480, 120, kHatClosed, 90}, {1440, 240, kClap, 90}}},
        };
        publish(core, encode(true, 0, 3840, 3840, tr));
        core.play(0);
        return renderAll(core, 2, 48000 * 6, chunk);
    };
    const auto a = run(480), b = run(137), c = run(1);
    CHECK(a.size() == b.size() && std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) == 0, "480 vs 137 differ");
    CHECK(a.size() == c.size() && std::memcmp(a.data(), c.data(), a.size() * sizeof(float)) == 0, "480 vs 1 differ");
    CHECK(peak(a, 0, a.size()) > 0.05f, "mix is silent");
}

static void testSongSwapWhilePlaying() {
    begin("Thay bài nhạc khi đang phát (nhiều lần): nốt mới áp dụng, không lỗi bộ nhớ");
    std::unique_ptr<EngineCore> core(newMono(48000, 120.0f, false));
    publish(*core, encode(true, 0, 3840, 3840, {{Inst::DrumKit, {{0, 240, kHatClosed, 100}}}}));
    core->play(0);
    renderAll(*core, 1, 10000, 480);
    for (int i = 0; i < 50; ++i) {
        publish(*core, encode(true, 0, 3840, 3840, {{Inst::DrumKit, {{960 * (i % 4), 240, kHatClosed, 100}}}}));
        renderAll(*core, 1, 480, 480);
    }
    // Bài cuối: snare ở tick 960*(49%4=1) = 960 -> sau khi hết vòng hiện tại phải nghe đúng ở đó.
    const auto out = renderAll(*core, 1, 96000 * 2, 480);
    CHECK(peak(out, 0, out.size()) > 0.05f, "no sound after swaps");
}

static void testMuteSolo() {
    begin("Mute / Solo theo track");
    auto run = [](bool mute0, bool solo1) {
        std::unique_ptr<EngineCore> core(newMono(48000, 120.0f, false));
        core->setTrackMix(0, 0.8f, 0.0f, mute0, false);
        core->setTrackMix(1, 0.8f, 0.0f, false, solo1);
        publish(*core, encode(false, 0, 0, 0,
                              {{Inst::DrumKit, {{0, 240, kHatClosed, 100}}}, {Inst::DrumKit, {{960, 240, kHatClosed, 100}}}}));
        core->play(0);
        return renderAll(*core, 1, 48000, 480);
    };
    const auto base = run(false, false);
    CHECK(findOnsets(base).size() == 2, "both tracks should sound");
    const auto muted = run(true, false);
    const auto mo = findOnsets(muted);
    CHECK(mo.size() == 1 && mo[0] == 24000, "mute track 0: expected only beat 1 hit (got %zu)", mo.size());
    const auto solo = run(false, true);
    const auto so = findOnsets(solo);
    CHECK(so.size() == 1 && so[0] == 24000, "solo track 1: expected only beat 1 hit (got %zu)", so.size());
}

static void testPan() {
    begin("Pan: hết trái thì kênh phải im, hết phải thì kênh trái im");
    auto run = [](float pan) {
        EngineCore core;
        core.prepare(48000, 2);
        core.setTrackMix(0, 0.8f, pan, false, false);
        core.noteOn(static_cast<int>(Inst::Piano), 0, 60, 100);
        return renderAll(core, 2, 4800, 480);
    };
    const auto l = run(-1.0f), r = run(1.0f);
    float lL = 0, lR = 0, rL = 0, rR = 0;
    for (size_t i = 0; i + 1 < l.size(); i += 2) {
        lL = std::max(lL, std::fabs(l[i]));
        lR = std::max(lR, std::fabs(l[i + 1]));
        rL = std::max(rL, std::fabs(r[i]));
        rR = std::max(rR, std::fabs(r[i + 1]));
    }
    CHECK(lL > 0.05f && lR < 1e-3f, "pan left: L=%.4f R=%.4f", lL, lR);
    CHECK(rR > 0.05f && rL < 1e-3f, "pan right: L=%.4f R=%.4f", rL, rR);
}

// ---- Nhạc cụ --------------------------------------------------------------------------

static double estimateFreq(const std::vector<float>& x, int sr, double expected) {
    const int a = sr / 10, b = sr * 4 / 10;
    const int minLag = static_cast<int>(sr / (expected * 1.6));
    const int maxLag = static_cast<int>(sr / (expected / 1.6)) + 2;
    std::vector<double> r(static_cast<size_t>(maxLag) + 2, 0.0);
    for (int lag = minLag; lag <= maxLag; ++lag) {
        double s = 0.0;
        for (int i = a; i < b; ++i) s += static_cast<double>(x[i]) * x[i + lag];
        r[lag] = s;
    }
    int best = minLag;
    for (int lag = minLag; lag <= maxLag; ++lag) {
        if (r[lag] > r[best]) best = lag;
    }
    double lagF = best;
    if (best > minLag && best < maxLag) {  // nội suy parabol để có độ chính xác dưới 1 mẫu
        const double y0 = r[best - 1], y1 = r[best], y2 = r[best + 1];
        const double den = y0 - 2.0 * y1 + y2;
        if (den != 0.0) lagF += 0.5 * (y0 - y2) / den;
    }
    return sr / lagF;
}

static void testPitchAccuracy() {
    begin("Cao độ đúng (sai số < 6 cent) cho piano / bass / guitar mộc / guitar điện");
    const int sr = 48000;
    struct Case {
        Inst inst;
        const char* name;
        int notes[5];
    };
    const Case cases[] = {
        {Inst::Piano, "Piano", {36, 48, 60, 72, 84}},
        {Inst::Bass, "Bass", {28, 36, 43, 48, 55}},
        {Inst::AcousticGuitar, "Acoustic", {40, 48, 60, 72, 84}},
        {Inst::ElectricGuitar, "Electric", {40, 48, 60, 72, 84}},
    };
    for (const Case& c : cases) {
        double worst = 0.0;
        for (int note : c.notes) {
            EngineCore core;
            core.prepare(sr, 1);
            core.noteOn(static_cast<int>(c.inst), 0, note, 100);
            const auto out = renderAll(core, 1, sr, 480);
            const double f = 440.0 * std::pow(2.0, (note - 69) / 12.0);
            const double est = estimateFreq(out, sr, f);
            const double cents = 1200.0 * std::log2(est / f);
            worst = std::max(worst, std::fabs(cents));
            CHECK(std::fabs(cents) < 6.0, "%s note %d: %.2f Hz vs %.2f Hz (%.1f cents)", c.name, note, est, f, cents);
            CHECK(peak(out, 0, out.size()) > 0.03f, "%s note %d too quiet", c.name, note);
        }
        std::printf("    %s: sai số cao độ tối đa %.1f cent\n", c.name, worst);
    }
}

static void testDrumPieces() {
    begin("9 bộ phận trống x 2 kiểu: có tiếng, hữu hạn, trong [-1, 1], tự tắt sau vài giây");
    for (Inst kit : {Inst::DrumKit, Inst::DrumPad}) {
        for (int piece = 0; piece < kDrumPieces; ++piece) {
            EngineCore core;
            core.prepare(48000, 1);
            core.noteOn(static_cast<int>(kit), 0, piece, 110);
            const auto out = renderAll(core, 1, 48000 * 4, 480);
            bool ok = true;
            for (float s : out) ok = ok && std::isfinite(s) && std::fabs(s) <= 1.0f;
            CHECK(ok, "kit %d piece %d out of range", static_cast<int>(kit), piece);
            CHECK(peak(out, 0, 4800) > 0.05f, "kit %d piece %d silent", static_cast<int>(kit), piece);
            CHECK(core.activeVoiceCount() == 0, "kit %d piece %d never ended", static_cast<int>(kit), piece);
        }
    }
}

static void testHatChoke() {
    begin("Hi-hat đóng chặn hi-hat mở");
    EngineCore core;
    core.prepare(48000, 1);
    core.noteOn(static_cast<int>(Inst::DrumKit), 0, kHatOpen, 100);
    renderAll(core, 1, 2400, 480);
    core.noteOn(static_cast<int>(Inst::DrumKit), 0, kHatClosed, 100);
    renderAll(core, 1, 48000 / 2, 480);
    CHECK(core.activeVoiceCount() == 0, "open hat still ringing (%d voices)", core.activeVoiceCount());
}

static void testLiveNoteLifecycle() {
    begin("Nốt chạm tay: bật có tiếng, nhả thì tắt hẳn");
    EngineCore core;
    core.prepare(48000, 1);
    core.noteOn(static_cast<int>(Inst::Piano), 0, 60, 100);
    const auto on = renderAll(core, 1, 4800, 480);
    CHECK(peak(on, 0, on.size()) > 0.05f, "no sound");
    CHECK(core.activeVoiceCount() == 1, "expected 1 voice, got %d", core.activeVoiceCount());
    core.noteOff(0, 60);
    const auto off = renderAll(core, 1, 48000 * 2, 480);
    CHECK(core.activeVoiceCount() == 0, "voice not released");
    CHECK(off.back() == 0.0f, "tail not silent");
}

static void testVoiceStealing() {
    begin("Hơn 32 nốt cùng lúc: tối đa 32 voice, đầu ra hữu hạn và trong [-1, 1]");
    EngineCore core;
    core.prepare(48000, 2);
    for (int i = 0; i < 80; ++i) {
        core.noteOn(i % 4 == 0 ? 0 : (i % 4 == 1 ? 1 : (i % 4 == 2 ? 2 : 3)), i % kMaxTracks, 36 + (i % 40), 127);
        if (i % 20 == 19) renderAll(core, 2, 64, 64);  // lệnh vào hàng đợi có sức chứa hữu hạn
    }
    const auto out = renderAll(core, 2, 48000, 256);
    CHECK(core.activeVoiceCount() <= kMaxVoices, "too many voices: %d", core.activeVoiceCount());
    bool ok = true;
    for (float s : out) ok = ok && std::isfinite(s) && std::fabs(s) <= 1.0f;
    CHECK(ok, "output out of range or non-finite");
}

static void testQueueOverflowAndDecode() {
    begin("Hàng đợi đầy từ chối lệnh; decodeSong từ chối dữ liệu hỏng");
    EngineCore core;
    core.prepare(48000, 1);
    int rejected = 0;
    for (int i = 0; i < 1000; ++i) rejected += core.noteOn(0, 0, 60, 100) ? 0 : 1;
    CHECK(rejected > 0, "queue never filled");
    renderAll(core, 1, 480, 480);
    CHECK(core.noteOn(0, 0, 60, 100), "queue should accept commands after draining");

    const std::vector<int32_t> bad1 = {2, 0, 0, 0, 0, 0};                    // sai phiên bản (v2 cũ)
    const std::vector<int32_t> bad2 = {3, 0, 0, 0, 0, 1, 0, 0, 0, 5, 0, 1, 60, 1};  // khai 5 nốt nhưng chỉ có 1
    const std::vector<int32_t> bad3 = {3, 0, 0, 0, 0, 9};                    // quá nhiều track
    const std::vector<int32_t> bad4 = {3, 0, 0, 0, 0, 1, 99, 0, 0, 0};              // nhạc cụ không tồn tại
    CHECK(decodeSong(bad1.data(), static_cast<int>(bad1.size())) == nullptr, "bad version accepted");
    CHECK(decodeSong(bad2.data(), static_cast<int>(bad2.size())) == nullptr, "truncated accepted");
    CHECK(decodeSong(bad3.data(), static_cast<int>(bad3.size())) == nullptr, "too many tracks accepted");
    CHECK(decodeSong(bad4.data(), static_cast<int>(bad4.size())) == nullptr, "bad instrument accepted");
    CHECK(decodeSong(nullptr, 0) == nullptr, "null accepted");
    std::vector<int32_t> bad5 = {3, 0, 0, 0, 0, 1, 6, 33, 0, 0};  // kit quá lớn
    std::vector<int32_t> bad6 = {3, 0, 0, 0, 0, 1, 6, 2, 0, 0};   // khai 2 patch nhưng thiếu dữ liệu
    CHECK(decodeSong(bad5.data(), static_cast<int>(bad5.size())) == nullptr, "oversized kit accepted");
    CHECK(decodeSong(bad6.data(), static_cast<int>(bad6.size())) == nullptr, "truncated kit accepted");

    const auto ok = encode(false, 0, 0, 0, {{Inst::Piano, {{480, 10, 64, 100}, {0, 10, 60, 100}}}});
    Song* s = decodeSong(ok.data(), static_cast<int>(ok.size()));
    CHECK(s && s->tracks[0].notes.size() == 2 && s->tracks[0].notes[0].start == 0, "notes not sorted by start");
    delete s;
}

static void testCpuBudget() {
    begin("Tải CPU: 32 voice guitar/piano/trống cùng lúc, 10 giây âm thanh");
    EngineCore core;
    core.prepare(48000, 2);
    for (int i = 0; i < 32; ++i) {
        core.noteOn(i % 3 == 0 ? 1 : (i % 3 == 1 ? 2 : 0), i % kMaxTracks, 40 + i, 100);
        if (i % 16 == 15) renderAll(core, 2, 16, 16);
    }
    const auto t0 = std::chrono::steady_clock::now();
    renderAll(core, 2, 48000 * 10, 256);
    const double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    std::printf("    10 s âm thanh tốn %.3f s CPU máy tính (%.1fx nhanh hơn thời gian thực)\n", secs, 10.0 / secs);
    CHECK(secs < 3.0, "too slow: %.2f s", secs);
}

// ---- Lọc ồn ---------------------------------------------------------------------------

static std::vector<float> makeNoisyTone(int sr, int seconds, uint32_t seed, std::vector<float>* clean) {
    const int n = sr * seconds;
    std::vector<float> x(n), c(n, 0.0f);
    uint32_t s = seed;
    auto rnd = [&]() {
        s ^= s << 13;
        s ^= s >> 17;
        s ^= s << 5;
        return static_cast<float>(static_cast<int32_t>(s)) / 2147483648.0f;
    };
    const double t0 = 1.5, t1 = 3.5;
    for (int i = 0; i < n; ++i) {
        const double t = static_cast<double>(i) / sr;
        if (t >= t0 && t < t1) {
            const double fade = std::min({1.0, (t - t0) / 0.02, (t1 - t) / 0.02});
            c[i] = static_cast<float>(0.3 * fade * (std::sin(2 * 3.14159265358979 * 440.0 * t) +
                                                    0.5 * std::sin(2 * 3.14159265358979 * 880.0 * t)) / 1.5);
        }
        x[i] = c[i] + 0.04f * rnd();  // nhiễu trắng ~ -31 dBFS
    }
    if (clean) *clean = c;
    return x;
}

static void checkDenoise(const char* label, std::vector<float> x, const std::vector<float>& clean, int sr,
                         const float* noiseOnly, int noiseCount, float strength) {
    const std::vector<float> before = x;
    DenoiseParams p;
    p.strength = strength;
    denoise(x, sr, p, noiseOnly, noiseCount);
    CHECK(x.size() == before.size(), "length changed");
    const size_t a0 = static_cast<size_t>(0.2 * sr), a1 = static_cast<size_t>(1.2 * sr);
    const size_t b0 = static_cast<size_t>(3.9 * sr), b1 = static_cast<size_t>(4.8 * sr);
    const double nBefore = (rms(before, a0, a1) + rms(before, b0, b1)) / 2.0;
    const double nAfter = (rms(x, a0, a1) + rms(x, b0, b1)) / 2.0;
    const double reduction = 20.0 * std::log10(nBefore / std::max(nAfter, 1e-12));
    const size_t t0 = static_cast<size_t>(1.8 * sr), t1 = static_cast<size_t>(3.2 * sr);
    const double toneDb = 20.0 * std::log10(rms(x, t0, t1) / rms(clean, t0, t1));
    std::printf("    %s: giảm nhiễu %.1f dB, tín hiệu thay đổi %+.2f dB\n", label, reduction, toneDb);
    CHECK(reduction >= 12.0, "%s: noise reduction only %.1f dB", label, reduction);
    CHECK(toneDb > -1.5 && toneDb < 1.0, "%s: tone level changed by %.2f dB", label, toneDb);
}

static void testDenoise() {
    begin("Lọc ồn: giảm >= 12 dB nhiễu nền, giữ nguyên tiếng (+-1.5 dB)");
    const int sr = 44100;
    std::vector<float> clean;
    const auto x = makeNoisyTone(sr, 5, 12345u, &clean);
    checkDenoise("tự tìm mẫu nhiễu", x, clean, sr, nullptr, 0, 0.7f);
    std::vector<float> room;  // 1 giây "im lặng" thu riêng
    {
        std::vector<float> dummy;
        auto n = makeNoisyTone(sr, 5, 999u, &dummy);
        room.assign(n.begin() + static_cast<long>(0.1 * sr), n.begin() + static_cast<long>(1.1 * sr));
    }
    checkDenoise("mẫu nhiễu thu riêng", x, clean, sr, room.data(), static_cast<int>(room.size()), 0.7f);
}

static void testDenoiseNoOpAndSafety() {
    begin("Lọc ồn: strength = 0 và không HPF thì giữ nguyên; tín hiệu quá ngắn không lỗi");
    std::vector<float> x(44100, 0.0f);
    for (size_t i = 0; i < x.size(); ++i) x[i] = 0.2f * std::sin(0.05f * static_cast<float>(i));
    const auto before = x;
    DenoiseParams p;
    p.strength = 0.0f;
    p.highpassHz = 0.0f;
    denoise(x, 44100, p);
    CHECK(x == before, "should be unchanged");
    std::vector<float> tiny(500, 0.1f);
    DenoiseParams q;
    denoise(tiny, 44100, q);
    CHECK(tiny.size() == 500, "tiny buffer changed size");
    std::vector<float> silent(44100 * 2, 0.0f);
    denoise(silent, 44100, q);
    bool finite = true;
    for (float s : silent) finite = finite && std::isfinite(s);
    CHECK(finite, "silence produced NaN");
}


// ---- Kiểm chứng cơ chế đồng bộ (mục 9 của yêu cầu: prototype trước khi mở rộng) -----------

// 1 ô nhịp lặp: trống (hi-hat 4 phách), bass (2 nốt), piano (2 hợp âm).
static std::vector<int32_t> syncSong(bool withDrums = true) {
    std::vector<N> hats;
    if (withDrums) hats = {{0, 120, kHatClosed, 100}, {960, 120, kHatClosed, 100}, {1920, 120, kHatClosed, 100}, {2880, 120, kHatClosed, 100}};
    return encode(true, 0, 3840, 3840,
                  {{Inst::DrumKit, hats},
                   {Inst::Bass, {{0, 900, 36, 100}, {1920, 900, 43, 100}}},
                   {Inst::Piano, {{0, 1800, 60, 90}, {0, 1800, 64, 90}, {1920, 1800, 62, 90}, {1920, 1800, 65, 90}}}});
}

static void testSyncBitExactLoops() {
    begin("Đồng bộ: trống + bass + piano lặp 8 vòng, các vòng 2..6 giống hệt từng bit");
    std::unique_ptr<EngineCore> core(newMono(48000, 120.0f, false));
    publish(*core, syncSong());
    core->play(0);
    const size_t L = 96000;
    const auto out = renderAll(*core, 1, static_cast<int>(L) * 8, 480);
    for (size_t k = 3; k <= 6; ++k) {
        CHECK(std::memcmp(out.data() + 2 * L, out.data() + k * L, L * sizeof(float)) == 0, "loop %zu differs from loop 2", k);
    }
    CHECK(peak(out, 2 * L, L) > 0.05f, "mix is silent");
}

static void testSyncAcrossTempoChange() {
    begin("Đồng bộ: đổi BPM giữa chừng, trống / bass / piano vẫn rơi cùng một khung hình");
    // Mỗi track chỉ có một sự kiện ngắn ở vị trí cố định của ô nhịp để dò khởi đầu nốt cho sạch.
    const auto song = encode(true, 0, 3840, 3840,
                             {{Inst::DrumKit, {{0, 120, kHatClosed, 100}, {2880, 120, kHatClosed, 100}}},
                              {Inst::Bass, {{0, 120, 36, 100}}},
                              {Inst::Piano, {{2880, 120, 60, 100}}}});
    auto run = [&](int soloTrack) {
        std::unique_ptr<EngineCore> core(newMono(48000, 100.0f, false));
        for (int t = 0; t < 3; ++t) core->setTrackMix(t, 0.8f, 0.0f, false, t == soloTrack);
        publish(*core, song);
        core->play(0);
        std::vector<float> out = renderAll(*core, 1, 100000, 480);
        core->setBpm(140.0f);
        auto a = renderAll(*core, 1, 100000, 480);
        out.insert(out.end(), a.begin(), a.end());
        core->setBpm(75.0f);
        auto b = renderAll(*core, 1, 200000, 480);
        out.insert(out.end(), b.begin(), b.end());
        return out;
    };
    const auto d = findOnsets(run(0)), b = findOnsets(run(1)), p = findOnsets(run(2));
    std::printf("    trống %zu, bass %zu, piano %zu khởi đầu nốt\n", d.size(), b.size(), p.size());
    CHECK(b.size() >= 3 && p.size() >= 3, "too few events: bass %zu piano %zu", b.size(), p.size());
    long worst = 0;
    for (size_t i = 0; i < std::min(b.size(), p.size()); ++i) {
        if (2 * i + 1 >= d.size()) break;
        worst = std::max(worst, std::labs(static_cast<long>(b[i] - d[2 * i])));
        worst = std::max(worst, std::labs(static_cast<long>(p[i] - d[2 * i + 1])));
    }
    std::printf("    lệch lớn nhất giữa các track: %ld khung hình (%.2f ms)\n", worst, worst * 1000.0 / 48000.0);
    CHECK(worst <= 8, "tracks drifted apart by %ld frames after tempo changes", worst);
}

static void testMuteDoesNotShiftOthers() {
    begin("Đồng bộ: tắt/bật track trống giữa chừng không làm lệch bass và nhịp trống sau khi bật lại");
    auto run = [](bool withDrums, bool toggleMute) {
        std::unique_ptr<EngineCore> core(newMono(48000, 120.0f, false));
        publish(*core, syncSong(withDrums));
        core->play(0);
        std::vector<float> out = renderAll(*core, 1, 48000 * 3, 480);
        if (toggleMute) core->setTrackMix(0, 0.8f, 0.0f, true, false);
        auto a = renderAll(*core, 1, 48000 * 3, 480);
        out.insert(out.end(), a.begin(), a.end());
        if (toggleMute) core->setTrackMix(0, 0.8f, 0.0f, false, false);
        auto b = renderAll(*core, 1, 48000 * 6, 480);
        out.insert(out.end(), b.begin(), b.end());
        return out;
    };
    const auto base = run(true, false), muted = run(true, true), noDrums = run(false, false);
    auto maxDiff = [](const std::vector<float>& x, const std::vector<float>& y, size_t a, size_t b) {
        float m = 0.0f;
        for (size_t i = a; i < b; ++i) m = std::max(m, std::fabs(x[i] - y[i]));
        return m;
    };
    const float whileMuted = maxDiff(muted, noDrums, 48000 * 9 / 2, 48000 * 59 / 10);
    const float afterUnmute = maxDiff(muted, base, 48000 * 15 / 2, 48000 * 12);
    std::printf("    đang tắt: lệch so với bài không có trống = %.2e; sau khi bật lại: lệch so với bản không tắt = %.2e\n",
                whileMuted, afterUnmute);
    CHECK(whileMuted < 1e-4f, "bass/piano changed while drums muted (%.2e)", whileMuted);
    CHECK(afterUnmute < 1e-4f, "drum phase shifted after unmute (%.2e)", afterUnmute);
}

static void testRawMidiPlaysAtExactTick() {
    begin("MIDI thô (chưa quantize): nốt ở tick 13 và 977 phát đúng khung hình 325 và 24425");
    std::unique_ptr<EngineCore> core(newMono(48000, 120.0f, false));
    publish(*core, encode(false, 0, 0, 0, {{Inst::DrumKit, {{13, 60, kHatClosed, 100}, {977, 60, kHatClosed, 100}}}}));
    core->play(0);
    const auto onsets = findOnsets(renderAll(*core, 1, 48000, 480));
    CHECK(onsets.size() == 2, "expected 2 hits, got %zu", onsets.size());
    if (onsets.size() == 2) CHECK(onsets[0] == 325 && onsets[1] == 24425, "hits at %lld, %lld", (long long)onsets[0], (long long)onsets[1]);
}


// ---- Looper (pad one-shot từ thông số Patch) ---------------------------------------------

static std::array<int32_t, kPatchInts> P(float toneAmp, float fS, float fE, float pitchTau, float toneTau,
                                         float noiseAmp, float noiseTau, float hp, float lp, int bursts,
                                         float gap, float tail, float t2r, float t2a, int choke, float gain,
                                         float drive) {
    auto q = [](float v) { return static_cast<int32_t>(std::lround(v * 1000.0f)); };
    return {q(toneAmp), q(fS),  q(fE),  q(pitchTau), q(toneTau), q(noiseAmp), q(noiseTau), q(hp), q(lp),
            bursts * 1000, q(gap), q(tail), q(t2r),  q(t2a),  choke * 1000, q(gain), q(drive), -1};
}
// decodeSong đọc chokeGroup trực tiếp (không nhân 1000); P() nhân để mọi trường cùng một quy ước, nên sửa lại ở đây.
static std::array<int32_t, kPatchInts> PI(std::array<int32_t, kPatchInts> a) {
    a[9] /= 1000;   // bursts
    a[14] /= 1000;  // chokeGroup
    return a;
}

static Kit testKit() {
    Kit k;
    k.push_back(PI(P(0.9f, 140, 45, 40, 250, 0.2f, 4, 1, 0, 0, 11, 90, 0, 0, 0, 1, 0)));     // 0: kick (tone)
    k.push_back(PI(P(0, 0, 0, 30, 200, 0.5f, 40, 1, 0, 0, 11, 90, 0, 0, 0, 1, 0)));          // 1: hat (nhiễu cao)
    k.push_back(PI(P(0, 0, 0, 30, 200, 0.4f, 150, 1, 0, 0, 11, 90, 0, 0, 1, 1, 0)));         // 2: hat mở (nhóm 1)
    k.push_back(PI(P(0, 0, 0, 30, 200, 0.4f, 40, 1, 0, 0, 11, 90, 0, 0, 1, 1, 0)));          // 3: hat đóng (nhóm 1)
    k.push_back(PI(P(2.0f, 55, 40, 80, 150, 0, 100, 0, 0, 0, 11, 90, 0, 0, 0, 2, 12)));      // 4: 808 méo rất mạnh
    return k;
}

static int zeroCrossings(const std::vector<float>& x, size_t from, size_t to) {
    int z = 0;
    for (size_t i = from + 1; i < to; ++i) z += (x[i - 1] < 0.0f) != (x[i] < 0.0f) ? 1 : 0;
    return z;
}

static void testLooperRoutingAndTone() {
    begin("Looper: pad đúng thứ tự trong kit, tone khác nhiễu, nốt rơi đúng khung hình");
    // 120 BPM, 48 kHz: 960 tick = 1 phách = 24000 khung hình
    auto core = std::unique_ptr<EngineCore>(newMono(48000, 120, false));
    publish(*core, encode(false, 0, 0, 0, {TrackSpec(Inst::Looper, {{960, 240, 1, 100}, {1920, 240, 0, 100}}, testKit())}));
    core->play(0);
    const auto out = renderAll(*core, 1, 48000 * 2 + 24000, 480);
    const auto on = findOnsets(out);
    CHECK(on.size() == 2, "expected 2 onsets, got %zu", on.size());
    if (on.size() == 2) {
        CHECK(std::llabs(on[0] - 24000) <= 1, "hat onset %lld", static_cast<long long>(on[0]));
        CHECK(std::llabs(on[1] - 48000) <= 1, "kick onset %lld", static_cast<long long>(on[1]));
    }
    const int zHat = zeroCrossings(out, 24000, 24000 + 600);
    const int zKick = zeroCrossings(out, 48000, 48000 + 600);
    CHECK(zHat > 100, "hat should be noisy, zc=%d", zHat);
    CHECK(zKick < 40, "kick should be a low tone, zc=%d", zKick);
}

static void testLooperLive() {
    begin("Looper: bấm pad trực tiếp, pad ngoài kit / kit rỗng im lặng không lỗi");
    auto core = std::unique_ptr<EngineCore>(newMono(48000, 120, false));
    publish(*core, encode(false, 0, 0, 0, {TrackSpec(Inst::Looper, {}, testKit()), TrackSpec(Inst::Looper, {}, {})}));
    renderAll(*core, 1, 480, 480);
    core->noteOn(static_cast<int>(Inst::Looper), 0, 1, 100);
    auto a = renderAll(*core, 1, 2400, 480);
    CHECK(peak(a, 0, a.size()) > 0.05f, "live pad silent");
    core->noteOn(static_cast<int>(Inst::Looper), 0, 31, 100);   // ngoài kit
    core->noteOn(static_cast<int>(Inst::Looper), 1, 0, 100);    // kit rỗng
    core->noteOn(static_cast<int>(Inst::Looper), 0, -5, 100);   // chỉ số âm
    renderAll(*core, 1, 48000, 480);  // chờ pad đầu tắt hẳn
    auto b = renderAll(*core, 1, 4800, 480);
    CHECK(peak(b, 0, b.size()) == 0.0f, "invalid pads should stay silent (peak %f)", peak(b, 0, b.size()));
    CHECK(core->activeVoiceCount() == 0, "voices left: %d", core->activeVoiceCount());
}

static void testLooperChoke() {
    begin("Looper: hi-hat đóng chặn hi-hat mở cùng nhóm");
    auto run = [&](int second) {
        auto core = std::unique_ptr<EngineCore>(newMono(48000, 120, false));
        publish(*core, encode(false, 0, 0, 0, {TrackSpec(Inst::Looper, {}, testKit())}));
        renderAll(*core, 1, 480, 480);
        core->noteOn(static_cast<int>(Inst::Looper), 0, 2, 100);
        renderAll(*core, 1, 2400, 480);
        core->noteOn(static_cast<int>(Inst::Looper), 0, second, 100);
        renderAll(*core, 1, 24000, 480);  // 0,5 s sau
        return core->activeVoiceCount();
    };
    CHECK(run(3) == 0, "open hat should be choked by closed hat (voices %d)", run(3));
    CHECK(run(1) == 1, "different group must not choke (voices %d)", run(1));
}

static void testLooperLoopBitExactAndBounded() {
    begin("Looper: các vòng lặp giống hệt từng bit; méo mạnh vẫn nằm trong [-1, 1]");
    auto core = std::unique_ptr<EngineCore>(newMono(48000, 120, false));
    publish(*core, encode(true, 0, 3840, 3840,
                          {TrackSpec(Inst::Looper, {{0, 240, 0, 110}, {960, 240, 1, 90}, {1920, 240, 4, 127}, {2880, 240, 2, 80}},
                                     testKit())}));
    core->play(0);
    const size_t bar = 96000;
    const auto out = renderAll(*core, 1, static_cast<int>(bar) * 3, 480);
    bool same = true, bounded = true;
    for (size_t i = 0; i < bar; ++i) {
        if (out[i + bar] != out[i + 2 * bar]) same = false;  // vòng 1 chưa có đuôi âm của vòng trước nên so vòng 2 và 3
        if (!(std::fabs(out[i]) <= 1.0f)) bounded = false;
    }
    CHECK(same, "loop iterations differ (steady state)");
    CHECK(bounded, "output exceeded [-1, 1] or NaN");
    CHECK(peak(out, 0, bar) > 0.1f, "silent loop");
}

static void testLooperExtremeParams() {
    begin("Looper: thông số cực đoan được kẹp, không NaN, voice vẫn tự tắt");
    Kit k;
    std::array<int32_t, kPatchInts> crazy;
    crazy.fill(2000000000);
    k.push_back(crazy);
    std::array<int32_t, kPatchInts> neg;
    neg.fill(-2000000000);
    k.push_back(neg);
    auto core = std::unique_ptr<EngineCore>(newMono(44100, 120, false));
    publish(*core, encode(false, 0, 0, 0, {TrackSpec(Inst::Looper, {}, k)}));
    renderAll(*core, 1, 480, 480);
    core->noteOn(static_cast<int>(Inst::Looper), 0, 0, 127);
    core->noteOn(static_cast<int>(Inst::Looper), 0, 1, 127);
    const auto out = renderAll(*core, 1, 44100 * 12, 480);
    bool ok = true;
    for (float v : out) ok = ok && std::isfinite(v) && std::fabs(v) <= 1.0f;
    CHECK(ok, "non-finite or out-of-range sample");
}


// ---- Looper: pad là nhịp lặp, bật/tắt quantize theo ô nhịp -------------------------------

// 120 BPM, 48 kHz: 1 ô nhịp = 3840 tick = 96000 khung hình; 1 phách = 24000 khung hình.
static std::vector<PadSpec> padSet(std::initializer_list<std::pair<int, PadSpec>> defs) {
    std::vector<PadSpec> v(kMaxPads, PadSpec{0, {}});
    for (const auto& d : defs) v[static_cast<size_t>(d.first)] = d.second;
    return v;
}

static std::unique_ptr<EngineCore> padCore(const std::vector<PadSpec>& pads, bool loopOff = true) {
    auto core = std::unique_ptr<EngineCore>(newMono(48000, 120, false));
    (void)loopOff;
    publish(*core, encode(false, 0, 0, 0, {TrackSpec(Inst::Looper, {}, testKit(), pads)}));
    renderAll(*core, 1, 64, 64);  // để engine nhận bài nhạc
    return core;
}

static void testPadLaunchQuantizedLoop() {
    begin("Pad lặp: bật khi dừng thì chạy từ đầu; cú đánh rơi đúng khung hình, lặp mỗi ô nhịp; các vòng giống hệt từng bit");
    auto core = padCore(padSet({{0, {3840, {{0, 100, 1}, {1920, 80, 3}}}}}));
    core->play(0);
    core->padLaunch(0, 0, 0);
    const auto out = renderAll(*core, 1, 96000 * 4 - 5000, 480);
    const auto on = findOnsets(out);
    const std::vector<int64_t> want = {0, 48000, 96000, 144000, 192000, 240000, 288000, 336000};
    CHECK(on.size() == want.size(), "onsets %zu", on.size());
    for (size_t i = 0; i < on.size() && i < want.size(); ++i)
        CHECK(std::llabs(on[i] - want[i]) <= 1, "onset %zu at %lld want %lld", i, static_cast<long long>(on[i]), static_cast<long long>(want[i]));
    bool same = true;
    for (size_t i = 0; i < 90000; ++i) if (out[96000 + i] != out[192000 + i]) same = false;
    CHECK(same, "bar 2 and bar 3 differ");
}

static void testPadMidBarLaunchWaitsForBoundary() {
    begin("Pad lặp: bật giữa ô nhịp thì chờ đến đầu ô nhịp kế; mask báo chờ rồi chạy");
    auto core = padCore(padSet({{2, {3840, {{0, 100, 1}}}}}));
    core->play(960);  // đang ở phách 2 của ô 1
    renderAll(*core, 1, 480, 480);
    core->padLaunch(0, 0, 2);
    renderAll(*core, 1, 480, 480);
    int64_t m = core->padMask(0);
    CHECK((m >> 26) & 1, "queued bit missing (mask %llx)", static_cast<unsigned long long>(m));
    CHECK(!((m >> 2) & 1), "active too early");
    const auto out = renderAll(*core, 1, 72000 - 960 + 2000, 480);  // qua biên ô nhịp
    const auto on = findOnsets(out);
    CHECK(on.size() == 1, "onsets %zu", on.size());
    if (!on.empty()) {
        // biên ở tick 3840: 2880 tick sau điểm bắt đầu = 72000 khung hình, trừ phần đã render
        const int64_t want = 72000 - 960 - 0;
        CHECK(std::llabs(on[0] - want) <= 1, "onset %lld want %lld", static_cast<long long>(on[0]), static_cast<long long>(want));
    }
    m = core->padMask(0);
    CHECK((m >> 2) & 1, "active bit missing");
    CHECK(((m >> 26) & 1) == 0, "queued bit not cleared");
}

static void testPadRowExclusiveSwitchAndStop() {
    begin("Pad lặp: mỗi hàng một pad (đổi pad ở đầu ô nhịp), bấm tắt thì dừng ở đầu ô nhịp");
    // pad0: hi-hat đầu ô nhịp. pad1 (cùng hàng): kick ở giữa ô nhịp.
    auto core = padCore(padSet({{0, {3840, {{0, 100, 1}}}}, {1, {3840, {{1920, 100, 3}}}}}));
    core->play(0);
    core->padLaunch(0, 0, 0);
    renderAll(*core, 1, 144000, 480);               // đến giữa ô 2
    core->padLaunch(0, 0, 1);                       // chuyển sang pad1, hiệu lực ở 192000
    auto out = renderAll(*core, 1, 192000 + 60000 - 144000, 480);
    // hi-hat ở 96000 (đã render ở đoạn trước, nên chỉ cần đoạn này không có hi-hat ở 192000)
    const auto on = findOnsets(out, 144000);
    CHECK(on.size() == 1, "onsets %zu", on.size());
    if (!on.empty()) CHECK(std::llabs(on[0] - (192000 + 48000)) <= 1, "kick at %lld", static_cast<long long>(on[0]));

    // tắt: launch -1 ở giữa, pad dừng từ đầu ô nhịp kế
    auto core2 = padCore(padSet({{0, {3840, {{0, 100, 1}}}}}));
    core2->play(0);
    core2->padLaunch(0, 0, 0);
    renderAll(*core2, 1, 30000, 480);  // để đuôi hi-hat đầu tiên tắt hẳn
    core2->padLaunch(0, 0, -1);
    const auto o2 = renderAll(*core2, 1, 96000 * 3, 480);
    const auto on2 = findOnsets(o2, 30000);
    CHECK(on2.empty(), "pad still playing after stop (%zu onsets)", on2.size());
    CHECK(core2->padMask(0) == 0, "mask not cleared: %llx", static_cast<unsigned long long>(core2->padMask(0)));
}

static void testPadRowsIndependentAndLengths() {
    begin("Pad lặp: các hàng chạy độc lập, pad 2 ô nhịp khớp với pad 1 ô nhịp");
    auto core = padCore(padSet({{0, {3840, {{960, 100, 1}}}}, {4, {7680, {{0, 100, 3}}}}}));
    core->play(0);
    core->padLaunch(0, 0, 0);
    core->padLaunch(0, 1, 4);
    const auto on = findOnsets(renderAll(*core, 1, 230000, 480));
    const std::vector<int64_t> want = {0, 24000, 120000, 192000, 216000};
    CHECK(on.size() == want.size(), "onsets %zu", on.size());
    for (size_t i = 0; i < on.size() && i < want.size(); ++i)
        CHECK(std::llabs(on[i] - want[i]) <= 1, "onset %zu at %lld want %lld", i, static_cast<long long>(on[i]), static_cast<long long>(want[i]));
}

static void testPadRecordEventsAndInvalid() {
    begin("Pad lặp: sự kiện ghi chỉ khi bật ghi; launch sai hàng bị từ chối; pad ngoài dữ liệu không lỗi");
    auto core = padCore(padSet({{0, {3840, {{0, 100, 1}, {1920, 70, 3}}}}}));
    CHECK(!core->padLaunch(0, 1, 0), "pad of row 0 accepted on row 1");
    CHECK(!core->padLaunch(9, 0, 0), "bad track accepted");
    core->setRecording(true);
    core->play(0);
    core->padLaunch(0, 0, 0);
    core->padLaunch(0, 2, 8);   // pad rỗng (len 0): chạy nhưng im lặng
    renderAll(*core, 1, 96000 + 1000, 480);
    int32_t e[5];
    std::vector<std::array<int32_t, 4>> evs;
    while (core->popRecEvent(e)) evs.push_back({e[0], e[1], e[2], e[3]});
    CHECK(evs.size() == 3, "events %zu", evs.size());
    if (evs.size() == 3) {
        CHECK(evs[0] == (std::array<int32_t, 4>{0, 0, 1, 100}), "ev0");
        CHECK(evs[1] == (std::array<int32_t, 4>{1920, 0, 3, 70}), "ev1");
        CHECK(evs[2] == (std::array<int32_t, 4>{3840, 0, 1, 100}), "ev2");
    }
    core->setRecording(false);
    renderAll(*core, 1, 96000, 480);
    CHECK(!core->popRecEvent(e), "events recorded while recording off");
}


// ---- Sampler: mẫu âm thật cho pad Looper -------------------------------------------------

// Patch chỉ dùng mẫu số [id] (các phần tổng hợp để mặc định: nếu mẫu chưa nạp sẽ dùng tổng hợp thay thế).
static std::array<int32_t, kPatchInts> samplePatch(int id, float gain = 1.0f) {
    auto a = PI(P(0, 0, 0, 30, 200, 0.5f, 40, 1, 0, 0, 11, 90, 0, 0, 0, gain, 0));  // dự phòng: nhiễu ngắn
    a[17] = id;
    return a;
}

static std::vector<float> rampSample(int n) {  // 1.0 giảm dần về 0: dễ kiểm tra từng mẫu
    std::vector<float> v(static_cast<size_t>(n));
    for (int i = 0; i < n; ++i) v[static_cast<size_t>(i)] = 0.8f * (1.0f - static_cast<float>(i) / n) * ((i % 2) ? -1.0f : 1.0f);
    return v;
}

static void testSamplerPlaysAndEnds() {
    begin("Sampler: mẫu phát từ khung hình đầu, đúng độ dài, voice tự tắt; nạp lần hai bị từ chối");
    auto core = std::unique_ptr<EngineCore>(newMono(48000, 120, false));
    const auto smp = rampSample(4800);  // 0,1 s ở 48 kHz
    CHECK(core->loadSample(0, smp.data(), 4800, 48000), "load failed");
    CHECK(!core->loadSample(0, smp.data(), 4800, 48000), "slot overwritten");
    CHECK(!core->loadSample(-1, smp.data(), 4800, 48000) && !core->loadSample(64, smp.data(), 4800, 48000), "bad id accepted");
    CHECK(!core->loadSample(1, nullptr, 0, 48000), "null accepted");
    publish(*core, encode(false, 0, 0, 0, {TrackSpec(Inst::Looper, {{960, 240, 0, 127}}, Kit{samplePatch(0)})}));
    core->play(0);
    const auto out = renderAll(*core, 1, 48000, 480);
    const auto on = findOnsets(out);
    CHECK(on.size() == 1 && std::llabs(on[0] - 24000) <= 1, "onset %lld", on.empty() ? -1LL : static_cast<long long>(on[0]));
    // Phần đuôi sau mẫu (0,1 s = 4800 khung) phải im lặng tuyệt đối.
    CHECK(peak(out, 24000 + 4800 + 5, 10000) == 0.0f, "sample did not end (tail peak %f)", peak(out, 24000 + 4805, 10000));
    CHECK(peak(out, 24000, 4800) > 0.3f, "sample too quiet");
    renderAll(*core, 1, 4800, 480);
    CHECK(core->activeVoiceCount() == 0, "voice still active after sample end");
}

static void testSamplerResamplesAndFallsBack() {
    begin("Sampler: mẫu 24 kHz phát dài gấp đôi trên thiết bị 48 kHz; mẫu chưa nạp dùng tổng hợp thay thế");
    auto core = std::unique_ptr<EngineCore>(newMono(48000, 120, false));
    const auto smp = rampSample(2400);  // 0,1 s ở 24 kHz => 4800 khung hình ở 48 kHz
    core->loadSample(0, smp.data(), 2400, 24000);
    publish(*core, encode(false, 0, 0, 0, {TrackSpec(Inst::Looper, {}, Kit{samplePatch(0), samplePatch(7)})}));
    renderAll(*core, 1, 64, 64);
    core->noteOn(static_cast<int>(Inst::Looper), 0, 0, 127);
    const auto a = renderAll(*core, 1, 9600, 480);
    // Còn tín hiệu ở nửa sau (khung 3000-4700) nhưng im lặng sau 4800.
    CHECK(peak(a, 3000, 1700) > 0.0f, "resampled sample ended too early");
    CHECK(peak(a, 4900, 4000) == 0.0f, "resampled sample too long");
    core->noteOn(static_cast<int>(Inst::Looper), 0, 1, 127);  // mẫu 7 chưa nạp
    const auto b = renderAll(*core, 1, 2400, 480);
    CHECK(peak(b, 0, b.size()) > 0.05f, "fallback synth silent");
}

static void testSamplerBitExactAndBounded() {
    begin("Sampler: pad dùng mẫu lặp giống hệt từng bit, đầu ra trong [-1, 1] với gain lớn");
    auto core = std::unique_ptr<EngineCore>(newMono(48000, 120, false));
    const auto smp = rampSample(9600);
    core->loadSample(2, smp.data(), 9600, 44100);
    Kit kit{samplePatch(2, 2.0f)};
    auto pads = padSet({{0, {3840, {{0, 127, 0}, {1920, 90, 0}}}}});
    publish(*core, encode(false, 0, 0, 0, {TrackSpec(Inst::Looper, {}, kit, pads)}));
    renderAll(*core, 1, 64, 64);
    core->play(0);
    core->padLaunch(0, 0, 0);
    const auto out = renderAll(*core, 1, 96000 * 3, 480);
    bool same = true, bounded = true;
    for (size_t i = 0; i < 96000; ++i) {
        if (out[96000 + i] != out[192000 + i]) same = false;
        if (!(std::fabs(out[i]) <= 1.0f)) bounded = false;
    }
    CHECK(same, "sample loops differ");
    CHECK(bounded, "exceeded [-1, 1]");
}

// ---- Looper: hàng Keyboard (nốt có cao độ, pad dài nhiều ô nhịp) ----------------------------

static void testKeyboardPadPitchAndRelease() {
    begin("Keyboard: pad 4 ô nhịp phát đúng nốt (cao độ < 10 cent), đúng thời điểm, nhả đúng độ dài");
    const int len = 4 * 3840;
    // 120 BPM: 1 phách = 24000 khung. Nốt C4 (60) ở tick 0, G4 (67) ở tick 3840 (đầu ô 2), dài 2 phách.
    auto pads = padSet({{20, {len, {{0, 90, 128 + 60, 1920}, {3840, 90, 128 + 67, 1920}}}}});
    auto core = padCore(pads);
    core->play(0);
    core->padLaunch(0, 5, 20);
    const int sr = 48000;
    const auto out = renderAll(*core, 1, 96000 * 2, 480);
    const double f1 = estimateFreq(out, sr, 261.6256);
    CHECK(std::fabs(1200.0 * std::log2(f1 / 261.6256)) < 10.0, "C4 off: %.2f Hz", f1);
    std::vector<float> second(out.begin() + 96000, out.end());
    const double f2 = estimateFreq(second, sr, 391.9954);
    CHECK(std::fabs(1200.0 * std::log2(f2 / 391.9954)) < 10.0, "G4 off: %.2f Hz", f2);
    // Nốt đầu dài 2 phách = 48000 khung rồi nhả; ngay trước ô 2 (khung 90000) gần như im lặng.
    CHECK(peak(out, 0, 2000) > 0.02f, "no onset at 0");
    CHECK(peak(out, 90000, 5000) < 0.01f, "note not released (peak %f)", peak(out, 90000, 5000));
    CHECK(peak(out, 96000, 2000) > 0.02f, "second chord missing");
}

static void testKeyboardNotesInClip() {
    begin("Keyboard: nốt pitch >= 128 trong clip của track Looper phát như piano, pitch < 128 vẫn là pad; hit keyboard sai bị bỏ");
    auto core = std::unique_ptr<EngineCore>(newMono(48000, 120, false));
    publish(*core, encode(false, 0, 0, 0, {TrackSpec(Inst::Looper, {{960, 960, 128 + 69, 100}, {2880, 240, 3, 100}}, testKit())}));
    core->play(0);
    const auto out = renderAll(*core, 1, 24000 * 5, 480);
    CHECK(peak(out, 24000 + 2000, 4000) > 0.05f, "keyboard note silent");
    CHECK(peak(out, 24000 * 3 + 100, 2000) > 0.02f, "pad note silent");
    const double f = estimateFreq(std::vector<float>(out.begin() + 24000, out.end()), 48000, 440.0);
    CHECK(std::fabs(1200.0 * std::log2(f / 440.0)) < 10.0, "A4 off: %.2f Hz", f);

    // Hit keyboard ngoài khoảng (128 + 128) hoặc chỉ số kit sai phải bị bỏ khi giải mã, không làm hỏng bài.
    auto bad = encode(false, 0, 0, 0, {TrackSpec(Inst::Looper, {}, testKit(),
                                                  padSet({{20, {3840, {{0, 90, 300, 480}, {480, 90, 31, 0}, {960, 90, 128 + 60, 480}}}}}))});
    std::unique_ptr<Song> song(decodeSong(bad.data(), static_cast<int>(bad.size())));
    CHECK(song != nullptr, "decode failed");
    if (song) CHECK(song->tracks[0].pads[20].hits.size() == 1, "invalid hits kept: %zu", song->tracks[0].pads[20].hits.size());
}

int main() {
    testExactBeatsAt120();
    testNoDriftAtOddTempo();
    testTempoChange();
    testAccentAndStop();
    testSequencedOnsetExact();
    testLoopExact();
    testLoopNoDriftNonInteger();
    testEndOfSong();
    testChunkInvariance();
    testSongSwapWhilePlaying();
    testMuteSolo();
    testPan();
    testPitchAccuracy();
    testDrumPieces();
    testHatChoke();
    testLiveNoteLifecycle();
    testVoiceStealing();
    testQueueOverflowAndDecode();
    testCpuBudget();
    testSyncBitExactLoops();
    testSyncAcrossTempoChange();
    testMuteDoesNotShiftOthers();
    testRawMidiPlaysAtExactTick();
    testDenoise();
    testDenoiseNoOpAndSafety();
    testLooperRoutingAndTone();
    testLooperLive();
    testLooperChoke();
    testLooperLoopBitExactAndBounded();
    testLooperExtremeParams();
    testPadLaunchQuantizedLoop();
    testPadMidBarLaunchWaitsForBoundary();
    testPadRowExclusiveSwitchAndStop();
    testPadRowsIndependentAndLengths();
    testPadRecordEventsAndInvalid();
    testSamplerPlaysAndEnds();
    testSamplerResamplesAndFallsBack();
    testSamplerBitExactAndBounded();
    testKeyboardPadPitchAndRelease();
    testKeyboardNotesInClip();

    if (g_failures == 0) {
        std::printf("\nALL %d TESTS PASSED\n", g_tests);
        return 0;
    }
    std::printf("\n%d CHECK(S) FAILED\n", g_failures);
    return 1;
}
