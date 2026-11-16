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
    enum class Type : uint8_t { Play, Stop, NoteOn, NoteOff, AllNotesOff };
    Type type = Type::Stop;
    int a = 0, b = 0, c = 0, d = 0;
    // Play: a = tick bắt đầu | NoteOn: a = nhạc cụ, b = track, c = nốt, d = velocity | NoteOff: b = track, c = nốt
};

// Giải mã bài nhạc từ mảng int do Kotlin gửi sang. Trả nullptr nếu dữ liệu hỏng.
// Định dạng: [version=1, loop, loopStart, loopEnd, length, trackCount,
//             rồi mỗi track: inst, noteCount, noteCount * (start, dur, pitch, vel)]
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

    bool audible_[kMaxTracks] = {};
    float targetL_[kMaxTracks] = {}, targetR_[kMaxTracks] = {};
    float curL_[kMaxTracks] = {}, curR_[kMaxTracks] = {};
    bool gainsInit_ = false;

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
