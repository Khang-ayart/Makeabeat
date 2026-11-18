package com.bandstudio.app.audio

import android.content.Context
import com.bandstudio.app.model.Catalog
import com.bandstudio.app.model.Instruments
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
        loadInstruments(context)
    }

    /**
     * Piano, guitar, bass thật: đọc manifest rồi nạp từng mẫu vào ô 100+ và đăng ký vùng phát. Mỗi mẫu được nạp xong là bỏ,
     * không giữ bản giải mã trong bộ nhớ Java (engine giữ bản 16-bit của riêng nó). Thiếu tệp thì nhạc cụ đó dùng giọng tổng hợp.
     */
    private fun loadInstruments(context: Context) {
        val sets = try {
            Instruments.parse(context.assets.open("instruments/manifest.json").use { String(it.readBytes()) })
        } catch (e: Exception) {
            return
        }
        var slot = Instruments.FIRST_SLOT
        for (set in sets) for (z in set.zones) {
            val wav = try {
                context.assets.open("instruments/${z.file}").use { Wav.parse(it.readBytes()) }
            } catch (e: Exception) {
                null
            }
            if (wav != null && AudioEngine.loadSample(slot, wav.samples, wav.sampleRate)) {
                AudioEngine.addZone(set.inst, slot, z.root, z.lo, z.hi, z.velLo, z.velHi, set.gain)
            }
            slot++
        }
    }
}
