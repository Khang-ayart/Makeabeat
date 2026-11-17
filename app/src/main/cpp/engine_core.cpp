#include "engine_core.h"

#include <algorithm>
#include <cmath>
#include <memory>

namespace band {

namespace {

constexpr double kEps = 1e-9;  // chống sai số số thực ở biên phách / tick
constexpr float kMasterGain = 0.8f;
constexpr float kMinBpm = 20.0f;
constexpr float kMaxBpm = 400.0f;
constexpr float kGainSmooth = 0.002f;  // ~10 ms: kéo thanh trượt không bị "lạch tạch"

// Cắt mềm: nhiều nốt cộng lại không vỡ tiếng. Đầu ra luôn nằm trong [-1, 1].
inline float softClip(float x) noexcept {
    x = std::clamp(x, -1.0f, 1.0f);
    return 1.5f * x - 0.5f * x * x * x;
}

}  // namespace

Song* decodeSong(const int32_t* d, int n) {
    if (!d || n < 6 || d[0] != 3) return nullptr;
    auto song = std::make_unique<Song>();
    song->loop = d[1] != 0;
    song->loopStart = std::max(0, d[2]);
    song->loopEnd = std::max(0, d[3]);
    song->length = std::max(0, d[4]);
    const int tracks = d[5];
    if (tracks < 0 || tracks > kMaxTracks) return nullptr;

    auto f = [](int32_t v) { return static_cast<float>(v) * 0.001f; };
    int i = 6;
    for (int t = 0; t < tracks; ++t) {
        if (i + 3 > n) return nullptr;
        const int inst = d[i++];
        const int kitCount = d[i++];
        if (inst < 0 || inst >= kInstCount || kitCount < 0 || kitCount > kMaxKit ||
            static_cast<int64_t>(i) + static_cast<int64_t>(kPatchInts) * kitCount + 2 > n) {
            return nullptr;
        }
        TrackData& td = song->tracks[t];
        td.inst = static_cast<Inst>(inst);
        td.kit.reserve(static_cast<size_t>(kitCount));
        for (int k = 0; k < kitCount; ++k, i += kPatchInts) {
            Patch p;
            p.toneAmp = f(d[i]);
            p.fStart = f(d[i + 1]);
            p.fEnd = f(d[i + 2]);
            p.pitchTauMs = f(d[i + 3]);
            p.toneTauMs = f(d[i + 4]);
            p.noiseAmp = f(d[i + 5]);
            p.noiseTauMs = f(d[i + 6]);
            p.noiseHP = f(d[i + 7]);
            p.noiseLP = f(d[i + 8]);
            p.bursts = d[i + 9];
            p.burstGapMs = f(d[i + 10]);
            p.tailTauMs = f(d[i + 11]);
            p.tone2Ratio = f(d[i + 12]);
            p.tone2Amp = f(d[i + 13]);
            p.chokeGroup = d[i + 14];
            p.gain = f(d[i + 15]);
            p.drive = f(d[i + 16]);
            p.sample = d[i + 17];
            td.kit.push_back(p);
        }
        const int padCount = d[i++];
        if (padCount < 0 || padCount > kMaxPads) return nullptr;
        td.pads.resize(static_cast<size_t>(padCount));
        for (int k = 0; k < padCount; ++k) {
            if (i + 2 > n) return nullptr;
            const int len = d[i++];
            const int hits = d[i++];
            if (len < 0 || hits < 0 || static_cast<int64_t>(i) + 3LL * hits + 1 > n) return nullptr;
            PadPattern& pp = td.pads[static_cast<size_t>(k)];
            pp.len = std::clamp(len, 0, kMaxPadLen);
            pp.hits.reserve(static_cast<size_t>(hits));
            for (int h = 0; h < hits; ++h, i += 3) {
                if (d[i] < 0 || d[i] >= pp.len || d[i + 2] < 0 || d[i + 2] >= kitCount) continue;  // hit lạc: bỏ
                pp.hits.push_back({d[i], static_cast<uint8_t>(std::clamp(d[i + 1], 1, 127)), static_cast<uint8_t>(d[i + 2])});
            }
            std::stable_sort(pp.hits.begin(), pp.hits.end(),
                             [](const PadHit& a, const PadHit& b) { return a.start < b.start; });
        }
        const int count = d[i++];
        if (count < 0 || static_cast<int64_t>(i) + 4LL * count > n) return nullptr;
        td.notes.reserve(static_cast<size_t>(count));
        for (int k = 0; k < count; ++k, i += 4) {
            Note nt;
            nt.start = std::max(0, d[i]);
            nt.dur = std::max(1, d[i + 1]);
            nt.pitch = static_cast<uint8_t>(std::clamp(d[i + 2], 0, 127));
            nt.vel = static_cast<uint8_t>(std::clamp(d[i + 3], 1, 127));
            td.notes.push_back(nt);
        }
        std::stable_sort(td.notes.begin(), td.notes.end(),
                         [](const Note& a, const Note& b) { return a.start < b.start; });
    }
    return song.release();
}

EngineCore::EngineCore() {
    current_ = new Song();
    prepare(sampleRate_, channels_);
}

EngineCore::~EngineCore() {
    for (auto& s : samples_) delete s.load();
    delete current_;
    delete pending_.exchange(nullptr);
    Song* s = nullptr;
    while (retired_.pop(s)) delete s;
}

void EngineCore::prepare(int sampleRate, int channelCount) {
    if (playing_) {  // đổi sample rate giữa chừng: giữ nguyên vị trí phách
        baseBeat_ += static_cast<double>(framesSinceBase_) * beatsPerFrame_;
        framesSinceBase_ = 0;
    }
    sampleRate_ = std::max(8000, sampleRate);
    channels_ = std::max(1, channelCount);
    appliedBpm_ = bpm_.load(std::memory_order_relaxed);
    beatsPerFrame_ = static_cast<double>(appliedBpm_) / (60.0 * sampleRate_);
    clickDecay_ = static_cast<float>(std::exp(-1.0 / (0.008 * sampleRate_)));
    releaseCoef_ = static_cast<float>(std::exp(-1.0 / (0.12 * sampleRate_)));
    chokeCoef_ = static_cast<float>(std::exp(-1.0 / (0.012 * sampleRate_)));
    for (auto& v : voices_) v.active = false;
    clearPads();
    click_ = Click{};
    gainsInit_ = false;
}

// ---------------------------------------------------------------------------------------
// Luồng UI
// ---------------------------------------------------------------------------------------

void EngineCore::setBpm(float bpm) noexcept { bpm_.store(std::clamp(bpm, kMinBpm, kMaxBpm), std::memory_order_relaxed); }

void EngineCore::setMetronomeEnabled(bool enabled) noexcept { metronome_.store(enabled, std::memory_order_relaxed); }

void EngineCore::setTrackMix(int track, float volume, float pan, bool mute, bool solo) noexcept {
    if (track < 0 || track >= kMaxTracks) return;
    TrackMix& m = mix_[track];
    m.volume.store(volume, std::memory_order_relaxed);
    m.pan.store(pan, std::memory_order_relaxed);
    m.mute.store(mute, std::memory_order_relaxed);
    m.solo.store(solo, std::memory_order_relaxed);
}

bool EngineCore::loadSample(int id, const float* data, int count, int sampleRate) {
    if (id < 0 || id >= kMaxSamples || !data || count < 2 || count > 48000 * 20 || sampleRate < 8000) return false;
    auto* s = new Sample();
    s->data.assign(data, data + count);
    for (float& x : s->data) x = std::clamp(x, -1.0f, 1.0f);
    s->sampleRate = sampleRate;
    const Sample* expected = nullptr;
    if (!samples_[id].compare_exchange_strong(expected, s, std::memory_order_acq_rel)) {
        delete s;
        return false;
    }
    return true;
}

void EngineCore::publishSong(Song* song) {
    Song* r = nullptr;
    while (retired_.pop(r)) delete r;  // giải phóng bản cũ mà luồng âm thanh đã trả về
    Song* unused = pending_.exchange(song, std::memory_order_acq_rel);
    delete unused;  // bản chưa kịp tới luồng âm thanh thì bỏ luôn
}

bool EngineCore::play(int fromTick) noexcept {
    Command c;
    c.type = Command::Type::Play;
    c.a = std::max(0, fromTick);
    return commands_.push(c);
}

bool EngineCore::stop() noexcept {
    Command c;
    c.type = Command::Type::Stop;
    return commands_.push(c);
}

bool EngineCore::noteOn(int inst, int track, int note, int velocity) noexcept {
    Command c;
    c.type = Command::Type::NoteOn;
    c.a = inst;
    c.b = track;
    c.c = note;
    c.d = velocity;
    return commands_.push(c);
}

bool EngineCore::noteOff(int track, int note) noexcept {
    Command c;
    c.type = Command::Type::NoteOff;
    c.b = track;
    c.c = note;
    return commands_.push(c);
}

bool EngineCore::padLaunch(int track, int row, int pad) noexcept {
    if (track < 0 || track >= kMaxTracks || row < 0 || row >= kPadRows) return false;
    if (pad >= 0 && pad / kPadsPerRow != row) return false;  // pad phải thuộc đúng hàng
    Command c;
    c.type = Command::Type::PadLaunch;
    c.a = row;
    c.b = track;
    c.c = pad < 0 ? -1 : pad;
    return commands_.push(c);
}

bool EngineCore::popRecEvent(int32_t out[4]) noexcept {
    RecEvent e;
    if (!recEvents_.pop(e)) return false;
    out[0] = e.tick;
    out[1] = e.track;
    out[2] = e.kitIdx;
    out[3] = e.vel;
    return true;
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
        case Command::Type::Play:
            releaseSequenced(true);
            playing_ = true;
            baseBeat_ = static_cast<double>(cmd.a) / kPpq;
            framesSinceBase_ = 0;
            lastBeat_ = static_cast<int64_t>(std::floor(baseBeat_ + kEps)) - 1;
            lastTick_ = static_cast<int64_t>(cmd.a) - 1;
            seekEvents(cmd.a);
            break;

        case Command::Type::Stop:
            playing_ = false;
            releaseSequenced(true);
            clearPads();
            break;

        case Command::Type::PadLaunch:
            if (cmd.b >= 0 && cmd.b < kMaxTracks && cmd.a >= 0 && cmd.a < kPadRows) {
                PadRow& r = padRows_[cmd.b][cmd.a];
                if (cmd.c == r.active && r.queued == kNoQueue) {
                    break;  // đã chạy đúng pad đó, không có gì đổi
                }
                r.queued = cmd.c;  // >= 0 bật, -1 tắt (ở đầu chu kỳ kế tiếp)
            }
            break;

        case Command::Type::NoteOn:
            if (cmd.a >= 0 && cmd.a < kInstCount) {
                startVoice(static_cast<Inst>(cmd.a), cmd.b, cmd.c, cmd.d, -1, false, voiceCounter_ + 1);
            }
            break;

        case Command::Type::NoteOff:
            for (auto& v : voices_) {
                if (v.active && !v.releasing && !v.seq && v.kind != Voice::Kind::Drum && v.track == cmd.b &&
                    v.note == cmd.c) {
                    v.beginRelease(releaseCoef_);
                }
            }
            break;

        case Command::Type::AllNotesOff:
            for (auto& v : voices_) v.beginRelease(v.kind == Voice::Kind::Drum ? chokeCoef_ : releaseCoef_);
            break;
    }
}

void EngineCore::clearPads() noexcept {
    for (auto& t : padRows_)
        for (auto& r : t) r = PadRow{};
}

// Áp dụng pad đang chờ ở đầu chu kỳ rồi phát các cú đánh của pad đang chạy tại tick này.
void EngineCore::runPads(int track, int64_t tick) noexcept {
    const TrackData& td = current_->tracks[track];
    if (td.inst != Inst::Looper || td.pads.empty()) return;
    const int padN = static_cast<int>(td.pads.size());
    for (int row = 0; row < kPadRows; ++row) {
        PadRow& r = padRows_[track][row];
        if (r.queued != kNoQueue) {
            const int ref = r.queued >= 0 ? r.queued : r.active;  // độ dài chu kỳ quyết định thời điểm đổi
            const int64_t len = (ref >= 0 && ref < padN) ? td.pads[static_cast<size_t>(ref)].len : 0;
            if (len <= 0 || tick % len == 0) {
                r.active = r.queued;
                r.queued = kNoQueue;
            }
        }
        if (r.active < 0 || r.active >= padN) continue;
        const PadPattern& pp = td.pads[static_cast<size_t>(r.active)];
        if (pp.len <= 0 || pp.hits.empty()) continue;
        const int32_t phase = static_cast<int32_t>(tick % pp.len);
        auto it = std::lower_bound(pp.hits.begin(), pp.hits.end(), phase,
                                   [](const PadHit& h, int32_t x) { return h.start < x; });
        for (; it != pp.hits.end() && it->start == phase; ++it) {
            if (recording_.load(std::memory_order_relaxed)) {
                recEvents_.push(RecEvent{static_cast<int32_t>(tick), track, it->kitIdx, it->vel});
            }
            if (audible_[track]) {
                // Hạt giống theo (vị trí trong chu kỳ, pad, track, âm): mỗi vòng lặp giống hệt từng bit.
                const uint64_t seed = static_cast<uint64_t>(phase) * 2654435761ULL + static_cast<uint64_t>(r.active) * 7919ULL +
                                      static_cast<uint64_t>(track) * 40503ULL + static_cast<uint64_t>(it->kitIdx) * 9973ULL + 1ULL;
                startVoice(Inst::Looper, track, it->kitIdx, it->vel, -1, true, seed);
            }
        }
    }
}

void EngineCore::adoptPendingSong() noexcept {
    Song* p = pending_.exchange(nullptr, std::memory_order_acq_rel);
    if (!p) return;
    Song* old = current_;
    current_ = p;
    retired_.push(old);  // đầy thì để rò rỉ còn hơn giải phóng trong luồng thời gian thực
    if (playing_) seekEvents(lastTick_ + 1);
}

void EngineCore::seekEvents(int64_t tick) noexcept {
    for (int t = 0; t < kMaxTracks; ++t) {
        const auto& v = current_->tracks[t].notes;
        const auto it = std::lower_bound(v.begin(), v.end(), tick,
                                         [](const Note& n, int64_t x) { return n.start < x; });
        nextIdx_[t] = static_cast<int>(it - v.begin());
    }
}

void EngineCore::releaseSequenced(bool includeDrums) noexcept {
    for (auto& v : voices_) {
        if (!v.active || !v.seq) continue;
        const bool drum = v.kind == Voice::Kind::Drum;
        if (drum && !includeDrums) continue;
        v.beginRelease(drum ? chokeCoef_ : releaseCoef_);
    }
}

// Còn voice trống thì dùng; hết thì "ăn cắp": ưu tiên voice đang nhả nhỏ nhất, sau đó voice cũ nhất.
Voice* EngineCore::allocateVoice() noexcept {
    for (auto& v : voices_) {
        if (!v.active) return &v;
    }
    Voice* quietest = nullptr;
    Voice* oldest = &voices_[0];
    for (auto& v : voices_) {
        if (v.releasing && (!quietest || v.release < quietest->release)) quietest = &v;
        if (v.order < oldest->order) oldest = &v;
    }
    return quietest ? quietest : oldest;
}

void EngineCore::startVoice(Inst inst, int track, int note, int velocity, int64_t endTick, bool seq,
                            uint64_t seed) noexcept {
    track = std::clamp(track, 0, kMaxTracks - 1);
    if (inst == Inst::Looper) {  // pad: âm lấy từ kit của track trong bài nhạc hiện tại
        const auto& kit = current_->tracks[track].kit;
        if (note < 0 || note >= static_cast<int>(kit.size())) return;  // pad chưa có âm: im lặng, không lỗi
        const Patch& p = kit[static_cast<size_t>(note)];
        if (p.chokeGroup > 0) {
            for (auto& v : voices_) {
                if (v.active && !v.releasing && v.kind == Voice::Kind::Drum && v.variant == 2 &&
                    v.track == track && v.chokeGroup == p.chokeGroup) {
                    v.beginRelease(chokeCoef_);
                }
            }
        }
        Voice* v = allocateVoice();
        if (!v) return;
        const Sample* smp = (p.sample >= 0 && p.sample < kMaxSamples) ? samples_[p.sample].load(std::memory_order_acquire) : nullptr;
        v->startPatch(p, smp, note, velocity, sampleRate_, ++voiceCounter_, seed);
        v->track = static_cast<uint8_t>(track);
        v->seq = seq;
        v->endTick = endTick;
        return;
    }
    if (isDrum(inst) && note == kHatClosed) {  // hi-hat đóng chặn hi-hat mở
        for (auto& v : voices_) {
            if (v.active && !v.releasing && v.kind == Voice::Kind::Drum && v.track == track && v.piece == kHatOpen) {
                v.beginRelease(chokeCoef_);
            }
        }
    }
    Voice* v = allocateVoice();
    if (!v) return;
    v->start(inst, note, velocity, sampleRate_, ++voiceCounter_, seed);
    v->track = static_cast<uint8_t>(track);
    v->seq = seq;
    v->endTick = endTick;
}

void EngineCore::triggerClick(bool accent) noexcept {
    click_.active = true;
    click_.phase = 0.25;  // bắt đầu ở pha cosine: mẫu đầu tiên có năng lượng ngay tại khung hình của phách
    click_.phaseInc = (accent ? 1500.0 : 1000.0) / sampleRate_;
    click_.env = 1.0f;
    click_.amp = accent ? 0.5f : 0.35f;
    click_.decay = clickDecay_;
}

void EngineCore::advanceTransport(bool metronome) noexcept {
    const Song& s = *current_;
    double beatPos = baseBeat_ + static_cast<double>(framesSinceBase_) * beatsPerFrame_;
    double tickPos = beatPos * kPpq;
    auto tickOf = [](double tp) { return static_cast<int64_t>(std::floor(tp + kEps)); };
    int64_t tickNow = tickOf(tickPos);

    if (s.loop && s.loopEnd > s.loopStart && tickNow >= s.loopEnd) {
        // Quay vòng: giữ phần dư (kể cả phần lẻ của khung hình) nên vòng lặp không trôi nhịp.
        const double len = static_cast<double>(s.loopEnd - s.loopStart);
        const double over = std::fmod(std::max(0.0, tickPos - s.loopEnd), len);
        baseBeat_ = (s.loopStart + over) / kPpq;
        framesSinceBase_ = 0;
        releaseSequenced(false);  // trống là nốt ngắn, để tự tàn; nốt dài bị cắt ở biên vòng
        seekEvents(s.loopStart);
        beatPos = baseBeat_;
        tickPos = beatPos * kPpq;
        tickNow = tickOf(tickPos);
        lastTick_ = static_cast<int64_t>(s.loopStart) - 1;
        lastBeat_ = static_cast<int64_t>(std::floor(beatPos + kEps)) - 1;
    } else if (!s.loop && s.length > 0 && tickNow >= s.length) {
        playing_ = false;  // hết bài
        releaseSequenced(true);
        return;
    }

    if (tickNow != lastTick_) {
        const int64_t prevTick = lastTick_;
        lastTick_ = tickNow;
        // Nhịp pad: đi qua từng tick (thường chỉ một) để không bỏ sót cú đánh khi tempo rất cao.
        const int64_t firstTick = std::max(prevTick + 1, tickNow - 64);
        for (int t = 0; t < kMaxTracks; ++t) {
            if (s.tracks[t].inst != Inst::Looper) continue;
            for (int64_t k = firstTick; k <= tickNow; ++k) runPads(t, k);
        }
        for (int t = 0; t < kMaxTracks; ++t) {
            const auto& notes = s.tracks[t].notes;
            int& idx = nextIdx_[t];
            const int count = static_cast<int>(notes.size());
            while (idx < count && notes[idx].start <= tickNow) {
                const Note& n = notes[idx++];
                if (audible_[t]) {
                    // Hạt giống theo (tick, track, nốt): mỗi vòng lặp nghe giống hệt nhau, render offline tái lập được.
                    const uint64_t seed = static_cast<uint64_t>(n.start) * 2654435761ULL +
                                          static_cast<uint64_t>(t) * 40503ULL + static_cast<uint64_t>(n.pitch) * 9973ULL + 1ULL;
                    startVoice(s.tracks[t].inst, t, n.pitch, n.vel, static_cast<int64_t>(n.start) + n.dur, true, seed);
                }
            }
        }
        for (auto& v : voices_) {  // nhả những nốt đã hết độ dài
            if (v.active && v.seq && !v.releasing && v.kind != Voice::Kind::Drum && v.endTick >= 0 &&
                tickNow >= v.endTick) {
                v.beginRelease(releaseCoef_);
            }
        }
    }

    const auto beat = static_cast<int64_t>(std::floor(beatPos + kEps));
    if (beat > lastBeat_) {
        lastBeat_ = beat;
        if (metronome) triggerClick(beat % kBeatsPerBar == 0);
    }
    ++framesSinceBase_;
}

void EngineCore::render(float* out, int numFrames) noexcept {
    Command cmd;
    while (commands_.pop(cmd)) apply(cmd);
    adoptPendingSong();

    const float bpm = bpm_.load(std::memory_order_relaxed);
    if (bpm != appliedBpm_) {  // đổi tempo: rebase để vị trí phách liên tục
        if (playing_) {
            baseBeat_ += static_cast<double>(framesSinceBase_) * beatsPerFrame_;
            framesSinceBase_ = 0;
        }
        appliedBpm_ = bpm;
        beatsPerFrame_ = static_cast<double>(bpm) / (60.0 * sampleRate_);
    }
    const bool metronome = metronome_.load(std::memory_order_relaxed);

    bool anySolo = false;
    for (const auto& m : mix_) anySolo = anySolo || m.solo.load(std::memory_order_relaxed);
    for (int t = 0; t < kMaxTracks; ++t) {
        const TrackMix& m = mix_[t];
        audible_[t] = anySolo ? m.solo.load(std::memory_order_relaxed) : !m.mute.load(std::memory_order_relaxed);
        const float vol = audible_[t] ? std::clamp(m.volume.load(std::memory_order_relaxed), 0.0f, 1.5f) : 0.0f;
        const float pan = std::clamp(m.pan.load(std::memory_order_relaxed), -1.0f, 1.0f);
        const float ang = (pan + 1.0f) * static_cast<float>(kPi / 4.0);  // pan đẳng công suất
        targetL_[t] = vol * std::cos(ang);
        targetR_[t] = vol * std::sin(ang);
    }
    if (!gainsInit_) {
        for (int t = 0; t < kMaxTracks; ++t) {
            curL_[t] = targetL_[t];
            curR_[t] = targetR_[t];
        }
        gainsInit_ = true;
    }

    for (int i = 0; i < numFrames; ++i) {
        if (playing_) advanceTransport(metronome);

        float bus[kMaxTracks] = {};
        for (auto& v : voices_) {
            if (v.active) bus[v.track] += v.render(table_);
        }

        float left = 0.0f, right = 0.0f;
        for (int t = 0; t < kMaxTracks; ++t) {
            curL_[t] += (targetL_[t] - curL_[t]) * kGainSmooth;
            curR_[t] += (targetR_[t] - curR_[t]) * kGainSmooth;
            left += bus[t] * curL_[t];
            right += bus[t] * curR_[t];
        }
        if (click_.active) {
            const float c = table_.at(click_.phase) * click_.env * click_.amp;
            click_.phase += click_.phaseInc;
            if (click_.phase >= 1.0) click_.phase -= 1.0;
            click_.env *= click_.decay;
            if (click_.env < 1e-3f) click_.active = false;
            left += c;
            right += c;
        }
        left = softClip(left * kMasterGain);
        right = softClip(right * kMasterGain);

        float* frame = out + static_cast<size_t>(i) * channels_;
        if (channels_ == 1) {
            frame[0] = 0.5f * (left + right);
        } else {
            frame[0] = left;
            frame[1] = right;
            for (int c = 2; c < channels_; ++c) frame[c] = 0.0f;
        }
    }

    for (int t = 0; t < kMaxTracks; ++t) {
        int64_t m = 0;
        for (int row = 0; row < kPadRows; ++row) {
            const PadRow& r = padRows_[t][row];
            if (r.active >= 0 && r.active < 20) m |= int64_t{1} << r.active;
            if (r.queued >= 0 && r.queued < 20) m |= int64_t{1} << (20 + r.queued);
            if (r.queued == -1 && r.active >= 0 && r.active < 20) m |= int64_t{1} << (40 + r.active);
        }
        padMask_[t].store(m, std::memory_order_relaxed);
    }
    const double pos = playing_ ? baseBeat_ + static_cast<double>(framesSinceBase_) * beatsPerFrame_ : 0.0;
    beatPosPublished_.store(pos, std::memory_order_relaxed);
    playingPublished_.store(playing_, std::memory_order_relaxed);
    int active = 0;
    for (const auto& v : voices_) active += v.active ? 1 : 0;
    activeVoicesPublished_.store(active, std::memory_order_relaxed);
}

}  // namespace band
