package com.bandstudio.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.bandstudio.app.model.WavData
import java.nio.ByteOrder

/** Giải mã MP3 / M4A / AAC / OGG / WAV / FLAC bằng bộ giải mã của hệ điều hành ra float mono (trộn các kênh). */
object AudioDecoder {
    const val MAX_SECONDS = 360

    /** Trả null nếu không đọc được hoặc không có luồng âm thanh. Chạy trên luồng nền. */
    fun decode(context: Context, uri: Uri): WavData? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return null
            ex.selectTrack(track)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var floatPcm = false
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()

            var out = FloatArray(rate * 30)
            var n = 0
            val limit = { rate.toLong() * MAX_SECONDS }
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone && n < limit()) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    oi >= 0 -> {
                        val buf = codec.getOutputBuffer(oi)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.nativeOrder())
                            val frames = info.size / ((if (floatPcm) 4 else 2) * channels)
                            if (n + frames > out.size) out = out.copyOf(maxOf(out.size * 2, n + frames))
                            if (floatPcm) {
                                val fb = buf.asFloatBuffer()
                                for (k in 0 until frames) {
                                    var s = 0f
                                    for (c in 0 until channels) s += fb.get()
                                    out[n++] = s / channels
                                }
                            } else {
                                val sb = buf.asShortBuffer()
                                for (k in 0 until frames) {
                                    var s = 0f
                                    for (c in 0 until channels) s += sb.get() / 32768f
                                    out[n++] = s / channels
                                }
                            }
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (n < 1024 || rate <= 0) return null
            val maxFrames = limit().toInt()
            return WavData(out.copyOf(minOf(n, maxFrames)), rate)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }

    /** Tên hiển thị của tệp (bỏ đuôi) để đặt tên track. */
    fun displayName(context: Context, uri: Uri): String {
        val raw = try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) { null } ?: uri.lastPathSegment ?: "Audio"
        return raw.substringBeforeLast('.').ifBlank { "Audio" }.take(24)
    }
}
