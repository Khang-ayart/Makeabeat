#include "audio_engine.h"

#include <android/log.h>

#define LOG_TAG "BandAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace band {

bool AudioEngine::start() {
    wantRunning_.store(true);
    std::lock_guard<std::mutex> lock(mutex_);
    if (stream_) return true;
    return openAndStartLocked();
}

void AudioEngine::stop() {
    wantRunning_.store(false);
    std::shared_ptr<oboe::AudioStream> stream;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        stream = std::move(stream_);
    }
    // Đóng NGOÀI khóa: tránh bế tắc với luồng callback lỗi đang chờ mutex_.
    if (stream) {
        stream->stop();
        stream->close();
    }
}

bool AudioEngine::openAndStartLocked() {
    oboe::AudioStreamBuilder builder;
    auto self = shared_from_this();
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setFormatConversionAllowed(true)
        ->setChannelConversionAllowed(true)
        ->setDataCallback(self)
        ->setErrorCallback(self);

    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        LOGE("openStream failed: %s", oboe::convertToText(result));
        stream_.reset();
        return false;
    }

    // Đệm 2 burst: cân bằng giữa độ trễ thấp và an toàn trước xrun.
    stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2);
    core_.prepare(stream_->getSampleRate(), stream_->getChannelCount());
    sampleRate_.store(stream_->getSampleRate());

    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("requestStart failed: %s", oboe::convertToText(result));
        stream_->close();
        stream_.reset();
        return false;
    }

    LOGI("stream started: %d Hz, %d ch, burst %d frames, api %s", stream_->getSampleRate(),
         stream_->getChannelCount(), stream_->getFramesPerBurst(),
         oboe::convertToText(stream_->getAudioApi()));
    return true;
}

double AudioEngine::latencyMs() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!stream_ || stream_->getState() != oboe::StreamState::Started) return -1.0;
    auto latency = stream_->calculateLatencyMillis();
    return latency.error() == oboe::Result::OK ? latency.value() : -1.0;
}

int AudioEngine::xRunCount() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!stream_) return 0;
    auto xruns = stream_->getXRunCount();
    return xruns.error() == oboe::Result::OK ? xruns.value() : 0;
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* /*stream*/, void* audioData,
                                                   int32_t numFrames) {
    // Thời gian thực: không khóa, không cấp phát, không JNI, không log.
    core_.render(static_cast<float*>(audioData), numFrames);
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    if (error != oboe::Result::ErrorDisconnected) return;
    std::lock_guard<std::mutex> lock(mutex_);
    if (!wantRunning_.load()) return;
    LOGI("stream disconnected, reopening");
    stream_.reset();
    openAndStartLocked();
}

}  // namespace band
