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

    // ---- Luồng âm thanh ----
    external fun create(): Boolean
    external fun destroy()

    /** Mở luồng âm thanh. Gọi ở onStart, đóng ở onStop để không giữ thiết bị âm thanh khi ở nền. */
    external fun start(): Boolean
    external fun stop()

    // ---- Điều khiển ----
    external fun setBpm(bpm: Float)
    external fun setMetronome(enabled: Boolean)
    external fun setTrackMix(track: Int, volume: Float, pan: Float, mute: Boolean, solo: Boolean)

    /** Gửi cả bài nhạc (định dạng mảng int, xem Project.encodeForEngine). Trả false nếu dữ liệu hỏng. */
    external fun setSong(data: IntArray): Boolean

    /** Phát từ tick [fromTick]. Có lặp hay không do bài nhạc (setSong) quyết định. */
    external fun play(fromTick: Int)
    external fun stopTransport()

    /** Chạm phím / pad: [inst] là Instrument.id, [track] là chỉ số track trong mixer. */
    external fun noteOn(inst: Int, track: Int, note: Int, velocity: Int)
    external fun noteOff(track: Int, note: Int)
    external fun allNotesOff()

    // ---- Trạng thái ----
    /** Vị trí hiện tại tính bằng phách (0.0 khi đang dừng). */
    external fun beatPosition(): Double
    external fun isPlaying(): Boolean

    /** Độ trễ đầu ra ước tính (ms); -1 nếu chưa đo được. */
    external fun latencyMs(): Double
    external fun sampleRate(): Int
    external fun xRunCount(): Int
    external fun activeVoices(): Int
}
