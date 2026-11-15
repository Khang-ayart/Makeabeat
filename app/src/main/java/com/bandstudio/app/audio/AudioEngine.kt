package com.bandstudio.app.audio

/**
 * Cầu nối JNI tới engine âm thanh C++ (Oboe).
 *
 * Quy ước: MỌI hàm ở đây chỉ được gọi từ luồng chính (UI). Hàng đợi lệnh phía C++
 * là lock-free một-người-ghi, nên không được gọi từ nhiều luồng cùng lúc.
 */
object AudioEngine {
    init {
        System.loadLibrary("bandaudio")
    }

    external fun create(): Boolean
    external fun destroy()

    /** Mở luồng âm thanh. Gọi ở onStart, đóng ở onStop để không giữ thiết bị âm thanh khi ở nền. */
    external fun start(): Boolean
    external fun stop()

    external fun setBpm(bpm: Float)
    external fun setPlaying(playing: Boolean)
    external fun setMetronome(enabled: Boolean)

    external fun noteOn(note: Int, velocity: Int)
    external fun noteOff(note: Int)
    external fun allNotesOff()

    /** Vị trí hiện tại tính bằng phách (0.0 khi đang dừng). */
    external fun beatPosition(): Double

    /** Độ trễ đầu ra ước tính (ms); -1 nếu chưa đo được. */
    external fun latencyMs(): Double
    external fun sampleRate(): Int
    external fun xRunCount(): Int
    external fun activeVoices(): Int
}
