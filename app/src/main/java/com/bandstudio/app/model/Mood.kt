package com.bandstudio.app.model

/**
 * Cảm xúc của bản phối: đổi hợp âm (nhiều vòng hơn hẳn), độ dày và độ to của trống, có clap / guitar hay không, kiểu đánh piano.
 * [minor] != null thì chọn cảm xúc sẽ đặt luôn thang thứ / trưởng (người dùng vẫn đổi lại được ở dòng chọn tông).
 */
enum class Mood(
    val id: String, val label: String, val hint: String, val minor: Boolean?,
    /** Hệ số độ mạnh: trống, nhạc cụ (piano/guitar/bass), hiệu ứng chuyển đoạn (crash/clap). */
    val drums: Float, val keys: Float, val fx: Float,
    val claps: Boolean, val guitar: Boolean, val sparseDrums: Boolean,
    /** Piano rải nốt (buồn / chill) thay vì đập cả hợp âm. */
    val arpeggio: Boolean, val pianoPatterns: List<Int>,
) {
    AUTO("auto", "Tự do", "Như trước: đủ nhịp, clap, crash.", null, 1f, 1f, 1f, true, true, false, false, listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)),
    SAD("sad", "Buồn", "Thang thứ, trống thưa và nhẹ, không clap, piano rải chậm, không guitar.", true, 0.62f, 0.8f, 0.45f, false, false, true, true, listOf(0, 1, 2, 4)),
    CHILL("chill", "Chill", "Êm, trống nhẹ, ít clap, piano rải, guitar khẽ.", null, 0.72f, 0.85f, 0.5f, false, true, true, true, listOf(0, 1, 2, 4, 6, 8)),
    HAPPY("happy", "Vui", "Thang trưởng, nhịp tươi, clap rõ, guitar quạt.", false, 1f, 1f, 0.9f, true, true, false, false, listOf(1, 3, 5, 8, 9)),
    HYPE("hype", "Mạnh", "Thang thứ, trống dày và to, clap + crash dồn mạnh.", true, 1.1f, 1.05f, 1.15f, true, true, false, false, listOf(3, 5, 8, 9, 6)),
    ;

    /** Các vòng hợp âm (4 hợp âm, lặp lại) mà bản phối chọn ngẫu nhiên khi chưa dò được hợp âm từ giọng. */
    fun progressions(minorScale: Boolean): List<List<Patterns.Chord>> {
        fun m(r: Int) = Patterns.Chord(r, true)
        fun M(r: Int) = Patterns.Chord(r, false)
        return when {
            this == SAD || this == HYPE -> if (this == SAD) listOf(
                listOf(m(0), M(8), M(3), M(10)), listOf(m(0), m(5), M(8), M(7)), listOf(M(8), M(3), M(10), m(0)),
                listOf(m(0), M(10), M(8), M(10)), listOf(m(0), m(5), m(0), M(10)), listOf(m(0), M(3), M(8), m(5)),
            ) else listOf(
                listOf(m(0), M(10), M(8), M(10)), listOf(m(0), M(8), M(10), m(0)), listOf(m(0), M(3), M(10), M(8)),
                listOf(m(0), m(0), M(8), M(10)), listOf(m(0), m(5), M(10), M(8)),
            )
            this == HAPPY -> listOf(
                listOf(M(0), M(7), m(9), M(5)), listOf(M(0), M(5), M(7), M(5)), listOf(m(9), M(5), M(0), M(7)),
                listOf(M(0), m(9), m(2), M(7)), listOf(M(0), m(4), M(5), M(7)), listOf(M(5), M(0), M(7), m(9)),
            )
            minorScale -> listOf(
                listOf(m(0), M(8), M(3), M(10)), listOf(m(0), m(5), M(10), M(3)), listOf(M(8), M(10), m(0), m(0)),
                listOf(m(0), M(10), M(8), m(5)), listOf(m(0), M(3), M(10), M(8)),
            )
            else -> listOf(
                listOf(M(0), M(7), m(9), M(5)), listOf(M(0), m(9), M(5), M(7)), listOf(M(5), M(0), M(7), m(9)),
                listOf(M(0), m(4), M(5), M(7)), listOf(M(0), M(5), m(9), M(7)),
            )
        }
    }

    companion object {
        fun of(id: String): Mood = entries.firstOrNull { it.id == id } ?: AUTO
    }
}
