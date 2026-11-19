package com.bandstudio.app.model

import java.util.Random
import kotlin.math.abs
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
     * Đặc điểm của giọng theo từng ô nhịp, để nhạc "nghe" được giọng: [activity] độ to (0..1), [pitchClasses] histogram 12 cao độ
     * (tuyệt đối, C = 0) từ các khung có cao độ, [onsets] mặt nạ 16 bit: bước 1/16 nào có âm tiết / nhấn mới bắt đầu.
     */
    class VocalProfile(val activity: List<Float>, val pitchClasses: List<DoubleArray>, val onsets: List<Int>) {
        val bars: Int get() = activity.size
        /** Tổng "lượng cao độ" đủ để tin vào hợp âm suy ra từ giọng. */
        fun hasPitch(): Boolean = pitchClasses.sumOf { it.sum() } > 30.0
        /** Dấu vân tay nhỏ của giọng: trộn vào hạt giống để hai giọng khác nhau ra hai bản phối khác nhau. */
        fun fingerprint(): Long {
            var h = 1469598103934665603L
            for (o in onsets) h = (h xor o.toLong()) * 1099511628211L
            for (a in activity) h = (h xor (a * 100).toLong()) * 1099511628211L
            return h
        }
    }

    /** Dựng [VocalProfile] từ bản thu (mẫu mono) và cao độ từng khung [hz] (0 = không có; có thể rỗng). [startMs]: như [melodyFromPitch]. */
    fun vocalProfile(samples: FloatArray, sampleRate: Int, startMs: Int, bpm: Int, bars: Int, hz: FloatArray, hopSamples: Int): VocalProfile {
        val act = barActivity(samples, sampleRate, startMs, bpm, bars)
        val pcs = List(bars) { DoubleArray(12) }
        val barSec = 240.0 / bpm
        if (hz.isNotEmpty() && hopSamples > 0 && sampleRate > 0) {
            val frameSec = hopSamples.toDouble() / sampleRate
            for (k in hz.indices) {
                val f = hz[k]
                if (f <= 0f) continue
                val t = (k + 0.5) * frameSec - startMs / 1000.0
                if (t < 0.0) continue
                val bar = (t / barSec).toInt()
                if (bar >= bars) break
                val m = (69.0 + 12.0 * ln(f / 440.0) / ln(2.0)).roundToInt()
                pcs[bar][Math.floorMod(m, 12)] += 1.0
            }
        }
        return VocalProfile(act, pcs, barOnsets(samples, sampleRate, startMs, bpm, bars))
    }

    /**
     * Bước 1/16 nào trong mỗi ô nhịp có âm tiết mới bắt đầu: đường bao năng lượng 5 ms, một "onset" là chỗ năng lượng vọt lên
     * hơn 2 lần mức trung bình 60 ms trước đó (và trên nền ồn). Mỗi ô một mặt nạ 16 bit (bit s = bước s).
     */
    fun barOnsets(samples: FloatArray, sampleRate: Int, startMs: Int, bpm: Int, bars: Int): List<Int> {
        val out = IntArray(max(bars, 0))
        if (samples.isEmpty() || sampleRate <= 0 || bpm <= 0 || bars <= 0) return out.toList()
        val hop = max(1, sampleRate / 200)  // 5 ms
        val n = samples.size / hop
        val env = DoubleArray(n)
        var peak = 0.0
        for (f in 0 until n) {
            var sum = 0.0
            for (i in f * hop until (f + 1) * hop) sum += samples[i].toDouble() * samples[i]
            env[f] = Math.sqrt(sum / hop)
            if (env[f] > peak) peak = env[f]
        }
        if (peak < 1e-4) return out.toList()
        val floor = peak * 0.08
        val back = 12  // 60 ms
        var lastOnset = -100
        val barSec = 240.0 / bpm
        val stepSec = barSec / 16.0
        for (f in 0 until n) {
            if (env[f] < floor) continue
            val lo = max(0, f - back)
            var avg = 0.0
            for (j in lo until f) avg += env[j]
            if (f > lo) avg /= (f - lo)
            if (env[f] > 2.0 * avg + floor * 0.3 && f - lastOnset >= 10) {  // >= 50 ms từ onset trước
                lastOnset = f
                val t = f * hop.toDouble() / sampleRate - startMs / 1000.0
                if (t < 0) continue
                val pos = t / stepSec
                val step = pos.roundToInt()
                val bar = step / 16
                if (bar in 0 until bars) out[bar] = out[bar] or (1 shl (step % 16))
            }
        }
        return out.toList()
    }

    /**
     * Hợp âm của từng ô nhịp từ histogram cao độ của giọng (tuyệt đối): chấm điểm hợp âm trong thang của tông như
     * [chordsFromMelody], thêm nhiễu theo hạt giống [rnd] (mỗi lần "Tạo beat" chọn khác đi nhưng vẫn hợp giọng), ưu tiên ở lại
     * hợp âm cũ. Ô gần như không có cao độ: đi tiếp theo vòng hợp âm số [fallbackProg] của thể loại.
     */
    fun chordsFromPitchClasses(pcs: List<DoubleArray>, bars: Int, key: Int, minor: Boolean, rnd: Random, fallbackProg: Int): List<Patterns.Chord> {
        val degrees = if (minor) minorDegrees else majorDegrees
        val fb = Patterns.progression(minor, fallbackProg)
        val out = ArrayList<Patterns.Chord>()
        var prev: Patterns.Chord? = null
        for (bar in 0 until bars) {
            val w = pcs.getOrNull(bar)
            val total = w?.sum() ?: 0.0
            if (w == null || total < 6.0) {
                val c = fb[bar % fb.size]
                out.add(c); prev = c
                continue
            }
            var best = degrees[0]
            var bestScore = -1e18
            for (c in degrees) {
                val iv = c.intervals
                val r = Math.floorMod(key + c.root, 12)
                val t3 = (r + iv[1]) % 12
                val t5 = (r + iv[2]) % 12
                var sc = w[r] * 1.0 + w[t3] * 0.8 + w[t5] * 0.7
                for (pc in 0 until 12) if (pc != r && pc != t3 && pc != t5) sc -= w[pc] * 0.3
                if (prev != null && c.root == prev.root && c.minor == prev.minor) sc += total * 0.1
                sc += (rnd.nextDouble() - 0.5) * total * 0.35
                if (sc > bestScore) { bestScore = sc; best = c }
            }
            out.add(best); prev = best
        }
        return out
    }

    private val pianoPatterns = listOf(
        "x...............", "x.......x.......", "x.....x.x.......", "x..x....x..x....", "..x.....x.....x.",
        "x.x...x...x.....", "x.......x.x.....", "...x......x.....", "x...x...x...x...", "x..x..x.........",
    )
    private val guitarPatterns = listOf(
        "x.......x.......", "x...x...x...x...", "x..x..x.x..x....", "..x...x...x...x.", "x.....x...x.....", "x.x.....x.x.....",
    )
    private val bassPatterns = listOf(
        "x.......x.......", "x.....x.x.......", "x..x....x..x....", "x.......x.x.....", "x.x.....x.......", "x.....x...x.....",
    )

    private fun steps(pattern: String): List<Int> = pattern.indices.filter { pattern[it] == 'x' }

    /**
     * Chọn mẫu nhịp cho khối 4 ô nhịp [bar]..[bar]+3: cú đánh tránh trùng bước có âm tiết của giọng khi giọng đang hát (chừa chỗ cho lời),
     * thích lấp vào chỗ giọng nghỉ khi giọng thưa, cộng nhiễu theo hạt giống để mỗi lần tạo ra một kiểu khác.
     */
    private fun pickPattern(patterns: List<String>, prof: VocalProfile?, bar: Int, rnd: Random, quietBias: Double): String {
        var best = patterns[0]
        var bestScore = -1e18
        for (pt in patterns) {
            val st = steps(pt)
            var sc = 0.0
            if (prof != null) for (i in 0 until BLOCK_BARS) {
                val gb = bar + i
                val on = prof.onsets.getOrElse(gb) { 0 }
                val a = prof.activity.getOrElse(gb) { 0.5f }.toDouble()
                for (s in st) {
                    val clash = ((on shr s) and 1) == 1 || ((on shr ((s + 15) % 16)) and 1) == 1
                    if (clash) sc -= 1.6 * a
                    else if (((on shr s) and 0xF) == 0) sc += 0.5 * (1.0 - a)
                    sc -= 0.25 * a  // giọng to: thưa đi
                }
            }
            sc -= abs(st.size - 3) * 0.1 * (1.0 - quietBias)
            sc += rnd.nextDouble() * 1.4
            if (sc > bestScore) { bestScore = sc; best = pt }
        }
        return best
    }

    /**
     * Dựng các track tự động vào [p] (thay các track tự động cũ, giữ nguyên track của người dùng). Mỗi lần gọi tăng
     * [Project.arrangeSeed] nên bấm "Tạo beat" nhiều lần ra các biến thể khác nhau (vòng hợp âm, mẫu nhịp piano / guitar / bass, hàng bộ gõ).
     * [profile] (từ [vocalProfile]) cho hợp âm bám cao độ giọng và mẫu nhịp né / lấp theo âm tiết của giọng.
     * [melody] (nốt giọng, tick tuyệt đối) dùng khi [Project.followMelody]: piano nhấn khẽ giai điệu ở chorus, looper chỉ giữ trống.
     * [activity] (khi không có [profile]): độ to của giọng theo ô; rỗng = coi như vừa phải.
     */
    fun arrange(
        p: Project, genre: Genre, vocalBars: Int, melody: List<NoteEvent> = emptyList(),
        activity: List<Float> = emptyList(), profile: VocalProfile? = null,
    ): Project {
        val key = Math.floorMod(p.keyRoot, 12)
        val minor = p.minor
        val total = totalBars(vocalBars)
        val blocks = total / BLOCK_BARS
        val secs = sections(blocks)
        val follow = p.followMelody && melody.isNotEmpty()
        val seed = p.arrangeSeed + 1
        val rnd = Random(seed * 7919L + (profile?.fingerprint() ?: 0L))
        val acts = profile?.activity ?: activity
        fun act(bar: Int) = acts.getOrElse(bar) { 0.5f }
        /** Độ mạnh nốt: nhỏ đi tới 35% khi giọng đang hát to. */
        fun vel(v: Int, bar: Int) = (v * (1f - 0.35f * act(bar))).roundToInt().coerceIn(20, 127)

        val chords = when {
            profile != null && profile.hasPitch() -> chordsFromPitchClasses(profile.pitchClasses, total, key, minor, rnd, rnd.nextInt(4))
            follow -> chordsFromMelody(melody, total, key, minor)
            else -> { val f = Patterns.progression(minor, rnd.nextInt(4)); List(total) { f[it % f.size] } }
        }
        fun chordAt(bar: Int) = chords[bar.coerceIn(0, chords.size - 1)]

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
            val blockAct = (0 until BLOCK_BARS).map { act(bar + it) }.average().toFloat()
            // Looper: hi-hat / bộ gõ đổi biến thể theo hạt giống; giọng đang hát dày thì bỏ hàng bộ gõ cho thoáng.
            // Bám giai điệu thì chỉ giữ trống / bộ gõ (bass, hợp âm do các track riêng đánh theo giọng).
            var pads = padsFor(sec, follow).filter { !follow || !isMelodicRow(it) }
            if (sec != Section.INTRO && sec != Section.OUTRO) {
                pads = pads.map { pd -> if (pd / PADS_PER_ROW == 2 || pd / PADS_PER_ROW == 3) (pd / PADS_PER_ROW) * PADS_PER_ROW + rnd.nextInt(PADS_PER_ROW) else pd }
                if (sec == Section.VERSE && blockAct > 0.7f) pads = pads.filter { it / PADS_PER_ROW != 3 }
            }
            val loopNotes = pads.distinct().flatMap { pad -> clipNotes(Patterns.padHitsIn(genre, key, minor, pad)) }
            if (loopNotes.isNotEmpty()) looperClips.add(Clip(nid(), bar, BLOCK_BARS, loopNotes.sortedBy { it.start }))

            // Piano: hợp âm theo giọng, mẫu nhịp chọn theo âm tiết của giọng. Chorus nhấn khẽ giai điệu lên một quãng tám.
            val pn = ArrayList<NoteEvent>()
            val pPat = if (sec == Section.INTRO || sec == Section.OUTRO) pianoPatterns[rnd.nextInt(2)]
            else pickPattern(pianoPatterns, profile, bar, rnd, if (sec == Section.CHORUS) 0.2 else 0.6)
            val pSteps = steps(pPat)
            for (i in 0 until BLOCK_BARS) {
                val gb = bar + i
                val notes = Patterns.chordNotes(key, chordAt(gb))
                val base = if (sec == Section.CHORUS) 62 else 50
                for ((si, st) in pSteps.withIndex()) {
                    val nextSt = pSteps.getOrNull(si + 1) ?: 16
                    val dur = ((nextSt - st).coerceAtMost(8)) * STEP_TICKS - 60
                    val accent = if (st == 0) 6 else 0
                    for ((k, n) in notes.withIndex()) pn.add(NoteEvent(i * BAR_TICKS + st * STEP_TICKS + k * 8, dur.coerceAtLeast(120), n, (vel(base, gb) + accent - k * 2).coerceIn(20, 127)))
                }
            }
            if (follow && sec == Section.CHORUS) {
                for (n in split(melody, bar * BAR_TICKS, (bar + BLOCK_BARS) * BAR_TICKS)) {
                    val pitch = if (n.pitch + 12 <= 96) n.pitch + 12 else n.pitch
                    pn.add(n.copy(pitch = pitch, vel = vel(46, bar + n.start / BAR_TICKS)))
                }
            }
            if (pn.isNotEmpty()) pianoClips.add(Clip(nid(), bar, BLOCK_BARS, pn.sortedBy { it.start }))

            // Guitar: quạt khi giọng thưa (verse) hoặc nhẹ hơn (chorus); không có ở intro / outro
            if (sec == Section.VERSE || sec == Section.CHORUS) {
                val gn = ArrayList<NoteEvent>()
                val gPat = pickPattern(guitarPatterns, profile, bar, rnd, if (sec == Section.CHORUS) 0.3 else 0.7)
                val gSteps = steps(gPat)
                for (i in 0 until BLOCK_BARS) {
                    val gb = bar + i
                    if (sec == Section.VERSE && act(gb) > 0.6f && i % 2 == 1) continue  // chừa chỗ cho lời
                    val chord = Patterns.chordNotes(key, chordAt(gb))
                    val tones = listOf(if (chord[0] - 12 >= 40) chord[0] - 12 else chord[0]) + chord
                    for (st in gSteps) {
                        val v = (if (sec == Section.CHORUS) 66 else 54) + (if (st % 4 == 0) 4 else -2)
                        tones.forEachIndexed { s, n -> gn.add(NoteEvent(i * BAR_TICKS + st * STEP_TICKS + s * 36, 900, n, vel(v, gb) - s * 3)) }
                    }
                }
                if (gn.isNotEmpty()) guitarClips.add(Clip(nid(), bar, BLOCK_BARS, gn.sortedBy { it.start }))
            }

            // Bass đánh gốc hợp âm của giọng (không ở intro). Rap không có giai điệu: Looper đã có bass nên bass riêng chỉ ở chorus.
            if (sec != Section.INTRO && (follow || sec == Section.CHORUS)) {
                val bn = ArrayList<NoteEvent>()
                val bPat = pickPattern(bassPatterns, profile, bar, rnd, 0.5)
                val bSteps = steps(bPat)
                for (i in 0 until BLOCK_BARS) {
                    val gb = bar + i
                    val c = chordAt(gb)
                    var m = 24 + Math.floorMod(key + c.root, 12)
                    if (m < 31) m += 12
                    for ((si, st) in bSteps.withIndex()) {
                        val nextSt = bSteps.getOrNull(si + 1) ?: 16
                        bn.add(NoteEvent(i * BAR_TICKS + st * STEP_TICKS, ((nextSt - st).coerceAtMost(8)) * STEP_TICKS - 80, m, vel(if (st == 0) 88 else 72, gb)))
                    }
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
        return p.copy(tracks = keep + made, nextId = nextId, arrangeGenre = genre.id, arrangeSeed = seed)
    }
}
