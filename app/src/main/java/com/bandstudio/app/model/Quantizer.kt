package com.bandstudio.app.model

/**
 * Quantization dùng chung cho MỌI nhạc cụ MIDI (không nhạc cụ nào có cơ chế căn nhịp riêng).
 * Tách bạch ba việc: ghi MIDI (lưu thời điểm thô), Quantize (căn vào lưới), Loop (do engine lo).
 * Hàm thuần túy, kiểm thử được bằng unit test.
 */
object Quantizer {
    /** Các mức lưới: 1/4, 1/8, 1/16, 1/32 (tick). */
    val GRIDS = listOf(PPQ, PPQ / 2, PPQ / 4, PPQ / 8)

    fun label(step: Int): String = when (step) {
        PPQ -> "1/4"
        PPQ / 2 -> "1/8"
        PPQ / 4 -> "1/16"
        PPQ / 8 -> "1/32"
        else -> "$step"
    }

    /** Căn điểm bắt đầu vào lưới gần nhất; nếu làm tròn tới cuối clip thì quay về đầu clip (vòng lặp). */
    fun snapStart(raw: Int, step: Int, lengthTicks: Int): Int {
        val s = ((raw.coerceAtLeast(0) + step / 2) / step) * step
        return if (s >= lengthTicks) 0 else s
    }

    /** Căn độ dài vào lưới, tối thiểu một ô lưới, không vượt [maxLen]. */
    fun snapDuration(raw: Int, step: Int, maxLen: Int): Int =
        minOf(maxOf(step, ((raw.coerceAtLeast(0) + step / 2) / step) * step), maxLen.coerceAtLeast(1))

    /** Quantize cả đoạn: căn điểm bắt đầu và độ dài, bỏ nốt trùng (cùng cao độ, cùng vị trí). */
    fun quantizeNotes(notes: List<NoteEvent>, step: Int, lengthTicks: Int): List<NoteEvent> =
        notes
            .map { n ->
                val start = snapStart(n.start, step, lengthTicks)
                n.copy(start = start, dur = snapDuration(n.dur, step, lengthTicks - start))
            }
            .distinctBy { it.pitch to it.start }
            .sortedBy { it.start }
}
