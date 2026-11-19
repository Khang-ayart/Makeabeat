package com.bandstudio.app.model

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Tạo beat": có giọng hát + BPM + tông + thể loại thì dựng sẵn các track (Looper, Piano, Guitar, Drum Pad) theo từng đoạn
 * (intro, verse, chorus, outro) để người dùng nghe ngay rồi chỉnh. Thuần dữ liệu, không đụng tới engine.
 */
object Arranger {

    enum class Section { INTRO, VERSE, CHORUS, OUTRO }

    const val BLOCK_BARS = 4
    private const val AUTO_PREFIX = "Tự động"

    /** Số ô nhịp của bài: đủ cho giọng + 1 khối outro, làm tròn lên bội của 4, trong [8, MAX_BARS]. */
    fun totalBars(vocalBars: Int): Int {
        val need = max(vocalBars, 4) + BLOCK_BARS
        val rounded = (need + BLOCK_BARS - 1) / BLOCK_BARS * BLOCK_BARS
        return rounded.coerceIn(8, MAX_BARS)
    }

    /** Đoạn của từng khối 4 ô nhịp: intro, rồi verse verse chorus chorus lặp lại, outro ở khối cuối (nếu đủ dài). */
    fun sections(blocks: Int): List<Section> = List(blocks) { b ->
        when {
            blocks >= 3 && b == 0 -> Section.INTRO
            blocks >= 3 && b == blocks - 1 -> Section.OUTRO
            (b - 1).coerceAtLeast(0) % 4 < 2 -> Section.VERSE
            else -> Section.CHORUS
        }
    }

    /** Pad (hàng * 4 + cột) được bật trong từng đoạn. Hàng: 0 kick, 1 snare, 2 hi-hat, 3 perc, 4 bass, 5 keyboard, 6 lead, 7 pad. */
    fun padsFor(section: Section, followMelody: Boolean): List<Int> = when (section) {
        Section.INTRO -> listOf(2 * 4 + 0, 5 * 4 + 0, 7 * 4 + 0)
        Section.VERSE -> buildList {
            addAll(listOf(0 * 4 + 0, 1 * 4 + 0, 2 * 4 + 0, 4 * 4 + 0, 7 * 4 + 0))
            if (!followMelody) add(5 * 4 + 0)  // rap: hợp âm đệm; melodic: để piano đánh theo giọng
        }
        Section.CHORUS -> listOf(0 * 4 + 1, 1 * 4 + 1, 2 * 4 + 1, 3 * 4 + 0, 4 * 4 + 1, 5 * 4 + 1, 6 * 4 + 0, 7 * 4 + 0)
        Section.OUTRO -> listOf(5 * 4 + 0, 7 * 4 + 0)
    }

    private fun clipNotes(hits: List<Patterns.Hit>): List<NoteEvent> =
        hits.filter { it.tick in 0 until Patterns.lenTicks(0) }.map { NoteEvent(it.tick, it.dur.coerceAtLeast(1), it.kitIdx, it.vel) }

    /**
     * Chuyển cao độ (Hz, từng khung, 0 = không có) thành nốt: nốt MIDI gần nhất thuộc thang của tông, gộp các khung cùng nốt
     * (bỏ qua khoảng hở ngắn), bỏ nốt quá ngắn, căn vào lưới 1/16. Trả nốt theo tick tuyệt đối của bài (start >= 0).
     * [startMs]: vị trí (ms trong bản thu) của giây 0 của bài.
     */
    fun melodyFromPitch(hz: FloatArray, hopSamples: Int, sampleRate: Int, startMs: Int, bpm: Int, keyRoot: Int, minor: Boolean): List<NoteEvent> {
        if (hz.isEmpty() || hopSamples <= 0 || sampleRate <= 0) return emptyList()
        val scale = if (minor) intArrayOf(0, 2, 3, 5, 7, 8, 10) else intArrayOf(0, 2, 4, 5, 7, 9, 11)
        fun snap(m: Int): Int {
            var best = m
            var bd = Int.MAX_VALUE
            for (d in -2..2) {
                val c = m + d
                val rel = Math.floorMod(c - keyRoot, 12)
                if (rel in scale && Math.abs(d) < bd) { bd = Math.abs(d); best = c }
            }
            return best
        }
        val frameSec = hopSamples.toDouble() / sampleRate
        val tickPerSec = bpm / 60.0 * PPQ
        val notes = ArrayList<NoteEvent>()
        var curNote = -1
        var curStart = 0.0
        var lastVoiced = 0.0
        var gap = 0
        fun flush() {
            if (curNote < 0) return
            val s = curStart
            val e = lastVoiced + frameSec
            if (e - s >= 0.06) {
                var st = (s * tickPerSec / STEP_TICKS).roundToInt() * STEP_TICKS
                var en = (e * tickPerSec / STEP_TICKS).roundToInt() * STEP_TICKS
                val prevEnd = notes.lastOrNull()?.let { it.start + it.dur } ?: 0
                if (st < prevEnd) st = prevEnd
                if (en < st + STEP_TICKS) en = st + STEP_TICKS
                if (st >= 0) {
                    var pitch = curNote
                    while (pitch < 48) pitch += 12  // giọng trầm: đàn đánh lên một quãng tám cho khỏi đục
                    while (pitch > 88) pitch -= 12
                    notes.add(NoteEvent(st, en - st, pitch, 90))
                }
            }
            curNote = -1
        }
        for (k in hz.indices) {
            val t = (k + 0.5) * frameSec - startMs / 1000.0   // thời gian theo bài
            val f = hz[k]
            if (f <= 0f || t < 0.0) {
                if (curNote >= 0 && ++gap > 2) flush()
                continue
            }
            val m = snap((69.0 + 12.0 * ln(f / 440.0) / ln(2.0)).roundToInt())
            if (curNote >= 0 && m == curNote) {
                lastVoiced = t
                gap = 0
            } else {
                flush()
                curNote = m
                curStart = t - frameSec / 2
                lastVoiced = t
                gap = 0
            }
        }
        flush()
        return notes
    }

    private fun split(notes: List<NoteEvent>, fromTick: Int, toTick: Int): List<NoteEvent> =
        notes.filter { it.start < toTick && it.start + it.dur > fromTick }.map {
            val s = max(it.start, fromTick)
            val e = min(it.start + it.dur, toTick)
            it.copy(start = s - fromTick, dur = max(1, e - s))
        }

    /**
     * Dựng các track tự động vào [p] (thay các track tự động cũ, giữ nguyên track của người dùng). [melody]: nốt của giọng hát
     * (tick tuyệt đối) dùng khi [Project.followMelody]; rỗng thì piano chỉ đệm hợp âm.
     */
    fun arrange(p: Project, genre: Genre, vocalBars: Int, melody: List<NoteEvent> = emptyList()): Project {
        val key = Math.floorMod(p.keyRoot, 12)
        val minor = p.minor
        val total = totalBars(vocalBars)
        val blocks = total / BLOCK_BARS
        val secs = sections(blocks)
        val follow = p.followMelody && melody.isNotEmpty()
        val prog = Patterns.progression(minor, 0)  // 4 hợp âm, mỗi ô nhịp một hợp âm

        var nextId = p.nextId
        fun nid() = nextId++

        val looperClips = ArrayList<Clip>()
        val pianoClips = ArrayList<Clip>()
        val guitarClips = ArrayList<Clip>()
        val padClips = ArrayList<Clip>()
        for (b in 0 until blocks) {
            val bar = b * BLOCK_BARS
            val sec = secs[b]
            // Looper
            val loopNotes = padsFor(sec, follow).flatMap { pad -> clipNotes(Patterns.padHitsIn(genre, key, minor, pad)) }
            if (loopNotes.isNotEmpty()) looperClips.add(Clip(nid(), bar, BLOCK_BARS, loopNotes.sortedBy { it.start }))
            // Piano
            val pn = ArrayList<NoteEvent>()
            if (follow && sec != Section.OUTRO) {
                pn.addAll(split(melody, bar * BAR_TICKS, (bar + BLOCK_BARS) * BAR_TICKS))
                for (i in 0 until BLOCK_BARS) {  // tay trái: nốt gốc của hợp âm, nhẹ
                    val c = prog[i % prog.size]
                    pn.add(NoteEvent(i * BAR_TICKS, BAR_TICKS - 60, Patterns.chordNotes(key, c)[0] - 12, 58))
                }
            } else if (!follow && sec != Section.INTRO) {
                val vel = if (sec == Section.CHORUS) 84 else 68
                for (i in 0 until BLOCK_BARS) {
                    val c = prog[i % prog.size]
                    for (n in Patterns.chordNotes(key, c)) pn.add(NoteEvent(i * BAR_TICKS, BAR_TICKS - 60, n, vel))
                }
            }
            if (pn.isNotEmpty()) pianoClips.add(Clip(nid(), bar, BLOCK_BARS, pn.sortedBy { it.start }))
            // Guitar: quạt hợp âm (verse nhẹ 1 lần/ô nhịp, chorus 2 lần + quạt lên); không có ở intro/outro
            if (sec == Section.VERSE || sec == Section.CHORUS) {
                val gn = ArrayList<NoteEvent>()
                for (i in 0 until BLOCK_BARS) {
                    val c = prog[i % prog.size]
                    val chord = Patterns.chordNotes(key, c)
                    val tones = listOf(if (chord[0] - 12 >= 40) chord[0] - 12 else chord[0]) + chord  // nốt gốc thấp (>= E2) + hợp âm
                    val strums = if (sec == Section.CHORUS) listOf(0 to 92, 1920 to 80, 2880 to 70) else listOf(0 to 74)
                    for ((off, vel) in strums) {
                        tones.forEachIndexed { s, n -> gn.add(NoteEvent(i * BAR_TICKS + off + s * 36, 900, n, vel - s * 3)) }
                    }
                }
                guitarClips.add(Clip(nid(), bar, BLOCK_BARS, gn.sortedBy { it.start }))
            }
            // Drum pad: clap phách 2 và 4 + crash đầu khối ở chorus
            if (sec == Section.CHORUS) {
                val dn = ArrayList<NoteEvent>()
                dn.add(NoteEvent(0, 960, 7, 96))  // kCrash
                for (i in 0 until BLOCK_BARS) {
                    dn.add(NoteEvent(i * BAR_TICKS + 960, STEP_TICKS, 4, 88))        // kClap
                    dn.add(NoteEvent(i * BAR_TICKS + 2880, STEP_TICKS, 4, 88))
                }
                padClips.add(Clip(nid(), bar, BLOCK_BARS, dn.sortedBy { it.start }))
            }
        }

        val keep = p.tracks.filter { !it.auto }
        val room = MAX_TRACKS - keep.size
        val made = ArrayList<Track>()
        fun add(inst: Instrument, name: String, vol: Float, pan: Float, clips: List<Clip>, looper: Boolean = false) {
            if (clips.isEmpty() || made.size >= room) return
            made.add(
                Track(
                    id = nid(), name = "$AUTO_PREFIX · $name", instrument = inst, volume = vol, pan = pan, clips = clips,
                    genreId = if (looper) genre.id else "", kit = if (looper) genre.kit(key) else emptyList(),
                    keyRoot = key, auto = true,
                ),
            )
        }
        add(Instrument.LOOPER, "Looper ${genre.label}", 0.8f, 0f, looperClips, looper = true)
        add(Instrument.PIANO, if (follow) "Piano theo giọng" else "Piano đệm", 0.65f, -0.15f, pianoClips)
        add(Instrument.ACOUSTIC, "Guitar", 0.55f, 0.25f, guitarClips)
        add(Instrument.DRUM_PAD, "Clap & crash", 0.5f, 0f, padClips)
        return p.copy(tracks = keep + made, nextId = nextId, arrangeGenre = genre.id)
    }
}
