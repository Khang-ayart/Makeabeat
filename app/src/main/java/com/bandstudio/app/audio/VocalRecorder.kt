package com.bandstudio.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Thu giọng từ micro (mono, 16-bit) vào bộ nhớ. Cần quyền RECORD_AUDIO (người gọi kiểm tra trước).
 * Luồng đọc riêng; [level] cho đồng hồ mức tín hiệu; [stop] trả toàn bộ bản thu.
 */
class VocalRecorder {
    val sampleRate = 44100

    @Volatile var level = 0f
        private set
    @Volatile var running = false
        private set
    /** Mốc System.nanoTime() ngay sau khi micro bắt đầu thu (để tính độ lệch với lúc engine bắt đầu phát). */
    @Volatile var startedNanos = 0L
        private set
    @Volatile var seconds = 0f
        private set

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var buf = ShortArray(sampleRate * 30)
    @Volatile private var count = 0

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, sampleRate / 5 * 2),
            )
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        // Tự chỉnh độ nhạy micro / khử ồn của hệ thống nếu máy hỗ trợ (giúp bản thu từ mic điện thoại đỡ nhỏ).
        try { if (android.media.audiofx.AutomaticGainControl.isAvailable()) android.media.audiofx.AutomaticGainControl.create(rec.audioSessionId)?.enabled = true } catch (e: Exception) { }
        count = 0
        level = 0f
        seconds = 0f
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            return false
        }
        startedNanos = System.nanoTime()
        record = rec
        running = true
        val maxSamples = sampleRate * MAX_SECONDS
        thread = Thread {
            val chunk = ShortArray(1024)
            while (running) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                if (count + n > buf.size) {
                    if (buf.size >= maxSamples) { running = false; break }
                    buf = buf.copyOf(minOf(buf.size * 2, maxSamples))
                }
                var peak = 0
                for (i in 0 until n) {
                    val v = chunk[i].toInt()
                    buf[count + i] = chunk[i]
                    val a = if (v < 0) -v else v
                    if (a > peak) peak = a
                }
                count += n
                val l = peak / 32768f
                level = if (l > level) l else level * 0.85f + l * 0.15f
                seconds = count.toFloat() / sampleRate
            }
        }.also { it.name = "vocal-recorder"; it.start() }
        return true
    }

    /** Dừng và trả bản thu (float [-1, 1]); null nếu chưa thu gì. */
    fun stop(): FloatArray? {
        running = false
        try { thread?.join(500) } catch (e: InterruptedException) { /* bỏ qua */ }
        thread = null
        try { record?.stop() } catch (e: Exception) { /* đã dừng */ }
        record?.release()
        record = null
        level = 0f
        val n = count
        if (n < sampleRate / 4) return null
        return FloatArray(n) { buf[it] / 32768f }
    }

    companion object {
        const val MAX_SECONDS = 180
    }
}
