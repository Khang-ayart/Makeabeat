#pragma once

#include <atomic>
#include <cstdint>

#include "song.h"
#include "spsc_queue.h"
#include "voices.h"

// EngineCore: lập lịch theo tick, nhiều track, nhiều nhạc cụ, metronome.
// KHÔNG phụ thuộc Oboe/Android nên test được trên máy tính và dùng lại để xuất bản phối offline.
namespace band {

struct Command {
    enum class Type : uint8_t { Play, Stop, NoteOn, NoteOff, AllNotesOff, PadLaunch };
    Type type = Type::Stop;
    int a = 0, b = 0, c = 0, d = 0;
    // PadLaunch: b = track, a = hàng, c = pad (-1 = tắt) | Play: a = tick bắt đầu | NoteOn: a = nhạc cụ, b = track, c = nốt, d = velocity | NoteOff: b = track, c = nốt
};

// Giải mã bài nhạc từ mảng int do Kotlin gửi sang. Trả nullptr nếu dữ liệu hỏng.
// Định dạng: [version=3, loop, loopStart, loopEnd, length, trackCount, rồi mỗi track:
//   inst, kitCount, kitCount * kPatchInts,
//   padCount, mỗi pad: len, hitCount, hitCount * (start, vel, kitIdx),
//   noteCount, noteCount * (start, dur, pitch, vel)]
Song* decodeSong(const int32_t* data, int count);

class EngineCore {
public:
    EngineCore();
    ~EngineCore();
    EngineCore(const EngineCore&) = delete;
    EngineCore& operator=(const EngineCore&) = delete;

    // Gọi khi luồng âm thanh đang DỪNG.
    void prepare(int sampleRate, int channelCount);

    // ---- Luồng UI (đúng MỘT luồng gọi) ----
    void setBpm(float bpm) noexcept;
    void setMetronomeEnabled(bool enabled) noexcept;
    void setTrackMix(int track, float volume, float pan, bool mute, bool solo) noexcept;
    void publishSong(Song* song);  // nhận quyền sở hữu
    bool play(int fromTick) noexcept;
    bool stop() noexcept;
    bool noteOn(int inst, int track, int note, int velocity) noexcept;
    bool noteOff(int track, int note) noexcept;
    bool allNotesOff() noexcept;
    // Bật pad (pad >= 0) hoặc tắt hàng (pad = -1). Có hiệu lực ở đầu chu kỳ kế tiếp (quantize), mỗi hàng chỉ một pad.
    bool padLaunch(int track, int row, int pad) noexcept;
    // Nạp mẫu âm thật vào ô [id] (mono float). Chỉ ghi được một lần cho mỗi ô (mẫu không bao giờ bị thay khi đang
    // được phát). Có thể gọi từ luồng nạp riêng, không dùng hàng đợi lệnh. Trả false nếu ô đã có / tham số sai.
    bool loadSample(int id, const float* data, int count, int sampleRate);
    // Bật cờ ghi: engine báo mỗi cú đánh do nhịp pad phát ra để UI ghi thành nốt.
    void setRecording(bool on) noexcept { recording_.store(on, std::memory_order_relaxed); }
    // Lấy một sự kiện ghi: out = {tick, track, kitIdx, vel}. false nếu hết.
    bool popRecEvent(int32_t out[4]) noexcept;
    // Trạng thái pad của track để UI vẽ: bit 0-19 đang chạy, 20-39 chờ bật, 40-59 chờ tắt.
    int64_t padMask(int track) const noexcept {
        return (track >= 0 && track < kMaxTracks) ? padMask_[track].load(std::memory_order_relaxed) : 0;
    }

    // ---- Luồng âm thanh thời gian thực ----
    void render(float* out, int numFrames) noexcept;

    // ---- Đọc từ luồng bất kỳ ----
    double beatPosition() const noexcept { return beatPosPublished_.load(std::memory_order_relaxed); }
    bool isPlaying() const noexcept { return playingPublished_.load(std::memory_order_relaxed); }
    int activeVoiceCount() const noexcept { return activeVoicesPublished_.load(std::memory_order_relaxed); }

private:
    struct TrackMix {
        std::atomic<float> volume{0.8f};
        std::atomic<float> pan{0.0f};
        std::atomic<bool> mute{false};
        std::atomic<bool> solo{false};
    };

    void apply(const Command& cmd) noexcept;
    void adoptPendingSong() noexcept;
    void seekEvents(int64_t tick) noexcept;
    void releaseSequenced(bool includeDrums) noexcept;
    void startVoice(Inst inst, int track, int note, int velocity, int64_t endTick, bool seq, uint64_t seed) noexcept;
    Voice* allocateVoice() noexcept;
    void triggerClick(bool accent) noexcept;
    void advanceTransport(bool metronome) noexcept;
    void runPads(int track, int64_t tick) noexcept;
    void clearPads() noexcept;

    int sampleRate_ = 48000;
    int channels_ = 2;
    SineTable table_;
    SpscQueue<Command, 256> commands_;

    // Song: UI -> âm thanh qua pending_, âm thanh trả bản cũ về UI qua retired_ (để UI giải phóng).
    std::atomic<Song*> pending_{nullptr};
    SpscQueue<Song*, 16> retired_;
    Song* current_ = nullptr;  // chỉ luồng âm thanh (và hàm dựng/hủy) chạm vào

    std::atomic<float> bpm_{120.0f};
    std::atomic<bool> metronome_{true};
    TrackMix mix_[kMaxTracks];
    std::atomic<double> beatPosPublished_{0.0};
    std::atomic<bool> playingPublished_{false};
    std::atomic<int> activeVoicesPublished_{0};

    // ---- Trạng thái riêng của luồng âm thanh ----
    // Vị trí = baseBeat_ + framesSinceBase_ * beatsPerFrame_ (tính từ số nguyên nên không trôi nhịp).
    float appliedBpm_ = 120.0f;
    double beatsPerFrame_ = 0.0;
    bool playing_ = false;
    double baseBeat_ = 0.0;
    int64_t framesSinceBase_ = 0;
    int64_t lastBeat_ = -1;
    int64_t lastTick_ = -1;
    int nextIdx_[kMaxTracks] = {};

    // Looper: mỗi hàng có pad đang chạy và pad đang chờ (áp dụng ở đầu chu kỳ).
    static constexpr int kNoQueue = -3;
    struct PadRow {
        int active = -1;
        int queued = kNoQueue;  // -1 = chờ tắt, >= 0 = chờ bật pad đó
    };
    PadRow padRows_[kMaxTracks][kPadRows];
    std::atomic<int64_t> padMask_[kMaxTracks] = {};
    std::atomic<bool> recording_{false};
    struct RecEvent {
        int32_t tick = 0, track = 0, kitIdx = 0, vel = 0;
    };
    SpscQueue<RecEvent, 512> recEvents_;  // âm thanh -> UI

    bool audible_[kMaxTracks] = {};
    float targetL_[kMaxTracks] = {}, targetR_[kMaxTracks] = {};
    float curL_[kMaxTracks] = {}, curR_[kMaxTracks] = {};
    bool gainsInit_ = false;

    std::atomic<const Sample*> samples_[kMaxSamples] = {};

    Voice voices_[kMaxVoices];
    uint64_t voiceCounter_ = 0;

    struct Click {
        bool active = false;
        double phase = 0.25, phaseInc = 0.0;
        float env = 0.0f, amp = 0.0f, decay = 0.0f;
    } click_;
    float clickDecay_ = 0.0f;
    float releaseCoef_ = 0.0f;
    float chokeCoef_ = 0.0f;
};

}  // namespace band
