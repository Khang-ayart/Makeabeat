#pragma once

#include <atomic>
#include <memory>
#include <mutex>

#include <oboe/Oboe.h>

#include "engine_core.h"

namespace band {

// Lớp mỏng nối EngineCore với luồng âm thanh Oboe (AAudio / OpenSL ES).
// - Mọi hàm điều khiển (start/stop/latency...) gọi từ luồng chính, không phải luồng thời gian thực.
// - Callback onAudioReady chạy trên luồng âm thanh ưu tiên cao và chỉ gọi EngineCore::render().
class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback,
                    public std::enable_shared_from_this<AudioEngine> {
public:
    AudioEngine() = default;
    ~AudioEngine() override { stop(); }

    bool start();  // mở stream và bắt đầu chạy (nếu đang chạy thì không làm gì)
    void stop();   // đóng stream, giải phóng thiết bị âm thanh

    EngineCore& core() { return core_; }

    double latencyMs();  // -1 nếu chưa đo được
    int xRunCount();
    int sampleRate() const { return sampleRate_.load(); }

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;

    // oboe::AudioStreamErrorCallback: tự mở lại khi rút/cắm tai nghe làm stream bị ngắt.
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool openAndStartLocked();

    std::mutex mutex_;  // bảo vệ stream_, KHÔNG bao giờ được dùng trong callback
    std::shared_ptr<oboe::AudioStream> stream_;
    EngineCore core_;
    std::atomic<bool> wantRunning_{false};
    std::atomic<int> sampleRate_{0};
};

}  // namespace band
