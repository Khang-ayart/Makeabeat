package com.bandstudio.app.model

const val PPQ = 960                       // tick / phách (khớp engine C++)
const val BEATS_PER_BAR = 4
const val BAR_TICKS = PPQ * BEATS_PER_BAR // 3840
const val STEP_TICKS = PPQ / 4            // 1/16 = 240
const val MAX_TRACKS = 8
const val MAX_BARS = 64
const val MAX_CLIP_BARS = 8
const val MAX_KIT = 32  // khớp kMaxKit

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
    DRUM_PAD(5, "Drum Pad", true, 0xFF1FB5D4, 0, 0),
    LOOPER(6, "Looper", false, 0xFFE0568F, 0, 0),
    VOCAL(9, "Vocal", false, 0xFFFFD166, 60, 60),
    AUDIO(10, "Audio", false, 0xFF8AD1C2, 60, 60);

    val isLooper: Boolean get() = this == LOOPER
    val isVocal: Boolean get() = this == VOCAL
    val isAudio: Boolean get() = this == AUDIO

    /** Id trong engine C++: track Audio dùng chung bộ phát clip của Vocal (id 9). */
    val engineId: Int get() = if (this == AUDIO) VOCAL.id else id

    /** Nhạc cụ bấm là phát một tiếng ngắn (trống, pad Looper): lưới 1/16, không có nốt kéo dài. */
    val oneShot: Boolean get() = isDrum || isLooper

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

/** Ô mẫu của giọng hát trong engine (mỗi bài chỉ có một track Vocal). */
const val VOCAL_SLOT = 300

fun tickToMs(tick: Int, bpm: Int): Int = (tick.toLong() * 60000L / (PPQ.toLong() * bpm.coerceAtLeast(1))).toInt()
fun msToTick(ms: Int, bpm: Int): Int = (ms.toLong() * PPQ * bpm / 60000L).toInt()

/** Một đoạn của bản thu giọng sau khi cắt: lấy [lenMs] từ [srcStartMs] của tệp đã xử lý, phát ở [atMs] (ms trong bài). */
data class VocalSeg(val id: Int, val srcStartMs: Int, val lenMs: Int, val atMs: Int) {
    val endMs: Int get() = atMs + lenMs
}

/**
 * Bản thu giọng của track Vocal. Hai tệp WAV mono 16-bit trong thư mục vocal của dự án: [rawFile] (nguyên bản vừa thu)
 * và [file] (đã lọc ồn / auto-tune / chuẩn hóa, là bản được phát). Xử lý luôn chạy lại từ bản gốc nên đổi ý thoải mái.
 */
data class VocalClip(
    val rawFile: String,
    val file: String,
    val sampleRate: Int,
    val lengthMs: Int,
    /** Vị trí (ms trong bản thu) của giây 0 của bài: đếm nhịp + độ trễ thiết bị. */
    val syncMs: Int = 0,
    /** Người dùng dịch giọng: dương = giọng nghe SỚM hơn (cắt thêm đầu bản thu), âm = muộn hơn. */
    val nudgeMs: Int = 0,
    val denoise: Boolean = true,
    /** Làm rõ giọng (EQ rõ lời + cân mức tự động): cứu bản thu nhỏ / xa micro. */
    val enhance: Boolean = true,
    val tune: Boolean = false,
    val tuneStrength: Float = 0.85f,
    val tuneSpeed: Float = 0.6f,
    val tuneChromatic: Boolean = false,
    val reverb: Float = 0.22f,
    val comp: Float = 0.6f,
    val bright: Float = 0.4f,
    val gain: Float = 1.0f,
    /** Các đoạn sau khi cắt; rỗng = chưa cắt (phát nguyên bản thu theo [startMs]). */
    val segs: List<VocalSeg> = emptyList(),
) {
    /** Các đoạn thực sự được phát (chưa cắt thì là một đoạn duy nhất). */
    fun segments(): List<VocalSeg> =
        if (segs.isNotEmpty()) segs else if (playMs > 0) listOf(VocalSeg(0, trimMs, playMs, offsetMs)) else emptyList()

    /** Vị trí trong bản thu ứng với giây 0 của bài, đã tính cả dịch. */
    val startMs: Int get() = syncMs + nudgeMs
    /** Số ms đầu bản thu bị cắt khi nạp vào engine. */
    val trimMs: Int get() = maxOf(0, startMs)
    /** Độ trễ (ms) trước khi giọng bắt đầu trong bài (khi bản thu bắt đầu SAU giây 0). */
    val offsetMs: Int get() = maxOf(0, -startMs)
    /** Độ dài phát trong engine (ms). */
    val playMs: Int get() = maxOf(0, lengthMs - trimMs)
    /** Bài cần bao nhiêu ô nhịp để phát hết giọng ở [bpm]. */
    fun bars(bpm: Int): Int = Math.ceil((segments().maxOfOrNull { it.endMs } ?: 0) / 1000.0 * bpm / 240.0).toInt()
}

/** Tệp âm thanh nhập vào (WAV mono đã giải mã trong thư mục audio của dự án). [srcBpm] > 0: co/giãn theo BPM bài. */
data class AudioSource(
    val id: Int,
    val name: String,
    val file: String,
    val sampleRate: Int,
    val lengthMs: Int,
    val srcBpm: Int = 0,
    /** Ô mẫu trong engine (301..383). */
    val slot: Int = VOCAL_SLOT + 1,
) {
    /** Hệ số độ dài khi chuyển sang BPM [bpm] (srcBpm / bpm; 1 nếu không biết BPM gốc). */
    fun ratio(bpm: Int): Double = if (srcBpm > 0) (srcBpm.toDouble() / bpm).coerceIn(0.5, 2.0) else 1.0
}

const val MAX_AUDIO_CLIPS = 32
const val FIRST_AUDIO_SLOT = VOCAL_SLOT + 1
const val LAST_AUDIO_SLOT = 383

/** Một đoạn của tệp nhập vào đặt trên timeline: bắt đầu ở ô [startBar], lấy [srcLenMs] từ [srcStartMs] của tệp gốc. */
data class AudioClip(
    val id: Int,
    val sourceId: Int,
    val startBar: Int,
    val srcStartMs: Int,
    val srcLenMs: Int,
    val gain: Float = 1.0f,
    /** Nhãn phần bài: Intro, Verse, Pre-chorus, Hook, Bridge, Outro (rỗng = không nhãn). */
    val label: String = "",
) {
    fun playMs(src: AudioSource, bpm: Int): Int = (srcLenMs * src.ratio(bpm)).toInt()
    fun startMs(bpm: Int): Int = (startBar * 240000.0 / bpm).toInt()
    fun bars(src: AudioSource, bpm: Int): Int = Math.ceil(playMs(src, bpm) / 1000.0 * bpm / 240.0).toInt().coerceAtLeast(1)
    fun endBar(src: AudioSource, bpm: Int): Int = startBar + bars(src, bpm)
}

val SECTION_LABELS = listOf("", "Intro", "Verse", "Pre-chorus", "Hook", "Bridge", "Outro")

data class Track(
    val id: Int,
    val name: String,
    val instrument: Instrument,
    val volume: Float = 0.8f,
    val pan: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
    val clips: List<Clip> = emptyList(),
    /** Chỉ track Looper: thể loại đang chọn và 20 pad (4 lựa chọn x 5 hàng). Pitch của nốt = chỉ số pad. */
    val genreId: String = "",
    val kit: List<PadSpec> = emptyList(),
    /** Chỉ track Looper: tông chung (0=C..11=B) cho bass và hợp âm Keyboard. */
    val keyRoot: Int = DEFAULT_KEY,
    /** Chỉ track Vocal: bản thu. */
    val vocal: VocalClip? = null,
    /** Chỉ track Audio: các đoạn nhạc nhập vào. */
    val audio: List<AudioClip> = emptyList(),
    /** Track do "Tạo beat" sinh ra (được thay khi tạo lại, không đụng tới track của người dùng). */
    val auto: Boolean = false,
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
    /** Tông chung của bài (từ giọng hát hoặc người dùng chọn): dùng cho auto-tune và "Tạo beat". */
    val keyRoot: Int = DEFAULT_KEY,
    val minor: Boolean = false,
    /** Thể loại dùng cho "Tạo beat". */
    val arrangeGenre: String = "hiphop",
    /** true: nhạc cụ đánh theo nốt giọng hát (melodic/pop); false: chỉ đệm hợp âm (rap). */
    val followMelody: Boolean = false,
    /** Các tệp âm thanh nhập vào (dùng chung giữa các clip). */
    val sources: List<AudioSource> = emptyList(),
) {
    fun source(id: Int): AudioSource? = sources.firstOrNull { it.id == id }
    fun audioEndBar(t: Track): Int = t.audio.maxOfOrNull { c -> source(c.sourceId)?.let { c.endBar(it, bpm) } ?: 0 } ?: 0

    /** Ô nhịp cuối cùng có nhạc hoặc giọng (0 nếu chưa có gì). */
    val endBar: Int get() = tracks.maxOfOrNull { t -> maxOf(t.clips.maxOfOrNull { it.endBar } ?: 0, t.vocal?.bars(bpm) ?: 0, audioEndBar(t)) } ?: 0

    fun track(id: Int): Track? = tracks.firstOrNull { it.id == id }
    fun trackIndex(id: Int): Int = tracks.indexOfFirst { it.id == id }
}

/**
 * Đóng gói bài nhạc thành mảng int cho engine C++ (xem decodeSong trong engine_core.h):
 * [3, loop, loopStart, loopEnd, length, trackCount, rồi mỗi track: inst, kitCount, kitCount * 18 số của Patch,
 * padCount, mỗi pad: len, hitCount, hitCount * (start, vel, kitIdx, dur), noteCount, noteCount * (start, dur, pitch, vel)].
 * kit và pad chỉ có ở track Looper (các track khác = 0). [launcherTrackId] != null: chế độ lưới Looper, chỉ nhịp pad
 * của track đó phát, mọi track khác (và clip của chính nó) bị bỏ nốt nhưng giữ nguyên chỉ số track.
 * Nốt của mọi clip được trải ra tick tuyệt đối trong bài; nốt vượt quá độ dài clip bị bỏ / cắt.
 */
fun Project.encodeForEngine(loop: Boolean, loopStart: Int, loopEnd: Int, length: Int, launcherTrackId: Int? = null): IntArray {
    val used = tracks.take(MAX_TRACKS)
    val out = ArrayList<Int>()
    out.add(3)
    out.add(if (loop) 1 else 0)
    out.add(loopStart)
    out.add(loopEnd)
    out.add(length)
    out.add(used.size)
    for (t in used) {
        val flat = ArrayList<Int>()
        var count = 0
        val vocal = if (launcherTrackId == null && t.instrument.isVocal) t.vocal else null
        if (vocal != null) {  // giọng hát: mỗi đoạn = (bắt đầu ms, dài ms, ô mẫu, độ to, điểm vào ms trong bản thu)
            for (sg in vocal.segments().take(MAX_AUDIO_CLIPS)) {
                if (sg.lenMs <= 0) continue
                flat.add(sg.atMs); flat.add(sg.lenMs); flat.add(VOCAL_SLOT)
                flat.add((vocal.gain * 100f).toInt().coerceIn(1, 127))
                flat.add(sg.srcStartMs.coerceAtLeast(0))
                count++
            }
        }
        if (launcherTrackId == null && t.instrument.isAudio) {  // mỗi đoạn: (bắt đầu ms, dài ms, ô mẫu, độ to, điểm vào ms)
            for (c in t.audio.take(MAX_AUDIO_CLIPS)) {
                val src = source(c.sourceId) ?: continue
                val play = c.playMs(src, bpm)
                if (play <= 0) continue
                flat.add(c.startMs(bpm)); flat.add(play); flat.add(src.slot)
                flat.add((c.gain * 100f).toInt().coerceIn(1, 127))
                flat.add((c.srcStartMs * src.ratio(bpm)).toInt())
                count++
            }
        }
        for (c in if (launcherTrackId != null) emptyList() else t.clips) {
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
        out.add(t.instrument.engineId)
        val kit = if (t.instrument.isLooper) t.kit.take(KIT_COUNT) else emptyList()
        // Kit gửi sang engine = 20 âm + 12 âm bass trượt (nốt trượt của hàng Bass trỏ tới các ô 20..31).
        val slides = if (kit.size == KIT_COUNT) Catalog.slidePatches(kit) else emptyList()
        out.add(kit.size + slides.size)
        kit.forEachIndexed { i, pad -> Catalog.patchFor(t.genreId, i, pad).encodeTo(out) }
        slides.forEach { it.encodeTo(out) }
        if (t.instrument.isLooper && kit.isNotEmpty()) {
            val genre = Catalog.genre(t.genreId)
            out.add(PAD_COUNT)
            for (pad in 0 until PAD_COUNT) {
                val hits = if (genre == null) emptyList() else Patterns.padHits(genre, t.keyRoot, pad)
                out.add(Patterns.lenTicks(pad))
                out.add(hits.size)
                for (h in hits) { out.add(h.tick); out.add(h.vel); out.add(h.kitIdx); out.add(h.dur) }
            }
        } else {
            out.add(0)
        }
        out.add(count)
        out.addAll(flat)
    }
    return out.toIntArray()
}
