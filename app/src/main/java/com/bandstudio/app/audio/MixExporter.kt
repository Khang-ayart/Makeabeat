package com.bandstudio.app.audio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.min

/**
 * Ghi bản phối đã render (PCM 16-bit xen kẽ kênh) ra file:
 * - WAV: không nén, dùng để chỉnh tiếp hoặc lưu bản gốc.
 * - MP4 (AAC + ảnh bìa tĩnh): định dạng YouTube nhận (YouTube không nhận file chỉ có âm thanh).
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

    private const val W = 1280
    private const val H = 720

    /** MP4 (H.264 ảnh tĩnh 1 khung/giây + AAC 192 kbps). [onProgress] 0..1. */
    fun writeMp4(file: File, pcm: ShortArray, rate: Int, channels: Int, title: String, onProgress: (Float) -> Unit) {
        val durationSec = pcm.size.toDouble() / channels / rate
        val (y, u, v) = coverYuv(title)
        val videoSamples = ArrayList<Sample>()
        val audioSamples = ArrayList<Sample>()
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null

        // ---- Video: cùng một khung hình lặp mỗi giây ----
        val vEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 600_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 2)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            vEnc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            vEnc.start()
            val frames = ceil(durationSec).toInt() + 1
            var sent = 0
            var done = false
            val info = MediaCodec.BufferInfo()
            while (!done) {
                if (sent <= frames) {
                    val idx = vEnc.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val pts = sent * 1_000_000L
                        if (sent < frames) {
                            val img = vEnc.getInputImage(idx)
                            if (img != null) fillImage(img, y, u, v)
                            vEnc.queueInputBuffer(idx, 0, W * H * 3 / 2, pts, 0)
                        } else {
                            vEnc.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                        sent++
                        onProgress(0.1f * min(sent, frames) / frames)
                    }
                }
                val oi = vEnc.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> videoFormat = vEnc.outputFormat
                    oi >= 0 -> {
                        val buf = vEnc.getOutputBuffer(oi)
                        if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val bytes = ByteArray(info.size)
                            buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(bytes)
                            videoSamples.add(Sample(bytes, info.presentationTimeUs, info.flags))
                        }
                        vEnc.releaseOutputBuffer(oi, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
                    }
                }
            }
        } finally {
            try { vEnc.stop() } catch (e: Exception) { /* bỏ qua */ }
            vEnc.release()
        }

        // ---- Âm thanh AAC ----
        val aEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            }
            aEnc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aEnc.start()
            var pos = 0   // vị trí trong pcm (số short)
            var eosSent = false
            var done = false
            val info = MediaCodec.BufferInfo()
            while (!done) {
                if (!eosSent) {
                    val idx = aEnc.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = aEnc.getInputBuffer(idx)!!
                        buf.clear()
                        buf.order(ByteOrder.LITTLE_ENDIAN)
                        val pts = pos / channels * 1_000_000L / rate
                        if (pos >= pcm.size) {
                            aEnc.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosSent = true
                        } else {
                            val n = min(buf.remaining() / 2 / channels * channels, pcm.size - pos)
                            val sb = buf.asShortBuffer()
                            sb.put(pcm, pos, n)
                            aEnc.queueInputBuffer(idx, 0, n * 2, pts, 0)
                            pos += n
                            onProgress(0.1f + 0.8f * pos / pcm.size)
                        }
                    }
                }
                val oi = aEnc.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> audioFormat = aEnc.outputFormat
                    oi >= 0 -> {
                        val buf = aEnc.getOutputBuffer(oi)
                        if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val bytes = ByteArray(info.size)
                            buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(bytes)
                            audioSamples.add(Sample(bytes, info.presentationTimeUs, info.flags))
                        }
                        aEnc.releaseOutputBuffer(oi, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
                    }
                }
            }
        } finally {
            try { aEnc.stop() } catch (e: Exception) { /* bỏ qua */ }
            aEnc.release()
        }

        // ---- Ghép vào MP4 (xen kẽ theo thời gian) ----
        val vf = videoFormat ?: throw IllegalStateException("Bộ mã hóa video không trả định dạng")
        val af = audioFormat ?: throw IllegalStateException("Bộ mã hóa âm thanh không trả định dạng")
        if (file.exists()) file.delete()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val vTrack = muxer.addTrack(vf)
            val aTrack = muxer.addTrack(af)
            muxer.start()
            val bi = MediaCodec.BufferInfo()
            var vi = 0
            var ai = 0
            while (vi < videoSamples.size || ai < audioSamples.size) {
                val takeVideo = ai >= audioSamples.size || (vi < videoSamples.size && videoSamples[vi].pts <= audioSamples[ai].pts)
                val s = if (takeVideo) videoSamples[vi++] else audioSamples[ai++]
                bi.set(0, s.data.size, s.pts, s.flags)
                muxer.writeSampleData(if (takeVideo) vTrack else aTrack, ByteBuffer.wrap(s.data), bi)
            }
            muxer.stop()
        } finally {
            muxer.release()
        }
        onProgress(1f)
    }

    /** Ảnh bìa (tên bài + dòng nhỏ) -> ba mặt phẳng Y, U, V (4:2:0, U/V mỗi mặt W/2 x H/2). */
    private fun coverYuv(title: String): Triple<ByteArray, ByteArray, ByteArray> {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), Color.rgb(24, 28, 48), Color.rgb(96, 64, 140), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        p.shader = null
        p.color = Color.WHITE
        p.textAlign = Paint.Align.CENTER
        var size = 120f
        p.textSize = size
        val text = title.ifBlank { "Bản nhạc" }
        while (p.measureText(text) > W * 0.86f && size > 30f) { size -= 4f; p.textSize = size }
        c.drawText(text, W / 2f, H / 2f + size / 3f, p)
        p.textSize = 36f
        p.color = Color.argb(190, 255, 255, 255)
        c.drawText("Made with BandStudio", W / 2f, H - 60f, p)
        val px = IntArray(W * H)
        bmp.getPixels(px, 0, W, 0, 0, W, H)
        bmp.recycle()
        val y = ByteArray(W * H)
        val u = ByteArray(W * H / 4)
        val v = ByteArray(W * H / 4)
        for (j in 0 until H) for (i in 0 until W) {
            val a = px[j * W + i]
            val r = (a shr 16) and 0xFF; val g = (a shr 8) and 0xFF; val b = a and 0xFF
            y[j * W + i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(16, 235).toByte()
            if (j % 2 == 0 && i % 2 == 0) {
                val k = (j / 2) * (W / 2) + i / 2
                u[k] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(16, 240).toByte()
                v[k] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(16, 240).toByte()
            }
        }
        return Triple(y, u, v)
    }

    private fun fillImage(img: android.media.Image, y: ByteArray, u: ByteArray, v: ByteArray) {
        val planes = img.planes
        fun put(plane: android.media.Image.Plane, src: ByteArray, w: Int, h: Int) {
            val buf = plane.buffer
            val rs = plane.rowStride
            val ps = plane.pixelStride
            if (ps == 1 && rs == w) {
                buf.position(0)
                buf.put(src, 0, w * h)
            } else {
                for (r in 0 until h) for (cx in 0 until w) buf.put(r * rs + cx * ps, src[r * w + cx])
            }
        }
        put(planes[0], y, W, H)
        put(planes[1], u, W / 2, H / 2)
        put(planes[2], v, W / 2, H / 2)
    }
}
