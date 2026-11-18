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

    /** Nạp mẫu âm thật vào ô [id] (mono float [-1,1]); mỗi ô chỉ nạp được một lần. Gọi được từ luồng nền. */
    external fun setSynthStyle(track: Int, style: Int)
    external fun setMasterFx(on: Boolean, wet: Float)
    /** Nạp / thay bản thu giọng hát vào ô [slot] (>= 300). Gọi từ luồng nền. */
    external fun loadVocal(slot: Int, data: FloatArray, sampleRate: Int): Boolean
    external fun clearVocal(slot: Int): Boolean
    external fun setVocalFx(track: Int, reverb: Float, comp: Float, bright: Float)
    external fun loadSample(id: Int, data: FloatArray, sampleRate: Int): Boolean
    external fun addZone(inst: Int, sample: Int, root: Int, lo: Int, hi: Int, velLo: Int, velHi: Int, gain: Float): Boolean

    // ---- Looper: pad nhịp lặp ----
    /** Bật pad (>= 0) hoặc tắt hàng (-1). Có hiệu lực ở đầu ô nhịp kế tiếp. Trả false nếu hàng/pad sai. */
    external fun padLaunch(track: Int, row: Int, pad: Int): Boolean

    /** Bật/tắt báo sự kiện: mỗi cú đánh do pad phát ra được đưa vào hàng đợi cho [popPadEvent]. */
    external fun setPadRecording(on: Boolean)

    /** Lấy một sự kiện vào [out] = {tick, track, kitIdx, vel}; false nếu hết. */
    external fun popPadEvent(out: IntArray): Boolean

    /** Bit 0-19: pad đang chạy; 20-39: đang chờ bật; 40-59: đang chờ tắt. */
    external fun padMask(track: Int): Long

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
