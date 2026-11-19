package com.bandstudio.app.model

/** Đọc WAV PCM 16-bit (mono hoặc nhiều kênh -> lấy trung bình) thành float [-1, 1]. */
class WavData(val samples: FloatArray, val sampleRate: Int)

object Wav {
    /** Trả null nếu không phải WAV PCM 16-bit hợp lệ. */
    fun parse(b: ByteArray): WavData? {
        fun u16(i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        fun u32(i: Int) = u16(i) or (u16(i + 2) shl 16)
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var pos = 12
        var channels = 0
        var rate = 0
        var bits = 0
        var format = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val size = u32(pos + 4)
            val body = pos + 8
            if (size < 0) return null
            if (id == "fmt " && body + 16 <= b.size) {
                format = u16(body); channels = u16(body + 2); rate = u32(body + 4); bits = u16(body + 14)
            } else if (id == "data") {
                if (format != 1 || bits != 16 || channels < 1 || rate <= 0) return null
                val avail = minOf(size, b.size - body)
                val frames = avail / (2 * channels)
                val out = FloatArray(frames)
                for (f in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until channels) {
                        val v = u16(body + (f * channels + c) * 2).toShort().toInt()
                        sum += v / 32768f
                    }
                    out[f] = sum / channels
                }
                return WavData(out, rate)
            }
            pos = body + size + (size and 1)
        }
        return null
    }

    /** Ghi WAV PCM 16-bit mono (clip về [-1, 1]). */
    fun encode16(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataLen = samples.size * 2
        val b = ByteArray(44 + dataLen)
        fun put32(i: Int, v: Int) { b[i] = v.toByte(); b[i + 1] = (v shr 8).toByte(); b[i + 2] = (v shr 16).toByte(); b[i + 3] = (v shr 24).toByte() }
        fun put16(i: Int, v: Int) { b[i] = v.toByte(); b[i + 1] = (v shr 8).toByte() }
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(b, 0)
        put32(4, 36 + dataLen)
        "WAVEfmt ".toByteArray(Charsets.US_ASCII).copyInto(b, 8)
        put32(16, 16); put16(20, 1); put16(22, 1); put32(24, sampleRate); put32(28, sampleRate * 2); put16(32, 2); put16(34, 16)
        "data".toByteArray(Charsets.US_ASCII).copyInto(b, 36)
        put32(40, dataLen)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            b[44 + i * 2] = v.toByte()
            b[45 + i * 2] = (v shr 8).toByte()
        }
        return b
    }
}
