#pragma once

#include <atomic>
#include <cstdint>

#include "spsc_queue.h"

// EngineCore là phần "não" của engine: lập lịch nhịp, metronome, synth đa âm.
// Nó KHÔNG phụ thuộc Oboe hay Android, nên:
//   - chạy được test trên máy tính (native-tests/core_test.cpp),
//   - sau này dùng lại nguyên xi để xuất bản phối (render offline nhanh hơn thời gian thực).
namespace band {

constexpr int kPpq = 960;        // tick / phách; Piano Roll sau này lưu nốt theo tick
constexpr int kMaxVoices = 16;   // số nốt đồng thời tối đa của synth thử
constexpr int kBeatsPerBar = 4;  // nhịp 4/4 (tạm thời)

struct Command {
    enum class Type : uint8_t { SetPlaying, NoteOn, NoteOff, AllNotesOff };
    Type type = Type::SetPlaying;
    int a = 0;  // SetPlaying: 0/1 | NoteOn, NoteOff: số nốt MIDI
    int b = 0;  // NoteOn: velocity 1..127
};

// Bảng sin có nội suy tuyến tính, dựng một lần khi khởi tạo (không gọi sin() trong callback).
class SineTable {
public:
    static constexpr int kSize = 4096;
    SineTable();

    // phase tính theo chu kỳ, trong [0, 1].
    inline float at(double phase) const noexcept {
        const double x = phase * kSize;
        const int i = static_cast<int>(x);
        const float frac = static_cast<float>(x - i);
        return table_[i] + (table_[i + 1] - table_[i]) * frac;
    }

private:
    float table_[kSize + 2];
};

namespace detail {

// Một "dây đàn": 4 họa âm, mỗi họa âm tắt dần với tốc độ riêng (nghe giống piano điện đơn giản).
// Đây là synth tạm để kiểm chứng engine; sẽ thay bằng sampler SoundFont.
struct Voice {
    static constexpr int kHarmonics = 4;

    bool active = false;
    bool releasing = false;
    int note = -1;
    uint64_t order = 0;
    double phase = 0.0;
    double phaseInc = 0.0;
    float gain = 0.0f;
    float attack = 1.0f;
    float attackInc = 1.0f;
    float release = 1.0f;
    float releaseCoef = 0.0f;
    float env[kHarmonics] = {};
    float decay[kHarmonics] = {};

    void start(int midiNote, int velocity, int sampleRate, uint64_t startOrder);

    void beginRelease(float coef) noexcept {
        if (!active || releasing) return;
        releasing = true;
        release = 1.0f;
        releaseCoef = coef;
    }

    inline float render(const SineTable& table) noexcept {
        static constexpr float kWeights[kHarmonics] = {1.0f, 0.45f, 0.22f, 0.10f};
        float sum = 0.0f;
        for (int h = 0; h < kHarmonics; ++h) {
            double ph = phase * (h + 1);
            ph -= static_cast<double>(static_cast<int64_t>(ph));
            sum += kWeights[h] * env[h] * table.at(ph);
            env[h] *= decay[h];
        }
        phase += phaseInc;
        if (phase >= 1.0) phase -= 1.0;

        if (attack < 1.0f) {
            attack += attackInc;
            if (attack > 1.0f) attack = 1.0f;
        }
        float out = sum * gain * attack;
        if (releasing) {
            release *= releaseCoef;
            out *= release;
            if (release < 1e-4f) active = false;
        }
        if (env[0] < 1e-3f) active = false;
        return out;
    }
};

// Tiếng "tách" của metronome: sin ngắn tắt nhanh. Bắt đầu ở pha cosine nên mẫu đầu tiên
// có năng lượng ngay tại khung hình của phách (dễ kiểm tra độ chính xác).
struct Click {
    bool active = false;
    double phase = 0.25;
    double phaseInc = 0.0;
    float env = 0.0f;
    float amp = 0.0f;
    float decay = 0.0f;

    inline float render(const SineTable& table) noexcept {
        const float out = table.at(phase) * env * amp;
        phase += phaseInc;
        if (phase >= 1.0) phase -= 1.0;
        env *= decay;
        if (env < 1e-3f) active = false;
        return out;
    }
};

}  // namespace detail

class EngineCore {
public:
    EngineCore();

    // Gọi khi luồng âm thanh đang DỪNG (trước mỗi lần mở/mở lại stream).
    void prepare(int sampleRate, int channelCount);

    // ---- Luồng UI (đúng MỘT luồng gọi) ------------------------------------------------
    void setBpm(float bpm) noexcept;
    void setMetronomeEnabled(bool enabled) noexcept;
    bool setPlaying(bool playing) noexcept;
    bool noteOn(int note, int velocity) noexcept;
    bool noteOff(int note) noexcept;
    bool allNotesOff() noexcept;

    // ---- Luồng âm thanh thời gian thực: không cấp phát, không khóa, không gọi hệ thống ---
    // Ghi numFrames khung hình xen kẽ kênh (channelCount kênh) vào out.
    void render(float* out, int numFrames) noexcept;

    // ---- Đọc từ luồng bất kỳ ------------------------------------------------------------
    double beatPosition() const noexcept { return beatPosPublished_.load(std::memory_order_relaxed); }
    int activeVoiceCount() const noexcept { return activeVoicesPublished_.load(std::memory_order_relaxed); }

private:
    void apply(const Command& cmd) noexcept;
    detail::Voice* allocateVoice() noexcept;
    void triggerClick(bool accent) noexcept;

    int sampleRate_ = 48000;
    int channels_ = 2;
    SineTable table_;
    SpscQueue<Command, 256> commands_;

    // Dữ liệu chia sẻ giữa UI và luồng âm thanh (nguyên tử).
    std::atomic<float> bpm_{120.0f};
    std::atomic<bool> metronome_{true};
    std::atomic<double> beatPosPublished_{0.0};
    std::atomic<int> activeVoicesPublished_{0};

    // Trạng thái CHỈ luồng âm thanh được chạm vào.
    //
    // Vị trí phách = baseBeat_ + framesSinceBase_ * beatsPerFrame_.
    // Tính từ số khung hình nguyên (không cộng dồn số thực) nên KHÔNG trôi nhịp dù chạy hàng giờ.
    // Khi đổi tempo giữa chừng, "rebase" để vị trí phách liên tục, không nhảy.
    float appliedBpm_ = 120.0f;
    double beatsPerFrame_ = 0.0;
    bool playing_ = false;
    double baseBeat_ = 0.0;
    int64_t framesSinceBase_ = 0;
    int64_t lastBeat_ = -1;

    detail::Voice voices_[kMaxVoices];
    uint64_t voiceCounter_ = 0;
    detail::Click click_;
    float clickDecay_ = 0.0f;
    float releaseCoef_ = 0.0f;
};

}  // namespace band
