#pragma once

#include <vector>

namespace band {

struct DenoiseParams {
    float strength = 0.6f;     // 0 (nhẹ) .. 1 (mạnh); mạnh quá có thể làm giọng "méo, như dưới nước"
    float highpassHz = 80.0f;  // cắt tiếng ù / gió / va chạm micro; 0 = tắt
};

// Lọc ồn ngoại tuyến cho tín hiệu mono (ví dụ một bản thu giọng hát), sửa trực tiếp trên x.
//
// Cách làm: lọc thông cao -> STFT (cửa sổ 1024, chồng 75%) -> trừ phổ nhiễu có quá-trừ, sàn độ lợi,
// làm mượt theo tần số và theo thời gian (giảm "tiếng chuông" nhiễu nhạc) -> ghép lại.
//
// Mẫu nhiễu: lấy từ noiseOnly nếu có (ví dụ 1 giây "im lặng" thu trước khi hát),
// nếu không thì tự lấy trung bình phổ của ~15% khung hình yên tĩnh nhất trong chính bản thu.
//
// Không chạy trong callback âm thanh (cấp phát bộ nhớ); gọi từ luồng nền sau khi thu xong.
void denoise(std::vector<float>& x, int sampleRate, const DenoiseParams& params,
             const float* noiseOnly = nullptr, int noiseCount = 0);

}  // namespace band
