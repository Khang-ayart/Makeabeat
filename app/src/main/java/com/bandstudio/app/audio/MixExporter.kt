package com.bandstudio.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Ghi bản phối đã render (PCM 16-bit xen kẽ kênh) ra file:
 * - WAV: không nén, dùng để chỉnh tiếp hoặc lưu bản gốc.
 * - M4A (AAC 192 kbps): nhẹ, dễ gửi; mọi trình dựng video đều nhận.
 */
object MixExporter {

    fun writeWav(file: File, pcm: ShortArray, rate: Int, channels: Int) {
        val dataLen = pcm.size * 2
        file.outputStream().buffered().use { out ->
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataLen).put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            h.putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * 2)
                .putShort((channels * 2).toShort()).putShort(16)
            h.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataLen)
            out.write(h.array())
            val chunk = ByteBuffer.allocate(8192 * 2).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < pcm.size) {
                chunk.clear()
                val n = min(8192, pcm.size - i)
                for (k in 0 until n) chunk.putShort(pcm[i + k])
                out.write(chunk.array(), 0, n * 2)
                i += n
            }
        }
    }

    private class Sample(val data: ByteArray, val pts: Long, val flags: Int)

    /** M4A (AAC-LC 192 kbps). [onProgress] 0..1. */
    fun writeM4a(file: File, pcm: ShortArray, rate: Int, channels: Int, onProgress: (Float) -> Unit) {
        val samples = ArrayList<Sample>()
        var format: MediaFormat? = null
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            }
            enc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            var pos = 0   // vị trí trong pcm (số short)
            var eosSent = false
            var done = false
            val info = MediaCodec.BufferInfo()
            while (!done) {
                if (!eosSent) {
                    val idx = enc.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = enc.getInputBuffer(idx)!!
                        buf.clear()
                        buf.order(ByteOrder.LITTLE_ENDIAN)
                        val pts = pos / channels * 1_000_000L / rate
                        if (pos >= pcm.size) {
                            enc.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosSent = true
                        } else {
                            val n = min(buf.remaining() / 2 / channels * channels, pcm.size - pos)
                            buf.asShortBuffer().put(pcm, pos, n)
                            enc.queueInputBuffer(idx, 0, n * 2, pts, 0)
                            pos += n
                            onProgress(0.9f * pos / pcm.size)
                        }
                    }
                }
                val oi = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> format = enc.outputFormat
                    oi >= 0 -> {
                        val buf = enc.getOutputBuffer(oi)
                        if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val bytes = ByteArray(info.size)
                            buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(bytes)
                            samples.add(Sample(bytes, info.presentationTimeUs, info.flags))
                        }
                        enc.releaseOutputBuffer(oi, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
                    }
                }
            }
        } finally {
            try { enc.stop() } catch (e: Exception) { /* bỏ qua */ }
            enc.release()
        }
        val fmt = format ?: throw IllegalStateException("Bộ mã hóa âm thanh không trả định dạng")
        if (file.exists()) file.delete()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val track = muxer.addTrack(fmt)
            muxer.start()
            val bi = MediaCodec.BufferInfo()
            for (smp in samples) {
                bi.set(0, smp.data.size, smp.pts, smp.flags)
                muxer.writeSampleData(track, ByteBuffer.wrap(smp.data), bi)
            }
            muxer.stop()
        } finally {
            muxer.release()
        }
        onProgress(1f)
    }
}
