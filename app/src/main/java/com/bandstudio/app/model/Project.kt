package com.bandstudio.app.model

const val PPQ = 960                       // tick / phách (khớp engine C++)
const val BEATS_PER_BAR = 4
const val BAR_TICKS = PPQ * BEATS_PER_BAR // 3840
const val STEP_TICKS = PPQ / 4            // 1/16 = 240
const val MAX_TRACKS = 8
const val MAX_BARS = 64
const val MAX_CLIP_BARS = 8

/** Dải nốt hiển thị trong Piano Roll: C1..B6. */
const val ROLL_LOW = 24
const val ROLL_HIGH = 95

enum class Instrument(
    val id: Int,            // khớp enum Inst trong C++
    val label: String,
    val isDrum: Boolean,
    val color: Long,
    val rollCenter: Int,    // nốt cuộn tới khi mở Piano Roll
    val keyStart: Int,      // phím C đầu tiên của bàn phím mini
) {
    PIANO(0, "Piano", false, 0xFF5B8CFF, 64, 60),
    ACOUSTIC(1, "Acoustic Guitar", false, 0xFFF2A444, 55, 48),
    ELECTRIC(2, "Electric Guitar", false, 0xFFE5534B, 55, 48),
    BASS(3, "Bass", false, 0xFF9B6BFF, 40, 36),
    DRUM_KIT(4, "Drum Kit", true, 0xFF2FBF8F, 0, 0),
    DRUM_PAD(5, "Drum Pad", true, 0xFF1FB5D4, 0, 0);

    companion object {
        fun fromId(id: Int): Instrument = entries.firstOrNull { it.id == id } ?: PIANO
    }
}

/** Thứ tự khớp enum DrumPiece trong C++ (pitch của nốt trống = chỉ số ở đây). */
val DRUM_NAMES = listOf("Kick", "Snare", "Hi-hat", "Hi-hat mở", "Clap", "Tom thấp", "Tom cao", "Crash", "Ride")

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

fun noteName(pitch: Int): String = NOTE_NAMES[pitch % 12] + (pitch / 12 - 1)

fun isBlackKey(pitch: Int): Boolean = pitch % 12 in intArrayOf(1, 3, 6, 8, 10)

/** start / dur tính theo tick, TƯƠNG ĐỐI so với đầu clip. */
data class NoteEvent(val start: Int, val dur: Int, val pitch: Int, val vel: Int = 100)

data class Clip(
    val id: Int,
    val startBar: Int,
    val bars: Int,
    val notes: List<NoteEvent> = emptyList(),
) {
    val endBar: Int get() = startBar + bars
    val lengthTicks: Int get() = bars * BAR_TICKS
}

data class Track(
    val id: Int,
    val name: String,
    val instrument: Instrument,
    val volume: Float = 0.8f,
    val pan: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
    val clips: List<Clip> = emptyList(),
) {
    /** Còn trống [startBar, startBar+bars) không (bỏ qua clip [ignoreId])? */
    fun isFree(startBar: Int, bars: Int, ignoreId: Int = -1): Boolean =
        startBar >= 0 && startBar + bars <= MAX_BARS &&
            clips.none { it.id != ignoreId && startBar < it.endBar && it.startBar < startBar + bars }
}

data class Project(
    val id: String,
    val name: String,
    val bpm: Int = 100,
    val tracks: List<Track> = emptyList(),
    val nextId: Int = 1,
    val updatedAt: Long = 0L,
) {
    /** Ô nhịp cuối cùng có nhạc (0 nếu chưa có clip nào). */
    val endBar: Int get() = tracks.maxOfOrNull { t -> t.clips.maxOfOrNull { it.endBar } ?: 0 } ?: 0

    fun track(id: Int): Track? = tracks.firstOrNull { it.id == id }
    fun trackIndex(id: Int): Int = tracks.indexOfFirst { it.id == id }
}

/**
 * Đóng gói bài nhạc thành mảng int cho engine C++ (xem decodeSong trong engine_core.h):
 * [1, loop, loopStart, loopEnd, length, trackCount, rồi mỗi track: inst, noteCount, noteCount * (start, dur, pitch, vel)]
 * Nốt của mọi clip được trải ra tick tuyệt đối trong bài; nốt vượt quá độ dài clip bị bỏ / cắt.
 */
fun Project.encodeForEngine(loop: Boolean, loopStart: Int, loopEnd: Int, length: Int): IntArray {
    val used = tracks.take(MAX_TRACKS)
    val out = ArrayList<Int>()
    out.add(1)
    out.add(if (loop) 1 else 0)
    out.add(loopStart)
    out.add(loopEnd)
    out.add(length)
    out.add(used.size)
    for (t in used) {
        val flat = ArrayList<Int>()
        var count = 0
        for (c in t.clips) {
            val base = c.startBar * BAR_TICKS
            val limit = c.lengthTicks
            for (n in c.notes) {
                if (n.start < 0 || n.start >= limit) continue
                flat.add(base + n.start)
                flat.add(minOf(n.dur, limit - n.start))
                flat.add(n.pitch)
                flat.add(n.vel)
                count++
            }
        }
        out.add(t.instrument.id)
        out.add(count)
        out.addAll(flat)
    }
    return out.toIntArray()
}
