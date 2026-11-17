package com.bandstudio.app.audio

import android.content.Context
import com.bandstudio.app.model.Catalog
import com.bandstudio.app.model.Wav
import com.bandstudio.app.model.WavData

/**
 * Nạp mẫu âm thật (assets/samples/<id>.wav, CC0) vào engine. Gọi ngay sau AudioEngine.create(), trên luồng chính
 * (khoảng 1 MB, vài chục ms), để không có luồng nền nào chạm vào engine khi nó bị huỷ. Giải mã chỉ làm một lần mỗi tiến trình.
 */
object SampleBank {
    private var cache: List<WavData?>? = null

    fun loadInto(context: Context) {
        val decoded = cache ?: Catalog.sampleIds.map { id ->
            try {
                context.assets.open("samples/$id.wav").use { Wav.parse(it.readBytes()) }
            } catch (e: Exception) {
                null // thiếu mẫu: pad dùng âm tổng hợp dự phòng
            }
        }.also { cache = it }
        decoded.forEachIndexed { slot, wav ->
            if (wav != null) AudioEngine.loadSample(slot, wav.samples, wav.sampleRate)
        }
    }
}
