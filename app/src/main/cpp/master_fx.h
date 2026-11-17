#pragma once

#include <algorithm>
#include <cmath>
#include <vector>

// Bus master: reverb phòng nhỏ (Freeverb rút gọn) + nén "keo" để các hàng nhạc dính vào nhau.
// prepare() cấp phát (gọi ngoài luồng âm thanh); process() không cấp phát, không khóa.
namespace band {

class MasterFx {
public:
    void prepare(int sampleRate) {
        sr_ = std::max(8000, sampleRate);
        const double k = sr_ / 44100.0;
        static const int combT[kCombs] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        static const int apT[kAps] = {556, 441, 341, 225};
        for (int ch = 0; ch < 2; ++ch) {
            for (int i = 0; i < kCombs; ++i) comb_[ch][i].init(static_cast<int>((combT[i] + ch * 23) * k));
            for (int i = 0; i < kAps; ++i) ap_[ch][i].init(static_cast<int>((apT[i] + ch * 23) * k));
        }
        auto coef = [&](double sec) { return static_cast<float>(std::exp(-1.0 / (sec * sr_))); };
        atk_ = coef(0.008);
        rel_ = coef(0.140);
        reset();
    }

    void reset() {
        for (auto& ch : comb_) for (auto& c : ch) c.clear();
        for (auto& ch : ap_) for (auto& a : ch) a.clear();
        env_ = 0.0f;
        damp_[0] = damp_[1] = 0.0f;
    }

    // wet: lượng reverb trộn thêm (0..1); thông số cố định đủ cho "căn phòng nhỏ".
    inline void process(float& l, float& r, float wet, float send = 0.0f) noexcept {
        const float in = ((l + r) * 0.5f + send * kSendGain) * 0.03f;
        float wl = 0.0f, wr = 0.0f;
        for (int i = 0; i < kCombs; ++i) {
            wl += comb_[0][i].tick(in);
            wr += comb_[1][i].tick(in);
        }
        for (int i = 0; i < kAps; ++i) {
            wl = ap_[0][i].tick(wl);
            wr = ap_[1][i].tick(wr);
        }
        // Cắt bớt trầm trong phần reverb để không làm đục bass/kick.
        damp_[0] += (wl - damp_[0]) * 0.02f;
        damp_[1] += (wr - damp_[1]) * 0.02f;
        l += (wl - damp_[0]) * wet;
        r += (wr - damp_[1]) * wet;

        // Nén liên kết hai kênh: ngưỡng ~ -14 dB, tỉ lệ 3:1, bù lại phần mất.
        const float peak = std::max(std::fabs(l), std::fabs(r));
        env_ = peak > env_ ? peak + (env_ - peak) * atk_ : peak + (env_ - peak) * rel_;
        float g = 1.0f;
        if (env_ > kThresh) {
            const float over = env_ / kThresh;
            g = std::pow(over, 1.0f / kRatio - 1.0f);
        }
        g *= kMakeup;
        l *= g;
        r *= g;
    }

private:
    static constexpr int kCombs = 8;
    static constexpr int kAps = 4;
    static constexpr float kSendGain = 2.5f;  // gửi riêng mạnh hơn đường chung
    static constexpr float kThresh = 0.2f;   // ~ -14 dBFS
    static constexpr float kRatio = 3.0f;
    static constexpr float kMakeup = 1.25f;  // bù ~ +2 dB

    struct Comb {
        std::vector<float> buf;
        int pos = 0;
        float store = 0.0f;
        void init(int n) { buf.assign(static_cast<size_t>(std::max(n, 8)), 0.0f); pos = 0; store = 0.0f; }
        void clear() { std::fill(buf.begin(), buf.end(), 0.0f); pos = 0; store = 0.0f; }
        inline float tick(float x) noexcept {
            const float y = buf[static_cast<size_t>(pos)];
            store = y * 0.75f + store * 0.25f;  // lọc thấp trong vòng hồi tiếp (tắt dần phần cao)
            float v = x + store * 0.84f;
            if (std::fabs(v) < 1e-20f) v = 0.0f;  // chống số phi chuẩn
            buf[static_cast<size_t>(pos)] = v;
            if (++pos >= static_cast<int>(buf.size())) pos = 0;
            return y;
        }
    };
    struct Allpass {
        std::vector<float> buf;
        int pos = 0;
        void init(int n) { buf.assign(static_cast<size_t>(std::max(n, 8)), 0.0f); pos = 0; }
        void clear() { std::fill(buf.begin(), buf.end(), 0.0f); pos = 0; }
        inline float tick(float x) noexcept {
            const float b = buf[static_cast<size_t>(pos)];
            float v = x + b * 0.5f;
            if (std::fabs(v) < 1e-20f) v = 0.0f;
            buf[static_cast<size_t>(pos)] = v;
            if (++pos >= static_cast<int>(buf.size())) pos = 0;
            return b - x;
        }
    };

    int sr_ = 48000;
    Comb comb_[2][kCombs];
    Allpass ap_[2][kAps];
    float damp_[2] = {0.0f, 0.0f};
    float env_ = 0.0f, atk_ = 0.0f, rel_ = 0.0f;
};

}  // namespace band
