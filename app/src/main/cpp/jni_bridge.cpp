#include <jni.h>

#include <memory>

#include "audio_engine.h"

namespace {
// Chỉ được chạm vào từ luồng chính (UI) của ứng dụng.
std::shared_ptr<band::AudioEngine> gEngine;
}  // namespace

#define JNI_FN(name) Java_com_bandstudio_app_audio_AudioEngine_##name

extern "C" {

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

JNIEXPORT void JNICALL JNI_FN(setBpm)(JNIEnv*, jobject, jfloat bpm) {
    if (gEngine) gEngine->core().setBpm(bpm);
}

JNIEXPORT void JNICALL JNI_FN(setPlaying)(JNIEnv*, jobject, jboolean playing) {
    if (gEngine) gEngine->core().setPlaying(playing == JNI_TRUE);
}

JNIEXPORT void JNICALL JNI_FN(setMetronome)(JNIEnv*, jobject, jboolean enabled) {
    if (gEngine) gEngine->core().setMetronomeEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL JNI_FN(noteOn)(JNIEnv*, jobject, jint note, jint velocity) {
    if (gEngine) gEngine->core().noteOn(note, velocity);
}

JNIEXPORT void JNICALL JNI_FN(noteOff)(JNIEnv*, jobject, jint note) {
    if (gEngine) gEngine->core().noteOff(note);
}

JNIEXPORT void JNICALL JNI_FN(allNotesOff)(JNIEnv*, jobject) {
    if (gEngine) gEngine->core().allNotesOff();
}

JNIEXPORT jdouble JNICALL JNI_FN(beatPosition)(JNIEnv*, jobject) {
    return gEngine ? gEngine->core().beatPosition() : 0.0;
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
