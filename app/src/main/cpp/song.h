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
};
constexpr int kInstCount = 7;

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
constexpr int kPatchInts = 17;  // số số nguyên mỗi patch trong mảng gửi sang (số thực x1000)
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
};

// Looper: mỗi pad là một nhịp lặp. Pad i nằm ở hàng i / 4. Nhịp gồm các cú đánh (hit) trong một chu kỳ
// dài len tick; engine tự phát lặp khi pad được bật, vào đúng đầu chu kỳ để mọi pad luôn khớp nhau.
constexpr int kPadRows = 5;
constexpr int kPadsPerRow = 4;
constexpr int kMaxPads = kPadRows * kPadsPerRow;
constexpr int kMaxPadLen = 8 * 3840;  // tối đa 8 ô nhịp
struct PadHit {
    int32_t start;    // tick trong chu kỳ, [0, len)
    uint8_t vel;
    uint8_t kitIdx;   // phát âm nào trong kit của track
};
struct PadPattern {
    int32_t len = 0;  // 0 = pad trống
    std::vector<PadHit> hits;  // đã sắp theo start
};

struct Note {
    int32_t start;  // tick tuyệt đối trong bài
    int32_t dur;    // độ dài (tick)
    uint8_t pitch;
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
