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
};
constexpr int kInstCount = 6;

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

struct Note {
    int32_t start;  // tick tuyệt đối trong bài
    int32_t dur;    // độ dài (tick)
    uint8_t pitch;
    uint8_t vel;
};

struct TrackData {
    Inst inst = Inst::Piano;
    std::vector<Note> notes;  // đã sắp theo start
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
