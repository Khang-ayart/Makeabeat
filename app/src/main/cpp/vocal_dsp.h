#pragma once

#include <vector>

namespace band {

// Phân tích và chỉnh giọng hát ngoại tuyến (không chạy trong callback âm thanh: có cấp phát bộ nhớ).

// Khoảng cách giữa hai khung cao độ (mẫu) ở tốc độ lấy mẫu sr: 512 mẫu @ 44,1 kHz ~ 11,6 ms.
int pitchHop(int sampleRate);

// Cao độ (Hz) của từng khung (khung k phủ mẫu [k*hop, k*hop + hop)); 0 = không có cao độ (im lặng, hơi thở, phụ âm).
// Thuật toán YIN: dò thô ở tốc độ đã giảm 4 lần rồi tinh chỉnh ở tốc độ gốc (sai số vài cent).
std::vector<float> trackPitch(const std::vector<float>& x, int sampleRate);

struct KeyGuess {
    int root = 0;        // 0 = C ... 11 = B
    bool minor = false;
    float score = 0.0f;  // độ tin cậy 0..1 (tương quan với mẫu tông)
    float voicedSeconds = 0.0f;
};

// Đoán tông từ các khung cao độ (Krumhansl-Schmuckler trên histogram 12 nốt, nốt dài/to được tính nhiều hơn).
KeyGuess estimateKey(const std::vector<float>& pitchHz, int sampleRate);

struct AutoTuneParams {
    int keyRoot = 0;          // chủ âm 0..11
    bool minor = false;       // thang thứ tự nhiên hay trưởng
    bool chromatic = false;   // true: chỉnh về nốt gần nhất bất kể tông
    float strength = 1.0f;    // 0..1: bao nhiêu phần độ lệch được sửa (1 = về đúng nốt)
    float speed = 0.5f;       // 0..1: tốc độ chỉnh (1 = tức thì, "robot"; 0 = chậm, giữ luyến láy)
};

// Sửa cao độ giọng mono về nốt trong tông (TD-PSOLA: giữ nguyên độ dài, chỉ đổi cao độ trên đoạn có cao độ).
// Phần không có cao độ (hơi thở, phụ âm) giữ nguyên. Với strength = 0 đầu ra bằng đầu vào.
void autoTune(std::vector<float>& x, int sampleRate, const AutoTuneParams& params);

// Co giãn thời gian không đổi cao độ (WSOLA): trả bản dài ratio x bản gốc (ratio > 1 chậm hơn, giới hạn 0,5..2).
// Dùng để khớp loop với BPM của bài (ratio = BPM gốc / BPM bài). Chạy ngoại tuyến.
std::vector<float> timeStretch(const std::vector<float>& x, int sampleRate, double ratio);

// Làm rõ giọng thu bằng micro điện thoại (nhỏ, xa, mất chữ): lọc trầm, bớt đục (~300 Hz), nâng độ rõ lời (~3 kHz) và
// độ thoáng (~7 kHz), rồi cân mức tự động (nâng đoạn nhỏ tới mức mục tiêu, tối đa ~+22 dB, không nâng nền ồn) và chặn đỉnh mềm.
// Chạy ngoại tuyến, thay đổi x tại chỗ. Bản im lặng / quá ngắn giữ nguyên.
void enhanceVocal(std::vector<float>& x, int sampleRate);

}  // namespace band
