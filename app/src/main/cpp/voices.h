#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

#include "song.h"

// Các "giọng" tổng hợp tạm thời cho 6 nhạc cụ. Mọi hàm chạy được trong callback thời gian thực
// (không cấp phát, không khóa). Về sau có thể thay bằng sampler SoundFont mà không đổi giao diện.
namespace band {

constexpr double kPi = 3.14159265358979323846;

// Bảng sin có nội suy tuyến tính, dựng một lần (không gọi sin() trong callback).
class SineTable {
public:
    static constexpr int kSize = 4096;
    SineTable() {
        for (int i = 0; i < kSize + 2; ++i) table_[i] = static_cast<float>(std::sin(2.0 * kPi * i / kSize));
    }
    inline float at(double phase) const noexcept {  // phase trong [0, 1]
        const double x = phase * kSize;
        const int i = static_cast<int>(x);
        const float frac = static_cast<float>(x - i);
        return table_[i] + (table_[i + 1] - table_[i]) * frac;
    }

private:
    float table_[kSize + 2];
};

// Nhiễu trắng xorshift32, trong [-1, 1).
struct Rng {
    uint32_t s = 2463534242u;
    inline float next() noexcept {
        s ^= s << 13;
        s ^= s >> 17;
        s ^= s << 5;
        return static_cast<float>(static_cast<int32_t>(s)) * (1.0f / 2147483648.0f);
    }
};

struct Voice {
    enum class Kind : uint8_t { Harmonic, Pluck, Drum };
    static constexpr int kHarmonics = 4;
    static constexpr int kMaxDelay = 2048;

    bool active = false;
    bool releasing = false;
    bool seq = false;  // do sequencer kích hoạt (true) hay do người dùng chạm phím (false)
    Kind kind = Kind::Harmonic;
    uint8_t track = 0;
    uint8_t piece = 0;
    uint8_t variant = 0;  // trống: 0 = Kit, 1 = Pad (808)
    int note = -1;
    uint64_t order = 0;
    int64_t endTick = -1;  // tick tự nhả nốt (chỉ với nốt do sequencer phát)

    float gain = 0.0f;
    float attack = 1.0f, attackInc = 1.0f;
    float release = 1.0f, releaseCoef = 0.0f;
    double phase = 0.0, phaseInc = 0.0;

    // Harmonic (piano, bass)
    float weights[kHarmonics] = {};
    float env[kHarmonics] = {};
    float decay[kHarmonics] = {};

    // Pluck (Karplus-Strong: guitar mộc / điện)
    float buf[kMaxDelay] = {};
    int len = 0, pos = 0;
    float rho = 0.0f, apA = 0.0f, apX1 = 0.0f, apY1 = 0.0f;
    float level = 0.0f, drive = 0.0f;

    // Drum
    Rng rng;
    float toneAmp = 0.0f, e1 = 0.0f, d1 = 0.0f;
    float fStart = 0.0f, fEnd = 0.0f, pitchEnv = 0.0f, pitchDecay = 0.0f;
    float noiseAmp = 0.0f, e2 = 0.0f, d2 = 0.0f, noiseHP = 0.0f, prevN = 0.0f;
    float tailDecay = 0.0f;
    int age = 0, burstGap = 0, burstsLeft = 0;
    double invSr = 0.0;
    // Mở rộng cho pad Looper: lọc thấp nhiễu, thành phần tone thứ hai, nhóm chặn (choke)
    float noiseLpK = 1.0f, lpState = 0.0f;  // lpK = 1: không lọc
    float t2Ratio = 0.0f, t2Amp = 0.0f;
    double phase2 = 0.0;
    int chokeGroup = 0;
    float send = 0.0f;  // phần tín hiệu gửi vào reverb chung
    // Mẫu âm thật (pad Looper): con trỏ chỉ đọc, đọc với nội suy tuyến tính theo tỉ lệ sample rate.
    const float* smpData = nullptr;
    int smpLen = 0;
    double smpPos = 0.0, smpInc = 1.0;
    float smpLp = 0.0f;  // trạng thái bộ lọc thấp cho mẫu (làm tối hi-hat/percussion theo thể loại)

    // seed: hạt giống nhiễu. Nốt do sequencer phát lấy từ (tick, track, nốt) nên mỗi vòng lặp giống hệt nhau từng bit.
    void start(Inst inst, int midiNote, int velocity, int sampleRate, uint64_t startOrder, uint64_t seed);
    // Pad Looper: phát một âm one-shot từ thông số Patch (tham số được kẹp về khoảng an toàn).
    void startPatch(const Patch& p, const Sample* smp, int padIndex, int velocity, int sampleRate,
                    uint64_t startOrder, uint64_t seed);

    void beginRelease(float coef) noexcept {
        if (!active || releasing) return;
        releasing = true;
        release = 1.0f;
        releaseCoef = coef;
    }

    inline float render(const SineTable& tbl) noexcept {
        float s;
        switch (kind) {
            case Kind::Harmonic: s = renderHarmonic(tbl); break;
            case Kind::Pluck: s = renderPluck(); break;
            default: s = renderDrum(tbl); break;
        }
        if (attack < 1.0f) {
            attack += attackInc;
            if (attack > 1.0f) attack = 1.0f;
            s *= attack;
        }
        s *= gain;
        if (releasing) {
            release *= releaseCoef;
            s *= release;
            if (release < 1e-3f) active = false;
        }
        return s;
    }

private:
    inline float renderHarmonic(const SineTable& tbl) noexcept {
        float sum = 0.0f;
        for (int h = 0; h < kHarmonics; ++h) {
            if (weights[h] == 0.0f) continue;
            double ph = phase * (h + 1);
            ph -= static_cast<double>(static_cast<int64_t>(ph));
            sum += weights[h] * env[h] * tbl.at(ph);
            env[h] *= decay[h];
        }
        phase += phaseInc;
        if (phase >= 1.0) phase -= 1.0;
        if (env[0] < 1e-3f) active = false;
        return sum;
    }

    inline float renderPluck() noexcept {
        const float y = buf[pos];
        const int p1 = (pos + 1 == len) ? 0 : pos + 1;
        const float avg = 0.5f * (y + buf[p1]) * rho;
        const float ap = apA * avg + apX1 - apA * apY1;  // all-pass: trễ phân số để đúng cao độ
        apX1 = avg;
        apY1 = ap;
        buf[pos] = ap;
        pos = p1;

        level = std::max(std::fabs(y), level * 0.9995f);
        if (level < 1e-4f) active = false;

        if (drive > 0.0f) {
            const float x = std::clamp(y * drive, -1.0f, 1.0f);
            return (1.5f * x - 0.5f * x * x * x) * 0.6f;  // méo mềm cho guitar điện
        }
        return y;
    }

    inline float renderDrum(const SineTable& tbl) noexcept {
        float out = 0.0f;
        if (toneAmp > 0.0f) {
            const float f = fEnd + (fStart - fEnd) * pitchEnv;
            pitchEnv *= pitchDecay;
            phase += f * invSr;
            if (phase >= 1.0) phase -= 1.0;
            float tone = tbl.at(phase);
            if (t2Amp > 0.0f) {
                phase2 += f * t2Ratio * invSr;
                phase2 -= static_cast<double>(static_cast<int64_t>(phase2));
                tone += t2Amp * tbl.at(phase2);
            }
            out += toneAmp * e1 * tone;
            e1 *= d1;
        }
        if (noiseAmp > 0.0f) {
            const float n = rng.next();
            const float hp = 0.5f * (n - prevN);
            prevN = n;
            float x = n + (hp - n) * noiseHP;
            if (noiseLpK < 1.0f) {
                lpState += (x - lpState) * noiseLpK;
                x = lpState;
            }
            out += noiseAmp * e2 * x;
            e2 *= d2;
            if (burstsLeft > 0 && ++age >= burstGap) {  // clap: nhiều tiếng vỗ nối nhau
                age = 0;
                e2 = 1.0f;
                if (--burstsLeft == 0) d2 = tailDecay;
            }
        }
        if (smpData) {
            const int i0 = static_cast<int>(smpPos);
            if (i0 + 1 >= smpLen) {
                smpData = nullptr;  // hết mẫu
            } else {
                const float fr = static_cast<float>(smpPos - i0);
                float sv = smpData[i0] + (smpData[i0 + 1] - smpData[i0]) * fr;
                if (noiseLpK < 1.0f) {  // noiseLP của Patch áp cho cả mẫu thật
                    smpLp += (sv - smpLp) * noiseLpK;
                    sv = smpLp;
                }
                out += sv;
                smpPos += smpInc;
            }
        }
        if (e1 < 1e-3f && e2 < 1e-3f && burstsLeft == 0 && !smpData) active = false;
        if (drive > 0.0f) {  // méo mềm (808 rè); đầu ra bị chặn trong [-0.8, 0.8]
            const float xd = std::clamp(out * (1.0f + drive), -1.0f, 1.0f);
            out = (1.5f * xd - 0.5f * xd * xd * xd) * 0.8f;
        }
        return out;
    }
};

inline void Voice::start(Inst inst, int midiNote, int velocity, int sampleRate, uint64_t startOrder,
                         uint64_t seed) {
    const double sr = sampleRate;
    const float vel = static_cast<float>(std::clamp(velocity, 1, 127)) / 127.0f;
    const double freq = 440.0 * std::pow(2.0, (midiNote - 69) / 12.0);
    auto dec = [&](double tau) { return static_cast<float>(std::exp(-1.0 / (tau * sr))); };

    active = true;
    releasing = false;
    note = midiNote;
    order = startOrder;
    endTick = -1;
    attack = 1.0f;
    release = 1.0f;
    phase = 0.0;
    phase2 = 0.0;
    drive = 0.0f;
    noiseLpK = 1.0f;
    lpState = 0.0f;
    t2Amp = t2Ratio = 0.0f;
    chokeGroup = 0;
    send = 0.0f;
    smpData = nullptr;

    if (inst == Inst::Piano || inst == Inst::Bass) {
        kind = Kind::Harmonic;
        const bool bass = inst == Inst::Bass;
        const float w[kHarmonics] = {1.0f, bass ? 0.8f : 0.45f, bass ? 0.45f : 0.22f, bass ? 0.25f : 0.10f};
        const double tauBase = bass ? 1.3 : 1.6;
        const double hdiv = bass ? 0.6 : 1.2;
        const double tau = tauBase * std::pow(2.0, -(midiNote - 60) / 24.0);
        phaseInc = freq / sr;
        for (int h = 0; h < kHarmonics; ++h) {
            const bool alias = freq * (h + 1) > 0.45 * sr;
            weights[h] = alias ? 0.0f : w[h];
            env[h] = 1.0f;
            decay[h] = dec(tau / (1.0 + hdiv * h));
        }
        gain = (bass ? 0.14f : 0.18f) * vel;
        attack = 0.0f;
        attackInc = static_cast<float>(1.0 / ((bass ? 0.005 : 0.003) * sr));
    } else if (inst == Inst::AcousticGuitar || inst == Inst::ElectricGuitar) {
        kind = Kind::Pluck;
        const bool electric = inst == Inst::ElectricGuitar;
        double P = sr / freq + 0.5;  // chu kỳ vòng lặp = len - 0.5 (trung bình) + frac (all-pass)
        P = std::clamp(P, 4.0, static_cast<double>(kMaxDelay - 1));
        len = static_cast<int>(P);
        double frac = P - len;
        if (frac < 0.1) {
            len -= 1;
            frac += 1.0;
        }
        apA = static_cast<float>((1.0 - frac) / (1.0 + frac));
        apX1 = apY1 = 0.0f;
        pos = 0;
        // Kích thích: nhiễu, làm trơn nhẹ (gảy mềm), bỏ thành phần một chiều.
        Rng r;
        r.s = static_cast<uint32_t>(seed * 2654435761u) | 1u;
        const float smooth = electric ? 0.25f : 0.55f;
        float prev = 0.0f, mean = 0.0f;
        for (int i = 0; i < len; ++i) {
            const float n = r.next();
            prev = smooth * prev + (1.0f - smooth) * n;
            buf[i] = prev;
            mean += prev;
        }
        mean /= static_cast<float>(len);
        for (int i = 0; i < len; ++i) buf[i] -= mean;
        const double T = electric ? 4.0 : 2.2;  // thời gian ngân (giảm 60 dB)
        rho = static_cast<float>(std::pow(0.001, 1.0 / (freq * T)));
        level = 1.0f;
        drive = electric ? 3.5f : 0.0f;
        gain = (electric ? 0.35f : 0.5f) * vel;
        attack = 0.0f;
        attackInc = static_cast<float>(1.0 / (0.0007 * sr));
    } else {
        kind = Kind::Drum;
        piece = static_cast<uint8_t>(std::clamp(midiNote, 0, static_cast<int>(kDrumPieces) - 1));
        variant = inst == Inst::DrumPad ? 1 : 0;
        const bool pad = variant == 1;
        invSr = 1.0 / sr;
        gain = vel;
        toneAmp = noiseAmp = 0.0f;
        e1 = e2 = 1.0f;
        d1 = d2 = 0.0f;
        pitchEnv = 1.0f;
        pitchDecay = 0.0f;
        noiseHP = 0.0f;
        burstsLeft = 0;
        burstGap = 0;
        age = 0;
        prevN = 0.0f;
        rng.s = static_cast<uint32_t>(seed * 2246822519u) | 1u;
        switch (piece) {
            case kKick:
                toneAmp = 0.9f; fStart = pad ? 140.0f : 160.0f; fEnd = pad ? 42.0f : 50.0f;
                pitchDecay = dec(pad ? 0.05 : 0.03); d1 = dec(pad ? 0.40 : 0.22);
                noiseAmp = 0.25f; d2 = dec(0.004); noiseHP = 1.0f;
                break;
            case kSnare:
                toneAmp = 0.45f; fStart = fEnd = pad ? 170.0f : 190.0f; pitchEnv = 0.0f;
                d1 = dec(pad ? 0.12 : 0.07);
                noiseAmp = 0.6f; d2 = dec(pad ? 0.10 : 0.14); noiseHP = 0.6f;
                break;
            case kHatClosed:
                noiseAmp = 0.35f; d2 = dec(pad ? 0.03 : 0.045); noiseHP = 1.0f;
                break;
            case kHatOpen:
                noiseAmp = 0.32f; d2 = dec(pad ? 0.20 : 0.32); noiseHP = 1.0f;
                break;
            case kClap:
                noiseAmp = 0.6f; d2 = dec(0.012); tailDecay = dec(0.09); noiseHP = 0.7f;
                burstsLeft = 3; burstGap = static_cast<int>(0.011 * sr);
                break;
            case kTomLow:
                toneAmp = 0.85f; fStart = pad ? 130.0f : 150.0f; fEnd = pad ? 75.0f : 95.0f;
                pitchDecay = dec(0.06); d1 = dec(pad ? 0.40 : 0.28);
                noiseAmp = 0.1f; d2 = dec(0.01);
                break;
            case kTomHigh:
                toneAmp = 0.85f; fStart = pad ? 200.0f : 220.0f; fEnd = pad ? 140.0f : 160.0f;
                pitchDecay = dec(0.06); d1 = dec(pad ? 0.35 : 0.24);
                noiseAmp = 0.1f; d2 = dec(0.01);
                break;
            case kRide:
                toneAmp = 0.12f; fStart = fEnd = 4200.0f; pitchEnv = 0.0f; d1 = dec(pad ? 0.15 : 0.22);
                noiseAmp = 0.2f; d2 = dec(pad ? 0.18 : 0.3); noiseHP = 1.0f;
                break;
            default:  // kCrash
                noiseAmp = 0.3f; d2 = dec(pad ? 0.35 : 0.5); noiseHP = 1.0f;
                break;
        }
        // Thành phần không dùng phải về 0, nếu không voice sẽ không bao giờ tự tắt.
        if (toneAmp <= 0.0f) e1 = 0.0f;
        if (noiseAmp <= 0.0f) e2 = 0.0f;
    }
}

inline void Voice::startPatch(const Patch& p, const Sample* smp, int padIndex, int velocity, int sampleRate,
                              uint64_t startOrder, uint64_t seed) {
    const double sr = sampleRate;
    const float vel = static_cast<float>(std::clamp(velocity, 1, 127)) / 127.0f;
    auto dec = [&](double ms) {
        const double tau = std::clamp(ms, 0.5, 4000.0) * 0.001;
        return static_cast<float>(std::exp(-1.0 / (tau * sr)));
    };
    auto amp = [](float a) { return std::clamp(a, 0.0f, 2.0f); };

    kind = Kind::Drum;
    active = true;
    releasing = false;
    note = padIndex;
    order = startOrder;
    endTick = -1;
    attack = 1.0f;
    release = 1.0f;
    phase = phase2 = 0.0;
    piece = 0;
    variant = 2;
    invSr = 1.0 / sr;
    gain = vel * std::clamp(p.gain, 0.0f, 2.0f);
    drive = std::clamp(p.drive, 0.0f, 12.0f);
    chokeGroup = std::clamp(p.chokeGroup, 0, 255);
    send = std::clamp(p.send, 0.0f, 1.0f);

    const float fMax = static_cast<float>(0.45 * sr);
    toneAmp = amp(p.toneAmp);
    fStart = std::clamp(p.fStart, 20.0f, fMax);
    fEnd = std::clamp(p.fEnd, 20.0f, fMax);
    pitchEnv = 1.0f;
    pitchDecay = dec(p.pitchTauMs);
    d1 = dec(p.toneTauMs);
    e1 = toneAmp > 0.0f ? 1.0f : 0.0f;
    t2Amp = toneAmp > 0.0f ? amp(p.tone2Amp) : 0.0f;
    t2Ratio = std::clamp(p.tone2Ratio, 0.0f, 12.0f);
    if (t2Ratio <= 0.0f) t2Amp = 0.0f;

    noiseAmp = amp(p.noiseAmp);
    e2 = noiseAmp > 0.0f ? 1.0f : 0.0f;
    d2 = dec(p.noiseTauMs);
    noiseHP = std::clamp(p.noiseHP, 0.0f, 1.0f);
    noiseLpK = 1.0f - 0.97f * std::clamp(p.noiseLP, 0.0f, 1.0f);
    prevN = 0.0f;
    lpState = 0.0f;
    burstsLeft = noiseAmp > 0.0f ? std::clamp(p.bursts, 0, 8) : 0;
    burstGap = std::max(1, static_cast<int>(std::clamp(p.burstGapMs, 1.0f, 60.0f) * 0.001 * sr));
    tailDecay = dec(p.tailTauMs);
    age = 0;
    rng.s = static_cast<uint32_t>(seed * 2246822519u) | 1u;
    smpData = nullptr;
    if (smp && smp->data.size() >= 2 && smp->sampleRate > 0) {  // có mẫu thật: thay cho phần tổng hợp
        smpData = smp->data.data();
        smpLen = static_cast<int>(smp->data.size());
        smpPos = 0.0;
        smpLp = 0.0f;
        smpInc = static_cast<double>(smp->sampleRate) / sr;
        toneAmp = noiseAmp = 0.0f;
        e1 = e2 = 0.0f;
        t2Amp = 0.0f;
        burstsLeft = 0;
    }
}

}  // namespace band
