#include "engine_core.h"

#include <algorithm>
#include <cmath>

namespace band {

namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr double kBeatEpsilon = 1e-9;  // chống sai số số thực khi so sánh biên phách
constexpr float kMasterGain = 0.7f;
constexpr float kMinBpm = 20.0f;
constexpr float kMaxBpm = 400.0f;

// Cắt mềm: tránh vỡ tiếng khi nhiều nốt cộng lại. Đầu ra luôn nằm trong [-1, 1].
inline float softClip(float x) noexcept {
    x = std::clamp(x, -1.0f, 1.0f);
    return 1.5f * x - 0.5f * x * x * x;
}

}  // namespace

SineTable::SineTable() {
    for (int i = 0; i < kSize + 2; ++i) {
        table_[i] = static_cast<float>(std::sin(2.0 * kPi * i / kSize));
    }
}

void detail::Voice::start(int midiNote, int velocity, int sampleRate, uint64_t startOrder) {
    const double sr = sampleRate;
    const double freq = 440.0 * std::pow(2.0, (midiNote - 69) / 12.0);

    active = true;
    releasing = false;
    note = midiNote;
    order = startOrder;
    phase = 0.0;
    phaseInc = freq / sr;
    gain = 0.18f * (static_cast<float>(std::clamp(velocity, 1, 127)) / 127.0f);
    attack = 0.0f;
    attackInc = static_cast<float>(1.0 / (0.003 * sr));  // 3 ms, tránh tiếng "bụp"
    release = 1.0f;
    releaseCoef = 0.0f;

    // Nốt trầm ngân dài hơn nốt cao; họa âm cao tắt nhanh hơn.
    const double tau = 1.6 * std::pow(2.0, -(midiNote - 60) / 24.0);
    for (int h = 0; h < kHarmonics; ++h) {
        env[h] = 1.0f;
        decay[h] = static_cast<float>(std::exp(-1.0 / ((tau / (1.0 + 1.2 * h)) * sr)));
    }
}

EngineCore::EngineCore() {
    prepare(sampleRate_, channels_);
}

void EngineCore::prepare(int sampleRate, int channelCount) {
    // Nếu đang phát mà đổi sample rate, giữ nguyên vị trí phách.
    if (playing_) {
        baseBeat_ += static_cast<double>(framesSinceBase_) * beatsPerFrame_;
        framesSinceBase_ = 0;
    }
    sampleRate_ = std::max(8000, sampleRate);
    channels_ = std::max(1, channelCount);
    appliedBpm_ = bpm_.load(std::memory_order_relaxed);
    beatsPerFrame_ = static_cast<double>(appliedBpm_) / (60.0 * sampleRate_);
    clickDecay_ = static_cast<float>(std::exp(-1.0 / (0.008 * sampleRate_)));  // ~8 ms
    releaseCoef_ = static_cast<float>(std::exp(-1.0 / (0.12 * sampleRate_)));  // ~120 ms

    for (auto& v : voices_) v = detail::Voice{};
    click_ = detail::Click{};
}

// ---------------------------------------------------------------------------------------
// Luồng UI
// ---------------------------------------------------------------------------------------

void EngineCore::setBpm(float bpm) noexcept {
    bpm_.store(std::clamp(bpm, kMinBpm, kMaxBpm), std::memory_order_relaxed);
}

void EngineCore::setMetronomeEnabled(bool enabled) noexcept {
    metronome_.store(enabled, std::memory_order_relaxed);
}

bool EngineCore::setPlaying(bool playing) noexcept {
    Command c;
    c.type = Command::Type::SetPlaying;
    c.a = playing ? 1 : 0;
    return commands_.push(c);
}

bool EngineCore::noteOn(int note, int velocity) noexcept {
    Command c;
    c.type = Command::Type::NoteOn;
    c.a = note;
    c.b = velocity;
    return commands_.push(c);
}

bool EngineCore::noteOff(int note) noexcept {
    Command c;
    c.type = Command::Type::NoteOff;
    c.a = note;
    return commands_.push(c);
}

bool EngineCore::allNotesOff() noexcept {
    Command c;
    c.type = Command::Type::AllNotesOff;
    return commands_.push(c);
}

// ---------------------------------------------------------------------------------------
// Luồng âm thanh
// ---------------------------------------------------------------------------------------

void EngineCore::apply(const Command& cmd) noexcept {
    switch (cmd.type) {
        case Command::Type::SetPlaying:
            if (cmd.a != 0) {
                playing_ = true;
                baseBeat_ = 0.0;
                framesSinceBase_ = 0;
                lastBeat_ = -1;
            } else {
                playing_ = false;
            }
            break;

        case Command::Type::NoteOn: {
            detail::Voice* v = allocateVoice();
            if (v) v->start(cmd.a, cmd.b, sampleRate_, ++voiceCounter_);
            break;
        }

        case Command::Type::NoteOff:
            for (auto& v : voices_) {
                if (v.active && !v.releasing && v.note == cmd.a) v.beginRelease(releaseCoef_);
            }
            break;

        case Command::Type::AllNotesOff:
            for (auto& v : voices_) v.beginRelease(releaseCoef_);
            break;
    }
}

// Còn voice trống thì dùng; hết thì "ăn cắp": ưu tiên voice đang nhả nhỏ nhất, sau đó voice cũ nhất.
detail::Voice* EngineCore::allocateVoice() noexcept {
    for (auto& v : voices_) {
        if (!v.active) return &v;
    }
    detail::Voice* quietest = nullptr;
    detail::Voice* oldest = &voices_[0];
    for (auto& v : voices_) {
        if (v.releasing && (!quietest || v.release < quietest->release)) quietest = &v;
        if (v.order < oldest->order) oldest = &v;
    }
    return quietest ? quietest : oldest;
}

void EngineCore::triggerClick(bool accent) noexcept {
    click_.active = true;
    click_.phase = 0.25;
    click_.phaseInc = (accent ? 1500.0 : 1000.0) / sampleRate_;
    click_.env = 1.0f;
    click_.amp = accent ? 0.5f : 0.35f;
    click_.decay = clickDecay_;
}

void EngineCore::render(float* out, int numFrames) noexcept {
    // 1) Nhận lệnh từ UI.
    Command cmd;
    while (commands_.pop(cmd)) apply(cmd);

    // 2) Đổi tempo: rebase để vị trí phách liên tục.
    const float bpm = bpm_.load(std::memory_order_relaxed);
    if (bpm != appliedBpm_) {
        if (playing_) {
            baseBeat_ += static_cast<double>(framesSinceBase_) * beatsPerFrame_;
            framesSinceBase_ = 0;
        }
        appliedBpm_ = bpm;
        beatsPerFrame_ = static_cast<double>(bpm) / (60.0 * sampleRate_);
    }
    const bool metronome = metronome_.load(std::memory_order_relaxed);

    // 3) Dựng âm thanh từng khung hình.
    for (int i = 0; i < numFrames; ++i) {
        if (playing_) {
            const double beatPos = baseBeat_ + static_cast<double>(framesSinceBase_) * beatsPerFrame_;
            const auto beat = static_cast<int64_t>(std::floor(beatPos + kBeatEpsilon));
            if (beat > lastBeat_) {
                lastBeat_ = beat;
                if (metronome) triggerClick(beat % kBeatsPerBar == 0);
            }
            ++framesSinceBase_;
        }

        float mix = 0.0f;
        if (click_.active) mix += click_.render(table_);
        for (auto& v : voices_) {
            if (v.active) mix += v.render(table_);
        }

        const float s = softClip(mix * kMasterGain);
        float* frame = out + static_cast<size_t>(i) * channels_;
        for (int c = 0; c < channels_; ++c) frame[c] = s;
    }

    // 4) Công bố trạng thái cho UI.
    const double pos =
        playing_ ? baseBeat_ + static_cast<double>(framesSinceBase_) * beatsPerFrame_ : 0.0;
    beatPosPublished_.store(pos, std::memory_order_relaxed);

    int active = 0;
    for (const auto& v : voices_) active += v.active ? 1 : 0;
    activeVoicesPublished_.store(active, std::memory_order_relaxed);
}

}  // namespace band
