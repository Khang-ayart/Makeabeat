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

    private val minorDegrees = listOf(Patterns.Chord(0, true), Patterns.Chord(3, false), Patterns.Chord(5, true), Patterns.Chord(7, false), Patterns.Chord(8, false), Patterns.Chord(10, false))
    private val majorDegrees = listOf(Patterns.Chord(0, false), Patterns.Chord(2, true), Patterns.Chord(4, true), Patterns.Chord(5, false), Patterns.Chord(7, false), Patterns.Chord(9, true))

    /**
     * Hợp âm của từng ô nhịp, chọn theo giai điệu giọng: hợp âm trong thang của tông chứa nhiều nốt (dài / to) của ô đó nhất,
     * ưu tiên giữ nguyên hợp âm cũ để không đổi liên tục. Ô không có nốt: giữ hợp âm trước (hoặc vòng mặc định nếu chưa có).
     */
    fun chordsFromMelody(melody: List<NoteEvent>, bars: Int, key: Int, minor: Boolean): List<Patterns.Chord> {
        val fallback = Patterns.progression(minor, 0)
        val degrees = if (minor) minorDegrees else majorDegrees
        val out = ArrayList<Patterns.Chord>()
        var prev: Patterns.Chord? = null
        for (bar in 0 until bars) {
            val from = bar * BAR_TICKS
            val to = from + BAR_TICKS
            val w = DoubleArray(12)
            for (n in melody) {
                val s = max(n.start, from)
                val e = min(n.start + n.dur, to)
                if (e <= s) continue
                w[Math.floorMod(n.pitch - key, 12)] += (e - s).toDouble() * (if (n.start in from until from + PPQ) 1.5 else 1.0)
            }
            val total = w.sum()
            if (total <= 0.0) {
                val c = prev ?: fallback[bar % fallback.size]
                out.add(c); prev = c
                continue
            }
            var best = degrees[0]
            var bestScore = -1e18
            for (c in degrees) {
                val iv = c.intervals
                val tones = setOf(c.root % 12, (c.root + iv[1]) % 12, (c.root + iv[2]) % 12)
                var sc = w[c.root % 12] * 1.0 + w[(c.root + iv[1]) % 12] * 0.8 + w[(c.root + iv[2]) % 12] * 0.7
                for (pc in 0 until 12) if (pc !in tones) sc -= w[pc] * 0.3
                if (c.root == 0 || c.root == 5 || c.root == 7 || c.root == 9 || c.root == 8) sc += total * 0.05  // hợp âm quen thuộc
                if (prev != null && c.root == prev.root && c.minor == prev.minor) sc += total * 0.15
                if (sc > bestScore) { bestScore = sc; best = c }
            }
            out.add(best); prev = best
        }
        return out
    }

    /**
     * Độ hoạt động của giọng theo từng ô nhịp (0..1, so với ô to nhất): dùng để nhạc cụ nhẹ và thưa đi khi giọng đang hát.
     * [startMs] là vị trí (ms trong bản thu) của giây 0 của bài, như [melodyFromPitch].
     */
    fun barActivity(samples: FloatArray, sampleRate: Int, startMs: Int, bpm: Int, bars: Int): List<Float> {
        if (samples.isEmpty() || sampleRate <= 0 || bpm <= 0 || bars <= 0) return List(max(bars, 0)) { 0f }
        val barSec = 240.0 / bpm
        val rms = DoubleArray(bars)
        for (b in 0 until bars) {
            val a = ((b * barSec + startMs / 1000.0) * sampleRate).toLong().coerceIn(0L, samples.size.toLong()).toInt()
            val e = (((b + 1) * barSec + startMs / 1000.0) * sampleRate).toLong().coerceIn(0L, samples.size.toLong()).toInt()
            if (e - a < 16) continue
            var sum = 0.0
            for (i in a until e) sum += samples[i].toDouble() * samples[i]
            rms[b] = Math.sqrt(sum / (e - a))
        }
        val ref = rms.filter { it > 1e-4 }.sorted().let { if (it.isEmpty()) 0.0 else it[(it.size * 9 / 10).coerceAtMost(it.size - 1)] }
        if (ref <= 0.0) return List(bars) { 0f }
        return rms.map { (it / ref).coerceIn(0.0, 1.0).toFloat() }
    }

    /** Hàng pad nhạc (bass, keyboard, lead, pad): khi nhạc cụ khác đã đánh theo giai điệu thì Looper chỉ giữ trống / bộ gõ. */
    private fun isMelodicRow(pad: Int) = pad / PADS_PER_ROW >= 4

    /**
     * Dựng các track tự động vào [p] (thay các track tự động cũ, giữ nguyên track của người dùng). [melody]: nốt của giọng hát
     * (tick tuyệt đối) dùng khi [Project.followMelody]: hợp âm của piano / guitar / bass bám theo giai điệu đó, còn nhạc cụ chỉ
     * đệm nhẹ (không đánh đè lên giọng). [activity]: độ hoạt động của giọng theo ô nhịp ([barActivity]); rỗng = coi như vừa phải.
     */
    fun arrange(p: Project, genre: Genre, vocalBars: Int, melody: List<NoteEvent> = emptyList(), activity: List<Float> = emptyList()): Project {
        val key = Math.floorMod(p.keyRoot, 12)
        val minor = p.minor
        val total = totalBars(vocalBars)
        val blocks = total / BLOCK_BARS
        val secs = sections(blocks)
        val follow = p.followMelody && melody.isNotEmpty()
        val fixed = Patterns.progression(minor, 0)  // 4 hợp âm, mỗi ô nhịp một hợp âm (khi không có giai điệu)
        val chords = if (follow) chordsFromMelody(melody, total, key, minor) else List(total) { fixed[it % fixed.size] }
        fun act(bar: Int) = activity.getOrElse(bar) { 0.5f }
        /** Độ mạnh nốt: nhỏ đi tới 35% khi giọng đang hát to. */
        fun vel(v: Int, bar: Int) = (v * (1f - 0.35f * act(bar))).roundToInt().coerceIn(20, 127)

        var nextId = p.nextId
        fun nid() = nextId++

        val looperClips = ArrayList<Clip>()
        val pianoClips = ArrayList<Clip>()
        val guitarClips = ArrayList<Clip>()
        val padClips = ArrayList<Clip>()
        val bassClips = ArrayList<Clip>()
        for (b in 0 until blocks) {
            val bar = b * BLOCK_BARS
            val sec = secs[b]
            // Looper: bám giai điệu thì chỉ giữ trống / bộ gõ (bass, hợp âm do các track riêng đánh theo giọng)
            val pads = padsFor(sec, follow).filter { !follow || !isMelodicRow(it) }
            val loopNotes = pads.flatMap { pad -> clipNotes(Patterns.padHitsIn(genre, key, minor, pad)) }
            if (loopNotes.isNotEmpty()) looperClips.add(Clip(nid(), bar, BLOCK_BARS, loopNotes.sortedBy { it.start }))
            // Piano: đệm nhẹ theo hợp âm của giọng. Chorus có thêm giai điệu nhấn khẽ lên một quãng tám.
            val pn = ArrayList<NoteEvent>()
            for (i in 0 until BLOCK_BARS) {
                val gb = bar + i
                val notes = Patterns.chordNotes(key, chords[gb.coerceAtMost(chords.size - 1)])
                val base = if (sec == Section.CHORUS) 62 else 50
                val stabs = if (sec == Section.OUTRO || sec == Section.INTRO) listOf(0) else if (sec == Section.CHORUS) listOf(0, 1920) else listOf(0)
                for (off in stabs) for ((k, n) in notes.withIndex()) pn.add(NoteEvent(i * BAR_TICKS + off + k * 8, if (off == 0) 1800 else 1500, n, vel(base, gb) - k * 2))
            }
            if (follow && sec == Section.CHORUS) {
                for (n in split(melody, bar * BAR_TICKS, (bar + BLOCK_BARS) * BAR_TICKS)) {
                    val pitch = if (n.pitch + 12 <= 96) n.pitch + 12 else n.pitch
                    pn.add(n.copy(pitch = pitch, vel = vel(46, bar + n.start / BAR_TICKS)))
                }
            }
            if (pn.isNotEmpty()) pianoClips.add(Clip(nid(), bar, BLOCK_BARS, pn.sortedBy { it.start }))
            // Guitar: chỉ quạt khi giọng thưa (verse) hoặc nhẹ hơn (chorus); không có ở intro / outro
            if (sec == Section.VERSE || sec == Section.CHORUS) {
                val gn = ArrayList<NoteEvent>()
                for (i in 0 until BLOCK_BARS) {
                    val gb = bar + i
                    if (sec == Section.VERSE && act(gb) > 0.6f && i % 2 == 1) continue  // chừa chỗ cho lời
                    val chord = Patterns.chordNotes(key, chords[gb.coerceAtMost(chords.size - 1)])
                    val tones = listOf(if (chord[0] - 12 >= 40) chord[0] - 12 else chord[0]) + chord
                    val strums = if (sec == Section.CHORUS) listOf(0 to 70, 1920 to 58) else listOf(0 to 56)
                    for ((off, v) in strums) {
                        tones.forEachIndexed { s, n -> gn.add(NoteEvent(i * BAR_TICKS + off + s * 36, 900, n, vel(v, gb) - s * 3)) }
                    }
                }
                if (gn.isNotEmpty()) guitarClips.add(Clip(nid(), bar, BLOCK_BARS, gn.sortedBy { it.start }))
            }
            // Bass đánh gốc hợp âm của giọng (chỉ khi bám giai điệu; rap thì dùng bass của Looper)
            if (follow && sec != Section.INTRO) {
                val bn = ArrayList<NoteEvent>()
                for (i in 0 until BLOCK_BARS) {
                    val gb = bar + i
                    val c = chords[gb.coerceAtMost(chords.size - 1)]
                    var m = 24 + Math.floorMod(key + c.root, 12)
                    if (m < 31) m += 12
                    bn.add(NoteEvent(i * BAR_TICKS, 1800, m, vel(88, gb)))
                    bn.add(NoteEvent(i * BAR_TICKS + 1920, if (sec == Section.CHORUS) 900 else 1700, m, vel(76, gb)))
                    if (sec == Section.CHORUS) bn.add(NoteEvent(i * BAR_TICKS + 3360, 360, m, vel(70, gb)))
                }
                bassClips.add(Clip(nid(), bar, BLOCK_BARS, bn))
            }
            // Drum pad: clap phách 2 và 4 + crash đầu khối ở chorus
            if (sec == Section.CHORUS) {
                val dn = ArrayList<NoteEvent>()
                dn.add(NoteEvent(0, 960, 7, 90))  // kCrash
                for (i in 0 until BLOCK_BARS) {
                    dn.add(NoteEvent(i * BAR_TICKS + 960, STEP_TICKS, 4, 80))        // kClap
                    dn.add(NoteEvent(i * BAR_TICKS + 2880, STEP_TICKS, 4, 80))
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
        // Âm lượng thấp hơn giọng (giọng ở 0,9-1,0) để nhạc nằm dưới lời.
        add(Instrument.LOOPER, "Looper ${genre.label}", 0.62f, 0f, looperClips, looper = true)
        add(Instrument.PIANO, if (follow) "Piano theo giọng" else "Piano đệm", 0.45f, -0.15f, pianoClips)
        add(Instrument.ACOUSTIC, "Guitar", 0.36f, 0.25f, guitarClips)
        add(Instrument.BASS, "Bass theo giọng", 0.55f, 0f, bassClips)
        add(Instrument.DRUM_PAD, "Clap & crash", 0.4f, 0f, padClips)
        return p.copy(tracks = keep + made, nextId = nextId, arrangeGenre = genre.id)
    }
}
