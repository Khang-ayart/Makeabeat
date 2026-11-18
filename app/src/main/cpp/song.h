#pragma once

#include <cstdint>
#include <vector>

namespace band {

constexpr int kPpq = 960;        // tick / phách
constexpr int kBeatsPerBar = 4;  // nhịp 4/4
constexpr int kMaxTracks = 8;
constexpr int kMaxVoices = 32;

enum class Inst : uint8_t {
    Piano = 0,
    AcousticGuitar = 1,
    ElectricGuitar = 2,
    Bass = 3,
    DrumKit = 4,
    DrumPad = 5,
    Looper = 6,  // lưới pad: mỗi pad phát một âm tổng hợp theo Patch của kit
    SynthLead = 7,  // synth lead/arp (nội bộ: do hàng Synth của Looper phát)
    SynthPad = 8,   // synth pad/atmos: hợp âm nền kéo dài
};
constexpr int kInstCount = 9;

inline bool isDrum(Inst i) { return i == Inst::DrumKit || i == Inst::DrumPad; }

// Với track trống, "pitch" của nốt là số thứ tự bộ phận trống.
enum DrumPiece : uint8_t {
    kKick = 0,
    kSnare,
    kHatClosed,
    kHatOpen,
    kClap,
    kTomLow,
    kTomHigh,
    kCrash,
    kRide,
    kDrumPieces
};

// Thông số một âm "one-shot" (kick, snare, hi-hat, percussion, 808...). Danh mục âm nằm ở Kotlin,
// engine chỉ nhận thông số nên thêm âm mới không cần sửa C++.
constexpr int kPatchInts = 21;  // số số nguyên mỗi patch trong mảng gửi sang (số thực x1000)
constexpr int kMaxKit = 32;
struct Patch {
    float toneAmp = 0, fStart = 0, fEnd = 0;       // âm có cao độ: tần số đầu/cuối (Hz)
    float pitchTauMs = 30, toneTauMs = 200;        // tốc độ trượt cao độ / thời gian tắt
    float noiseAmp = 0, noiseTauMs = 100;          // thành phần nhiễu
    float noiseHP = 0, noiseLP = 0;                // 0..1: lọc cao / lọc thấp (làm tối)
    int bursts = 0;                                // clap: số tiếng vỗ
    float burstGapMs = 11, tailTauMs = 90;
    float tone2Ratio = 0, tone2Amp = 0;            // thành phần thứ hai (chuông, cowbell...)
    int chokeGroup = 0;                            // >0: voice cùng nhóm bị ngắt khi pad mới phát
    float gain = 1.0f, drive = 0;                  // drive>0: méo mềm (808 rè)
    float send = 0;                                // 0..1: lượng gửi vào reverb chung (snare nên có để dính vào bộ)
    float root = 0;                                // > 0 (Hz): mẫu có cao độ gốc này; phát theo fStart->fEnd (808 mẫu thật, glide)
    float vary = 0;                                // 0..1: mỗi cú đánh lệch nhẹ cao độ/độ to theo hạt giống (round robin giả)
    int sample = -1;                               // >= 0: phát mẫu âm thật trong ngân hàng (nếu đã nạp); không thì dùng tổng hợp ở trên
};

// Looper: mỗi pad là một nhịp lặp. Pad i nằm ở hàng i / 4. Nhịp gồm các cú đánh (hit) trong một chu kỳ
// dài len tick; engine tự phát lặp khi pad được bật, vào đúng đầu chu kỳ để mọi pad luôn khớp nhau.
constexpr int kPadRows = 8;  // Kick, Snare, Hi-hat, Percussion, Bass, Keyboard, Synth lead, Synth pad
constexpr int kPadsPerRow = 4;
constexpr int kMaxPads = kPadRows * kPadsPerRow;
constexpr int kMaxPadLen = 8 * 3840;  // tối đa 8 ô nhịp
struct PadHit {
    int32_t start;    // tick trong chu kỳ, [0, len)
    int32_t dur;      // độ dài nốt (chỉ dùng cho nốt keyboard)
    uint8_t vel;
    uint16_t kitIdx;  // < 128: âm số kitIdx trong kit; 128..255: nốt piano (MIDI = kitIdx - 128); 256..383: synth lead; 384..511: synth pad
};
constexpr int kKeyNoteBase = 128;
constexpr int kLeadNoteBase = 256;
constexpr int kPadNoteBase = 384;
constexpr int kMaxNoteCode = 512;  // mã nốt tối đa (loại trừ)
struct PadPattern {
    int32_t len = 0;  // 0 = pad trống
    std::vector<PadHit> hits;  // đã sắp theo start
};

// Mẫu âm thật (mono, float [-1, 1]) nạp một lần khi khởi động, sau đó chỉ đọc.
constexpr int kMaxSamples = 256;
// Vùng phát của nhạc cụ có cao độ (piano, bass, guitar): mẫu gốc ở nốt root, dùng cho nốt [lo, hi] và velocity [velLo, velHi].
constexpr int kMaxZones = 96;
struct Zone {
    int sample = -1;
    int root = 60, lo = 0, hi = 127, velLo = 1, velHi = 127;
    float gain = 1.0f;
};
struct Sample {
    std::vector<int16_t> data;
    int sampleRate = 44100;
};

struct Note {
    int32_t start;  // tick tuyệt đối trong bài
    int32_t dur;    // độ dài (tick)
    uint16_t pitch;   // Looper: mã nốt như PadHit::kitIdx
    uint8_t vel;
};

struct TrackData {
    Inst inst = Inst::Piano;
    std::vector<Note> notes;  // đã sắp theo start
    std::vector<Patch> kit;   // chỉ track Looper: pitch của nốt (và kitIdx của hit) = chỉ số trong kit
    std::vector<PadPattern> pads;  // chỉ track Looper: nhịp của từng pad
};

// Ảnh chụp bất biến của bài nhạc. UI dựng rồi "xuất bản" sang luồng âm thanh, sau khi
// xuất bản KHÔNG ai được sửa nó nữa (luồng âm thanh đọc mà không cần khóa).
struct Song {
    TrackData tracks[kMaxTracks];
    bool loop = false;
    int32_t loopStart = 0;
    int32_t loopEnd = 0;
    int32_t length = 0;  // tick kết thúc; phát không lặp sẽ tự dừng ở đây (0 = chạy mãi)
};

}  // namespace band
