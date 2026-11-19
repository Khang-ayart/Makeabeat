#include <jni.h>

#include <memory>

#include <vector>

#include "audio_engine.h"
#include "noise_reducer.h"
#include "vocal_dsp.h"

namespace {
// Chỉ được chạm vào từ luồng chính (UI) của ứng dụng.
std::shared_ptr<band::AudioEngine> gEngine;
}  // namespace

#define JNI_FN(name) Java_com_bandstudio_app_audio_AudioEngine_##name

extern "C" {

// ---- Vòng đời luồng âm thanh ----
JNIEXPORT jboolean JNICALL JNI_FN(create)(JNIEnv*, jobject) {
    if (!gEngine) gEngine = std::make_shared<band::AudioEngine>();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL JNI_FN(destroy)(JNIEnv*, jobject) {
    if (gEngine) {
        gEngine->stop();
        gEngine.reset();
    }
}

JNIEXPORT jboolean JNICALL JNI_FN(start)(JNIEnv*, jobject) {
    return gEngine && gEngine->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(stop)(JNIEnv*, jobject) {
    if (gEngine) gEngine->stop();
}

// ---- Điều khiển ----
JNIEXPORT void JNICALL JNI_FN(setBpm)(JNIEnv*, jobject, jfloat bpm) {
    if (gEngine) gEngine->core().setBpm(bpm);
}

JNIEXPORT void JNICALL JNI_FN(setMetronome)(JNIEnv*, jobject, jboolean enabled) {
    if (gEngine) gEngine->core().setMetronomeEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL JNI_FN(setTrackMix)(JNIEnv*, jobject, jint track, jfloat volume, jfloat pan, jboolean mute,
                                           jboolean solo) {
    if (gEngine) gEngine->core().setTrackMix(track, volume, pan, mute == JNI_TRUE, solo == JNI_TRUE);
}

// Nhận cả bài nhạc dưới dạng một mảng int (định dạng xem engine_core.h), trả false nếu dữ liệu hỏng.
JNIEXPORT jboolean JNICALL JNI_FN(setSong)(JNIEnv* env, jobject, jintArray data) {
    if (!gEngine || !data) return JNI_FALSE;
    const jsize n = env->GetArrayLength(data);
    jint* p = env->GetIntArrayElements(data, nullptr);
    if (!p) return JNI_FALSE;
    band::Song* song = band::decodeSong(reinterpret_cast<const int32_t*>(p), static_cast<int>(n));
    env->ReleaseIntArrayElements(data, p, JNI_ABORT);
    if (!song) return JNI_FALSE;
    gEngine->core().publishSong(song);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL JNI_FN(play)(JNIEnv*, jobject, jint fromTick) {
    if (gEngine) gEngine->core().play(fromTick);
}

JNIEXPORT void JNICALL JNI_FN(stopTransport)(JNIEnv*, jobject) {
    if (gEngine) gEngine->core().stop();
}

JNIEXPORT void JNICALL JNI_FN(noteOn)(JNIEnv*, jobject, jint inst, jint track, jint note, jint velocity) {
    if (gEngine) gEngine->core().noteOn(inst, track, note, velocity);
}

JNIEXPORT void JNICALL JNI_FN(noteOff)(JNIEnv*, jobject, jint track, jint note) {
    if (gEngine) gEngine->core().noteOff(track, note);
}

JNIEXPORT void JNICALL JNI_FN(allNotesOff)(JNIEnv*, jobject) {
    if (gEngine) gEngine->core().allNotesOff();
}

JNIEXPORT void JNICALL JNI_FN(setSynthStyle)(JNIEnv*, jobject, jint track, jint style) {
    if (gEngine) gEngine->core().setSynthStyle(track, style);
}
JNIEXPORT void JNICALL JNI_FN(setMasterFx)(JNIEnv*, jobject, jboolean on, jfloat wet) {
    if (gEngine) gEngine->core().setMasterFx(on == JNI_TRUE, wet);
}

// Giọng hát: nạp / thay bản thu (mono float) vào ô >= 300.
JNIEXPORT jboolean JNICALL JNI_FN(loadVocal)(JNIEnv* env, jobject, jint id, jfloatArray data, jint sampleRate) {
    if (!gEngine || !data) return JNI_FALSE;
    const jsize n = env->GetArrayLength(data);
    jfloat* p = env->GetFloatArrayElements(data, nullptr);
    if (!p) return JNI_FALSE;
    const bool ok = gEngine->core().loadVocal(id, p, static_cast<int>(n), sampleRate);
    env->ReleaseFloatArrayElements(data, p, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL JNI_FN(clearVocal)(JNIEnv*, jobject, jint id) {
    return gEngine && gEngine->core().clearVocal(id) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL JNI_FN(setVocalFx)(JNIEnv*, jobject, jint track, jfloat reverb, jfloat comp, jfloat bright) {
    if (gEngine) gEngine->core().setVocalFx(track, reverb, comp, bright);
}
JNIEXPORT void JNICALL JNI_FN(enhanceVocal)(JNIEnv* env, jobject, jfloatArray data, jint sampleRate) {
    if (!data) return;
    const jsize n = env->GetArrayLength(data);
    std::vector<float> x(static_cast<size_t>(n));
    env->GetFloatArrayRegion(data, 0, n, x.data());
    band::enhanceVocal(x, sampleRate);
    env->SetFloatArrayRegion(data, 0, n, x.data());
}
JNIEXPORT void JNICALL JNI_FN(setVocalBypass)(JNIEnv*, jobject, jint track, jboolean bypass) {
    if (gEngine) gEngine->core().setVocalBypass(track, bypass == JNI_TRUE);
}
// Co/gian thoi gian giu cao do (WSOLA). ratio = do dai moi / do dai cu. Tra ve mang moi.
JNIEXPORT jfloatArray JNICALL JNI_FN(timeStretch)(JNIEnv* env, jobject, jfloatArray data, jint sampleRate, jdouble ratio) {
    if (!data) return nullptr;
    const jsize n = env->GetArrayLength(data);
    std::vector<float> x(static_cast<size_t>(n));
    env->GetFloatArrayRegion(data, 0, n, x.data());
    const std::vector<float> y = band::timeStretch(x, sampleRate, ratio);
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(y.size()));
    if (out && !y.empty()) env->SetFloatArrayRegion(out, 0, static_cast<jsize>(y.size()), y.data());
    return out;
}
// Nạp mẫu âm thật (mono float). Có thể gọi từ luồng nền: không dùng hàng đợi lệnh.
JNIEXPORT jboolean JNICALL JNI_FN(loadSample)(JNIEnv* env, jobject, jint id, jfloatArray data, jint sampleRate) {
    if (!gEngine || !data) return JNI_FALSE;
    const jsize n = env->GetArrayLength(data);
    jfloat* p = env->GetFloatArrayElements(data, nullptr);
    if (!p) return JNI_FALSE;
    const bool ok = gEngine->core().loadSample(id, p, static_cast<int>(n), sampleRate);
    env->ReleaseFloatArrayElements(data, p, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Đăng ký vùng phát của nhạc cụ có cao độ (gọi sau khi nạp mẫu, trước khi phát).
JNIEXPORT jboolean JNICALL JNI_FN(addZone)(JNIEnv*, jobject, jint inst, jint sample, jint root, jint lo, jint hi,
                                            jint velLo, jint velHi, jfloat gain) {
    return gEngine && gEngine->core().addZone(inst, sample, root, lo, hi, velLo, velHi, gain) ? JNI_TRUE : JNI_FALSE;
}

// ---- Looper: pad nhịp lặp ----
JNIEXPORT jboolean JNICALL JNI_FN(padLaunch)(JNIEnv*, jobject, jint track, jint row, jint pad) {
    return gEngine && gEngine->core().padLaunch(track, row, pad) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(setPadRecording)(JNIEnv*, jobject, jboolean on) {
    if (gEngine) gEngine->core().setRecording(on == JNI_TRUE);
}

// out có 5 phần tử: tick, track, kitIdx, vel, dur. Trả false nếu hết sự kiện.
JNIEXPORT jboolean JNICALL JNI_FN(popPadEvent)(JNIEnv* env, jobject, jintArray out) {
    if (!gEngine || !out || env->GetArrayLength(out) < 5) return JNI_FALSE;
    int32_t e[5];
    if (!gEngine->core().popRecEvent(e)) return JNI_FALSE;
    env->SetIntArrayRegion(out, 0, 5, reinterpret_cast<const jint*>(e));
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL JNI_FN(padMask)(JNIEnv*, jobject, jint track) {
    return gEngine ? static_cast<jlong>(gEngine->core().padMask(track)) : 0;
}

// ---- Đọc trạng thái ----
JNIEXPORT jdouble JNICALL JNI_FN(beatPosition)(JNIEnv*, jobject) {
    return gEngine ? gEngine->core().beatPosition() : 0.0;
}

JNIEXPORT jboolean JNICALL JNI_FN(isPlaying)(JNIEnv*, jobject) {
    return gEngine && gEngine->core().isPlaying() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jdouble JNICALL JNI_FN(latencyMs)(JNIEnv*, jobject) {
    return gEngine ? gEngine->latencyMs() : -1.0;
}

JNIEXPORT jint JNICALL JNI_FN(sampleRate)(JNIEnv*, jobject) {
    return gEngine ? gEngine->sampleRate() : 0;
}

JNIEXPORT jint JNICALL JNI_FN(xRunCount)(JNIEnv*, jobject) {
    return gEngine ? gEngine->xRunCount() : 0;
}

JNIEXPORT jint JNICALL JNI_FN(activeVoices)(JNIEnv*, jobject) {
    return gEngine ? gEngine->core().activeVoiceCount() : 0;
}


// ---- Xử lý bản thu giọng (chạy trên luồng nền, không liên quan luồng âm thanh) ----

// Lọc ồn tại chỗ. noiseCount mẫu đầu là đoạn "im lặng" để học phổ nhiễu (0 = tự tìm khung yên tĩnh nhất).
JNIEXPORT void JNICALL JNI_FN(denoiseVocal)(JNIEnv* env, jobject, jfloatArray data, jint sampleRate, jfloat strength, jint noiseCount) {
    if (!data) return;
    const jsize n = env->GetArrayLength(data);
    std::vector<float> x(static_cast<size_t>(n));
    env->GetFloatArrayRegion(data, 0, n, x.data());
    std::vector<float> noise;
    if (noiseCount > 1024 && noiseCount < n) noise.assign(x.begin(), x.begin() + noiseCount);
    band::DenoiseParams p;
    p.strength = strength;
    band::denoise(x, sampleRate, p, noise.empty() ? nullptr : noise.data(), static_cast<int>(noise.size()));
    env->SetFloatArrayRegion(data, 0, n, x.data());
}

// Auto-tune tại chỗ (xem band::autoTune).
JNIEXPORT void JNICALL JNI_FN(autoTuneVocal)(JNIEnv* env, jobject, jfloatArray data, jint sampleRate, jint keyRoot, jboolean minor,
                                             jboolean chromatic, jfloat strength, jfloat speed) {
    if (!data) return;
    const jsize n = env->GetArrayLength(data);
    std::vector<float> x(static_cast<size_t>(n));
    env->GetFloatArrayRegion(data, 0, n, x.data());
    band::AutoTuneParams p;
    p.keyRoot = ((keyRoot % 12) + 12) % 12;
    p.minor = minor == JNI_TRUE;
    p.chromatic = chromatic == JNI_TRUE;
    p.strength = strength;
    p.speed = speed;
    band::autoTune(x, sampleRate, p);
    env->SetFloatArrayRegion(data, 0, n, x.data());
}

// Đoán tông: {chủ âm 0..11, thứ (1) hay trưởng (0), độ tin cậy x1000, số ms có giọng}.
JNIEXPORT jintArray JNICALL JNI_FN(detectKey)(JNIEnv* env, jobject, jfloatArray data, jint sampleRate) {
    jintArray out = env->NewIntArray(4);
    if (!out || !data) return out;
    const jsize n = env->GetArrayLength(data);
    std::vector<float> x(static_cast<size_t>(n));
    env->GetFloatArrayRegion(data, 0, n, x.data());
    const band::KeyGuess g = band::estimateKey(band::trackPitch(x, sampleRate), sampleRate);
    const jint v[4] = {g.root, g.minor ? 1 : 0, static_cast<jint>(g.score * 1000.0f), static_cast<jint>(g.voicedSeconds * 1000.0f)};
    env->SetIntArrayRegion(out, 0, 4, v);
    return out;
}

// Cao độ (Hz) từng khung của bản thu (0 = không có cao độ); khoảng cách khung = vocalPitchHop mẫu.
JNIEXPORT jfloatArray JNICALL JNI_FN(trackVocalPitch)(JNIEnv* env, jobject, jfloatArray data, jint sampleRate) {
    if (!data) return nullptr;
    const jsize n = env->GetArrayLength(data);
    std::vector<float> x(static_cast<size_t>(n));
    env->GetFloatArrayRegion(data, 0, n, x.data());
    const std::vector<float> f = band::trackPitch(x, sampleRate);
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(f.size()));
    if (out && !f.empty()) env->SetFloatArrayRegion(out, 0, static_cast<jsize>(f.size()), f.data());
    return out;
}
JNIEXPORT jint JNICALL JNI_FN(vocalPitchHop)(JNIEnv*, jobject, jint sampleRate) { return band::pitchHop(sampleRate); }

// ---- Xuất file: chạy engine ngoại tuyến khi luồng âm thanh đã đóng (người gọi đảm bảo không còn callback nào) ----
// {sampleRate, số kênh} của engine (đã đặt khi luồng âm thanh được mở lần gần nhất).
JNIEXPORT jintArray JNICALL JNI_FN(exportInfo)(JNIEnv* env, jobject) {
    jintArray out = env->NewIntArray(2);
    if (!out) return out;
    const jint v[2] = {gEngine ? gEngine->core().sampleRate() : 48000, gEngine ? gEngine->core().channels() : 2};
    env->SetIntArrayRegion(out, 0, 2, v);
    return out;
}

// Render [frames] khung vào out (xen kẽ các kênh). Chỉ gọi khi luồng âm thanh đã đóng.
JNIEXPORT void JNICALL JNI_FN(exportRender)(JNIEnv* env, jobject, jfloatArray out, jint frames) {
    if (!gEngine || !out || frames <= 0) return;
    const jsize n = env->GetArrayLength(out);
    const int ch = gEngine->core().channels();
    if (static_cast<int64_t>(frames) * ch > n) return;
    jfloat* p = env->GetFloatArrayElements(out, nullptr);
    if (!p) return;
    gEngine->core().render(p, frames);
    env->ReleaseFloatArrayElements(out, p, 0);
}
}  // extern "C"
