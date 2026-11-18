#include <jni.h>

#include <memory>

#include "audio_engine.h"

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

JNIEXPORT void JNICALL JNI_FN(setMasterFx)(JNIEnv*, jobject, jboolean on, jfloat wet) {
    if (gEngine) gEngine->core().setMasterFx(on == JNI_TRUE, wet);
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

}  // extern "C"
