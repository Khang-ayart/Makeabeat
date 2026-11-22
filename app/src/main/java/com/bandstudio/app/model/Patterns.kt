package com.bandstudio.app.model

/**
 * Nhịp của từng pad Looper. Mỗi pad là MỘT Ô NHỊP gồm 16 bước (1/16), do app tự sinh theo "họ" nhịp của thể loại,
 * không dùng gói loop dựng sẵn. Ký tự: '.' nghỉ, 'x' đánh vừa, 'X' đánh mạnh, 'g' đánh khẽ (ghost).
 * Hàng Bass dùng chữ số 0-3 = cao độ trong 4 nốt của hàng đó (gốc, quãng 3/4, 5, ...).
 * Vai trò của 4 cột (giống nhau ở mọi hàng): 0 = nhịp chính, 1 = biến tấu, 2 = thưa / lệch phách, 3 = dày / dồn.
 */
object Patterns {
    const val STEPS = 16
    const val LEN_TICKS = STEPS * STEP_TICKS  // 3840 = 1 ô nhịp

    /** Mỗi họ: 5 dòng (Kick, Snare, Hi-hat, Percussion, Bass), mỗi dòng 4 mẫu cách nhau bằng dấu cách. */
    private val families: Map<String, List<String>> = mapOf(
        "slow" to listOf(
            "X.......x....... X.....x...x..... X............... X..x..x.X..x....",
            "....X.......X... ....X.......X..g ........X....... ....X..g....X.x.",
            "x.x.x.x.x.x.x.x. xxxxxxxxxxxxxxxx ..x...x...x...x. x...x...x...x...",
            "x...x...x...x... ..x...x...x...x. x.x.x.x.x.x.x.x. ....x.......x...",
            "0.......2....... 0.....0.2.....1. 0...0...2...2... 0.1.2.3.0.1.2.3.",
        ),
        "pop" to listOf(
            "X...x...X...x... X.....x.X...x... x..x..x.x..x..x. X..x..x.X...x.x.",
            "....X.......X... ....X.......X..x ....X..g....X.g. ....x.x.....X.xx",
            "x.x.x.x.x.x.x.x. xxxxxxxxxxxxxxxx ..x...x...x...x. x.x.x.xxx.x.x.xx",
            "x...x...x...x... ..x...x...x...x. x.x.x.x.x.x.x.x. x..x..x.x..x..x.",
            "0..0..0.2..2..1. 0.0.0.0.2.2.2.1. 0...0.2...0.3... 0..0.0.0..0.2.3.",
        ),
        "boombap" to listOf(
            "X.....x...x..... X..x..x...x...x. X.........x..... X..x.....x.x..x.",
            "....X.......X... ....X.......X.g. ....X..g....X..g ....X.......X..x",
            "x.x.x.x.x.x.x.x. x.xxx.x.x.xxx.x. ..x...x...x...x. xxxxxxxxxxxxxxxx",
            "..x...x...x...x. x.......x....... ..x.....x...x... x..x..x.x..x..x.",
            "0.....0...2..... 0..0....2..2..3. 0.......2....... 0.0...0.2.2...3.",
        ),
        "trap" to listOf(
            "X.........x..... X.....x.....x... X..x......x..x.. X.x.......x.x...",
            "........X....... ........X......x ........X..g.... ........X.....xx",
            "x.x.x.x.x.x.x.xr x.xxx.x.x.xtx.x. x...x...x.r.x.tq x.x.x.x.x.x.xtqr",
            "........x....... ....x.......x... ..x.....x...x... x...x...x...x...",
            "0.........0..... 0.....0.....b..c 0..0..c.....d... 0.........c..d..",
        ),
        "melodic" to listOf(
            "X.......x....... X.....x.....x... X..x......x..x.. X.x.......x.x...",
            "........X....... ........X......g ........X..g.... ........X.....xx",
            "x.x.x.x.x.x.x.x. x.x.x.x.x.x.xtx. x...x.x.x...x.xr x.x.x.x.x.xtx.qq",
            "........x....... ....x.......x... ..x.....x...x... x...x...x...x...",
            "0............... 0.......b....... 0.....c...d..... 0..0....c...b...",
        ),
        "drill" to listOf(
            "X.....x...x..... X..x......x..x.. X........x.x.... X.x.....x..x....",
            "........X....... ........X.....x. ........X..x.... ........X...g..x",
            "x.x.x.x.x.x.x.x. x.xtx.x.x.xtx.x. x..x..x..x..x..x x.x.tqx.x.x.tqx.",
            "..x...x...x...x. x...x...x...x... ...x...x...x...x x.x.....x.x.....",
            "0.....c.....d... 0..b..0.....c..b 0.......c..b..a. 0..0..c..b..a...",
        ),
        "afro" to listOf(
            "X..x..x.....x... X..x..x...x.x... X.....x..x...... X..x..x..x..x..x",
            "....x.......x... ....x.g.....x.g. ....x..g....x.xg ..g.x.......x..g",
            "x.x.x.x.x.x.x.x. xxxxxxxxxxxxxxxx x.xx.xx.xx.xx.xx ..x...x...x...x.",
            "x..x..x...x.x... ..x..x..x..x..x. x.x.x...x.x.x... ..x...x..x..x...",
            "0..0.....0..2... 0..0..2.....0.3. 0.....2..3...... 0..0..0..2..2..3",
        ),
        "rock" to listOf(
            "X.......X.x..... X..x....X.x..... X...x...X...x... X.x.X.x.X.x.X.x.",
            "....X.......X... ....X..g....X..g ....X.......X.xx ....X.g.....X.g.",
            "x.x.x.x.x.x.x.x. x...x...x...x... xxxxxxxxxxxxxxxx x.x.x.x.x.x.xxxx",
            "X............... X.......X....... ....x.......x... x...x...x...x...",
            "0.0.0.0.0.0.0.0. 0.0.0.2.0.0.3.2. 0...0...2...2... 0.0..0..2.2..2..",
        ),
        "edm" to listOf(
            "X...X...X...X... X...X...X...X..x X...X...X...X.x. X.......X.......",
            "....X.......X... ....X.......X.x. ....X..x....X..x ............X.xx",
            "..x...x...x...x. x.x.x.x.x.x.x.x. xxxxxxxxxxxxxxxx ..x...x...x...xx",
            "x...x...x...x... ..x...x...x...x. x.x.x.x.x.x.x.x. ........x.......",
            "..0...0...0...0. ..0.0.0...0.0.0. 0.0.0.0.0.0.0.0. ..0..0..2..2..3.",
        ),
        "latin" to listOf(
            "X..x..x.X..x..x. X...x..x....x... X..x....X..x.... X.......X.......",
            "x..x..x...x.x... ..x...x...x...x. ....x.......x... x..x..x...x.x..x",
            "x.x.x.x.x.x.x.x. xxxxxxxxxxxxxxxx ..x...x...x...x. x..x..x...x.x...",
            "x..x..x...x.x... ..x.x...x.x...x. x.x.x.x.x.x.x.x. x...x...x...x..x",
            "0..0..2..0..0.3. 0..0..0.2..2..1. 0.......2.....3. 0.0..0.2.0.0..3.",
        ),
        "jazz" to listOf(
            "x...x...x...x... X.......x....... x.......x..g.... X..g....x...g...",
            "........g....... ....g.......g... ..g...g...g...g. ....x.......x..g",
            "x...x...x...x... ..x...x...x...x. x.x.x.x.x.x.x.x. x.xx.xx.x.xx.xx.",
            "x..x.x..x..x.x.. x...x...x...x... x.x...x.x.x...x. ..x...x...x...x.",
            "0...1...2...3... 0..1.2..3..2.1.. 0.......2....... 0.1.2.3.2.1.0...",
        ),
    )

    val familyIds: Set<String> get() = families.keys

    /** Chuỗi 16 ký tự của pad (0-19) trong họ nhịp; null nếu họ không tồn tại. */
    fun pattern(family: String, pad: Int): String? {
        val rows = families[family] ?: return null
        val row = pad / PADS_PER_ROW
        val col = pad % PADS_PER_ROW
        return rows.getOrNull(row)?.split(' ')?.getOrNull(col)
    }

    /** Một cú đánh: tick trong chu kỳ của pad, velocity, âm trong kit (< 128) hoặc nốt keyboard (128 + MIDI), độ dài. */
    data class Hit(val tick: Int, val vel: Int, val kitIdx: Int, val dur: Int)

    const val KEY_NOTE_BASE = 128     // Keyboard (piano)
    const val LEAD_NOTE_BASE = 256    // Synth lead / arp
    const val ATMOS_NOTE_BASE = 384   // Synth pad / atmos
    private val KEYS_FIRST_PAD = KIT_COUNT  // pad 20..31: các hàng giai điệu (Keyboard, Synth, Pad)
    const val KEYS_BARS = 4                 // vòng hợp âm dài 4 ô nhịp

    /** Pad thuộc hàng giai điệu (không có âm trong kit, nốt nằm ngay trong nhịp của pad). */
    fun isKeyPad(pad: Int) = pad >= KEYS_FIRST_PAD

    /** Mã nốt cơ sở của hàng giai điệu chứa [pad]: mã nốt = base + MIDI. */
    fun noteBase(pad: Int): Int = when (SoundCat.entries.getOrNull(pad / PADS_PER_ROW)) {
        SoundCat.LEAD -> LEAD_NOTE_BASE
        SoundCat.ATMOS -> ATMOS_NOTE_BASE
        else -> KEY_NOTE_BASE
    }

    /** Độ dài chu kỳ (tick) của pad: trống/bass và hợp âm đều là một cụm 4 ô nhịp (ô cuối của trống/bass là fill). */
    fun lenTicks(pad: Int) = KEYS_BARS * BAR_TICKS

    /** Số nốt trượt của hàng Bass: mỗi cặp (từ nốt -> tới nốt khác) trong 4 nốt, 4 x 3 = 12. */
    const val SLIDE_COUNT = 12

    /** Chỉ số kit của nốt trượt từ pitch [from] sang pitch [to] (khác nhau, 0..3): KIT_COUNT + 0..11. */
    fun slideKitIdx(from: Int, to: Int): Int = KIT_COUNT + from * 3 + if (to < from) to else to - 1

    /**
     * Nhịp trống/bass của pad 0-19 (1 ô nhịp). Hàng Keyboard/Synth/Pad dùng [melodicHits].
     * Ký tự mở rộng: 'r' = 2 nốt dồn (1/32), 't' = 3 nốt (triplet), 'q' = 4 nốt dồn tăng dần (hi-hat roll).
     * Hàng Bass: chữ cái 'a'..'d' = trượt (slide) từ nốt bass trước đó sang nốt 0..3, kiểu 808 glide của trap/drill.
     */
    fun hits(family: String, pad: Int): List<Hit> {
        if (isKeyPad(pad)) return emptyList()
        val p = pattern(family, pad) ?: return emptyList()
        val row = pad / PADS_PER_ROW
        val bass = row == SoundCat.BASS.ordinal
        // Chuỗi cao độ bass theo thứ tự đánh, để biết nốt trước của mỗi nốt trượt (vòng lặp nên nốt đầu nối với nốt cuối).
        val pitches = if (bass) p.filter { it in '0'..'3' || it in 'a'..'d' }.map { if (it in '0'..'3') it - '0' else it - 'a' } else emptyList()
        var pitchIdx = -1
        val out = ArrayList<Hit>()
        p.forEachIndexed { step, ch ->
            val tick = step * STEP_TICKS
            if (bass) {
                if (ch !in '0'..'3' && ch !in 'a'..'d') return@forEachIndexed
                pitchIdx++
                val to = if (ch in '0'..'3') ch - '0' else ch - 'a'
                val from = pitches[(pitchIdx - 1 + pitches.size) % pitches.size]
                val slide = ch in 'a'..'d' && from != to
                out.add(Hit(tick, if (slide) 112 else 108, if (slide) slideKitIdx(from, to) else row * PADS_PER_ROW + to, STEP_TICKS))
                return@forEachIndexed
            }
            when (ch) {
                'r' -> { out.add(Hit(tick, 104, pad, 120)); out.add(Hit(tick + 120, 86, pad, 120)) }
                't' -> for (k in 0 until 3) out.add(Hit(tick + k * 80, intArrayOf(98, 78, 90)[k], pad, 80))
                'q' -> for (k in 0 until 4) out.add(Hit(tick + k * 60, 70 + k * 10, pad, 60))
                else -> {
                    var vel = when (ch) {
                        'X' -> 124
                        'x' -> 100
                        'g' -> 58
                        else -> 0
                    }
                    if (vel == 0) return@forEachIndexed
                    // Hi-hat: nhấn mạnh theo phách (người chơi thật không đánh đều tăm tắp) -> nhịp có "nhấp nhô".
                    if (row == SoundCat.HAT.ordinal && ch == 'x') vel = when { step % 4 == 0 -> 108; step % 2 == 0 -> 94; else -> 80 }
                    out.add(Hit(tick, vel, pad, STEP_TICKS))
                }
            }
        }
        return out
    }

    // ---- Hàng Keyboard: vòng hợp âm 4 ô nhịp theo tông ----------------------------------------

    /** Hợp âm: bậc so với chủ âm (nửa cung) và là hợp âm thứ hay trưởng. */
    class Chord(val root: Int, val minor: Boolean) {
        val intervals: IntArray get() = if (minor) intArrayOf(0, 3, 7) else intArrayOf(0, 4, 7)
    }

    private val minorProgs = listOf(
        listOf(Chord(0, true), Chord(8, false), Chord(3, false), Chord(10, false)),   // i - VI - III - VII
        listOf(Chord(0, true), Chord(5, true), Chord(10, false), Chord(3, false)),    // i - iv - VII - III
        listOf(Chord(8, false), Chord(10, false), Chord(0, true), Chord(0, true)),    // VI - VII - i - i
    )
    private val majorProgs = listOf(
        listOf(Chord(0, false), Chord(7, false), Chord(9, true), Chord(5, false)),    // I - V - vi - IV
        listOf(Chord(0, false), Chord(9, true), Chord(5, false), Chord(7, false)),    // I - vi - IV - V
        listOf(Chord(5, false), Chord(0, false), Chord(7, false), Chord(9, true)),    // IV - I - V - vi
    )

    /** Vòng hợp âm của pad keyboard (cột 0..3 -> tiến trình 0, 1, 0, 2). */
    fun progression(minor: Boolean, pad: Int): List<Chord> {
        val progs = if (minor) minorProgs else majorProgs
        return progs[intArrayOf(0, 1, 0, 2)[(pad % PADS_PER_ROW)]]
    }

    private fun chordName(key: Int, c: Chord): String = KEY_NAMES[Math.floorMod(key + c.root, 12)] + if (c.minor) "m" else ""

    /** Tên vòng hợp âm để hiện trên pad, ví dụ "Am F C G". */
    fun progressionLabel(key: Int, minor: Boolean, pad: Int): String =
        progression(minor, pad).joinToString(" ") { chordName(key, it) }

    /** Nhịp hợp âm theo họ nhịp: 4 mẫu 16 bước, áp dụng cho từng ô nhịp (cột 2 là rải nốt). */
    private val keyRhythms: Map<String, List<String>> = mapOf(
        "slow" to listOf("x...............", "x.......x.......", "x.x.x.x.x.x.x.x.", "x.....x.x......."),
        "pop" to listOf("x.......x.......", "x..x..x.x..x..x.", "x.x.x.x.x.x.x.x.", "..x...x...x...x."),
        "boombap" to listOf("x...............", "x.....x.....x...", "x.x.x.x.x.x.x.x.", "x..x....x..x...."),
        "trap" to listOf("x...............", "x.......x.......", "x.x.x.x.x.x.x.x.", "x.....x...x....."),
        "rock" to listOf("x.......x.......", "x...x...x...x...", "x.x.x.x.x.x.x.x.", "x..x..x.x..x..x."),
        "edm" to listOf("x...x...x...x...", "..x...x...x...x.", "xxxxxxxxxxxxxxxx", "x..x..x...x..x.."),
        "latin" to listOf("x..x..x.x..x..x.", "..x..x..x..x..x.", "x.x.x.x.x.x.x.x.", "x.......x...x..."),
        "jazz" to listOf("x...............", "x.......x.....x.", "x.x.x.x.x.x.x.x.", "..x.......x....."),
        "melodic" to listOf("x...............", "x.......x.......", "x.x.x.x.x.x.x.x.", "x.....x.x......."),
        "drill" to listOf("x...............", "x.....x.........", "x.x.x.x.x.x.x.x.", "x..x..x.x..x..x."),
        "afro" to listOf("x..x..x.........", "x..x..x.x..x..x.", "x.x.x.x.x.x.x.x.", "..x...x..x..x..."),
    )

    /** Chuỗi 16 bước của nhịp hợp âm (để hiện vạch nhỏ trên pad). */
    fun keyRhythm(family: String, pad: Int): String? = keyRhythms[family]?.getOrNull(pad % PADS_PER_ROW)

    /** Nốt MIDI của hợp âm: gốc nằm trong C3-B3 (48-59), vị trí gốc, nên cả vòng nằm gọn trong 48-66. */
    fun chordNotes(key: Int, c: Chord): List<Int> {
        val rootMidi = 48 + Math.floorMod(key + c.root, 12)
        return c.intervals.map { rootMidi + it }
    }

    fun keyHits(family: String, pad: Int, key: Int, minor: Boolean): List<Hit> {
        val rhythm = keyRhythm(family, pad) ?: return emptyList()
        val prog = progression(minor, pad)
        val arpeggio = pad % PADS_PER_ROW == 2
        val steps = rhythm.indices.filter { rhythm[it] != '.' }
        val out = ArrayList<Hit>()
        for (bar in 0 until KEYS_BARS) {
            val notes = chordNotes(key, prog[bar])
            val base = bar * BAR_TICKS
            steps.forEachIndexed { idx, step ->
                val next = steps.getOrNull(idx + 1) ?: STEPS
                val dur = maxOf(2, next - step) * STEP_TICKS - 40
                if (arpeggio) { // rải nốt: gốc, quãng 3, quãng 5, lên một quãng tám, rồi xuống lại
                    val seq = intArrayOf(notes[0], notes[1], notes[2], notes[0] + 12, notes[2], notes[1])
                    out.add(Hit(base + step * STEP_TICKS, 74, KEY_NOTE_BASE + seq[idx % seq.size], dur.coerceAtLeast(STEP_TICKS * 2)))
                } else {
                    for ((k, n) in notes.withIndex()) {
                        out.add(Hit(base + step * STEP_TICKS + k * 6, 82, KEY_NOTE_BASE + n, dur)) // rải rất nhanh cho tự nhiên
                    }
                }
            }
        }
        return out
    }

    /** Tất cả cú đánh của một pad (trống/bass/hợp âm) để gửi engine. */
    fun padHits(genre: Genre, key: Int, pad: Int): List<Hit> = padHitsIn(genre, key, genre.minor, pad)

    /** Như [padHits] nhưng thang trưởng/thứ do người gọi chọn (ví dụ tông của bài chứ không phải mặc định của thể loại). */
    fun padHitsIn(genre: Genre, key: Int, minor: Boolean, pad: Int): List<Hit> =
        if (isKeyPad(pad)) melodicHits(genre.family, pad, key, minor) else swung(withImpact(genre, pad, phrase(genre.family, pad)), swingOf(genre.id))

    /** Pad crash: thêm một cú crash vào đầu mỗi cụm 4 ô nhịp (điểm chuyển đoạn) nếu nhịp gốc chưa có cú nào ở đó. */
    private fun withImpact(genre: Genre, pad: Int, ph: List<Hit>): List<Hit> {
        if (pad / PADS_PER_ROW != SoundCat.PERC.ordinal) return ph
        val id = genre.kit().getOrNull(pad)?.soundId ?: return ph
        if (!id.contains("crash") || ph.any { it.tick == 0 }) return ph
        return (listOf(Hit(0, 112, pad, 2 * STEP_TICKS)) + ph).sortedBy { it.tick }
    }

    /** Độ lệch velocity ổn định (không ngẫu nhiên) theo vị trí, để mỗi ô nhịp nhấn hơi khác nhau mà vòng lặp vẫn khớp từng bit. */
    private fun jitter(pad: Int, bar: Int, tick: Int): Int = Math.floorMod(pad * 31 + bar * 17 + (tick / STEP_TICKS) * 7, 9) - 4

    /**
     * Cụm 4 ô nhịp của pad trống/bass: ô 1 và 3 là nhịp gốc, ô 2 nhấn nhẹ khác đi, ô 4 là fill dẫn sang cụm sau.
     * Fill: kick đạp thêm cuối ô, snare dồn, hi-hat roll, perc thêm nốt, bass chừa chỗ cho fill.
     */
    fun phrase(family: String, pad: Int): List<Hit> {
        val base = hits(family, pad)
        val row = pad / PADS_PER_ROW
        val out = ArrayList<Hit>()
        for (bar in 0 until KEYS_BARS) {
            val off = bar * BAR_TICKS
            val lastBeat = 12 * STEP_TICKS
            var bh = base.map { h ->
                val v = if (bar == 0) h.vel else (h.vel + jitter(pad, bar, h.tick)).coerceIn(1, 127)
                h.copy(tick = h.tick + off, vel = v)
            }
            if (bar == KEYS_BARS - 1) {
                when (row) {
                    SoundCat.KICK.ordinal -> if (base.none { it.tick >= 14 * STEP_TICKS }) bh = bh + Hit(off + 14 * STEP_TICKS, 96, pad, STEP_TICKS)
                    SoundCat.SNARE.ordinal -> {
                        val rest = bh.filter { it.tick - off < 14 * STEP_TICKS }
                        bh = rest + listOf(
                            Hit(off + 14 * STEP_TICKS, 92, pad, 120), Hit(off + 14 * STEP_TICKS + 120, 100, pad, 120),
                            Hit(off + 15 * STEP_TICKS, 108, pad, 120), Hit(off + 15 * STEP_TICKS + 120, 120, pad, 120))
                    }
                    SoundCat.HAT.ordinal -> {
                        val rest = bh.filter { it.tick - off < 14 * STEP_TICKS }
                        bh = rest + (0 until 8).map { k -> Hit(off + 14 * STEP_TICKS + k * 60, 66 + k * 7, pad, 60) }
                    }
                    SoundCat.PERC.ordinal -> if (base.none { it.tick in 13 * STEP_TICKS until 16 * STEP_TICKS }) bh = bh + Hit(off + 13 * STEP_TICKS, 84, pad, STEP_TICKS) + Hit(off + 15 * STEP_TICKS, 72, pad, STEP_TICKS)
                    SoundCat.BASS.ordinal -> {
                        val kept = bh.filter { it.tick - off < lastBeat + 2 * STEP_TICKS }
                        if (kept.isNotEmpty()) bh = kept
                    }
                    else -> Unit
                }
            }
            out.addAll(bh)
        }
        return out.sortedBy { it.tick }
    }

    /** Hàng giai điệu: Keyboard (hợp âm), Synth lead (arp / pluck / hook / stab), Synth pad (hợp âm nền kéo dài). */
    fun melodicHits(family: String, pad: Int, key: Int, minor: Boolean): List<Hit> =
        when (SoundCat.entries.getOrNull(pad / PADS_PER_ROW)) {
            SoundCat.KEYS -> keyHits(family, pad, key, minor)
            SoundCat.LEAD -> leadHits(family, pad, key, minor)
            SoundCat.ATMOS -> atmosHits(pad, key, minor)
            else -> emptyList()
        }

    private val leadDense = setOf("edm", "pop", "trap", "latin", "afro")

    /** Nhịp 16 bước dùng để vẽ vạch nhỏ trên pad (chỉ để hiển thị). */
    fun displayRhythm(family: String, pad: Int): String? = when (SoundCat.entries.getOrNull(pad / PADS_PER_ROW)) {
        SoundCat.KEYS -> keyRhythm(family, pad)
        SoundCat.LEAD -> when (pad % PADS_PER_ROW) {
            0 -> if (family in leadDense) "xxxxxxxxxxxxxxxx" else "x.x.x.x.x.x.x.x."
            1 -> "..x...x...x...x."
            2 -> "x...x.x.x...x..."
            else -> "x..x..x.x..x..x."
        }
        SoundCat.ATMOS -> "x..............."
        else -> null
    }

    private val leadMotifs = listOf(  // (bước, chỉ số nốt hợp âm, dịch quãng tám, độ dài bước)
        listOf(intArrayOf(0, 2, 0, 3), intArrayOf(4, 1, 0, 2), intArrayOf(6, 0, 0, 2), intArrayOf(8, 2, 0, 3), intArrayOf(12, 1, 0, 2)),
        listOf(intArrayOf(0, 0, 0, 3), intArrayOf(4, 1, 0, 2), intArrayOf(6, 2, 0, 2), intArrayOf(10, 1, 0, 2), intArrayOf(12, 2, 12, 4)),
    )

    /**
     * Synth lead: mọi nốt đều là nốt của hợp âm đang chơi (arp, pluck, hook, stab) nên không thể nghe sai tông.
     * Cột 0 arp liên tục, cột 1 pluck lệch phách, cột 2 hook 2 ô nhịp lặp lại, cột 3 stab ngắt quãng.
     */
    fun leadHits(family: String, pad: Int, key: Int, minor: Boolean): List<Hit> {
        val col = pad % PADS_PER_ROW
        val prog = progression(minor, pad)
        val dense = family in leadDense
        val out = ArrayList<Hit>()
        for (bar in 0 until KEYS_BARS) {
            val tones = chordNotes(key, prog[bar]).map { it + 12 }
            val base = bar * BAR_TICKS
            fun add(step: Int, midi: Int, vel: Int, dur: Int) =
                out.add(Hit(base + step * STEP_TICKS, vel, LEAD_NOTE_BASE + midi, dur))
            when (col) {
                0 -> {
                    val gap = if (dense) 1 else 2
                    val seq = intArrayOf(0, 1, 2, 1)
                    var i = 0
                    for (step in 0 until STEPS step gap) {
                        add(step, tones[seq[i % seq.size]], if (i % 4 == 0) 92 else 72, gap * STEP_TICKS - 30)
                        i++
                    }
                }
                1 -> for ((i, step) in intArrayOf(2, 6, 10, 14).withIndex()) add(step, tones[intArrayOf(2, 1, 2, 0)[i]], 84, 360)
                2 -> for (m in leadMotifs[bar % 2]) add(m[0], tones[m[1]] + m[2], 88, m[3] * STEP_TICKS - 40)
                else -> for (step in intArrayOf(0, 3, 6, 8, 11, 14)) for (t in tones) add(step, t, 78, 170)
            }
        }
        return out
    }

    /** Synth pad: hợp âm nền kéo dài hết ô nhịp, bốn cách xếp nốt (đóng, mở, add9, đảo cao). */
    fun atmosHits(pad: Int, key: Int, minor: Boolean): List<Hit> {
        val col = pad % PADS_PER_ROW
        val prog = progression(minor, pad)
        val out = ArrayList<Hit>()
        for (bar in 0 until KEYS_BARS) {
            val n = chordNotes(key, prog[bar])
            val voicing = when (col) {
                0 -> n
                1 -> listOf(n[0], n[0] + 7, n[1] + 12)
                2 -> listOf(n[0], n[1], n[2], n[0] + 14)
                else -> listOf(n[1] + 12, n[2] + 12, n[0] + 24)
            }
            for (note in voicing) out.add(Hit(bar * BAR_TICKS, 72, ATMOS_NOTE_BASE + note, BAR_TICKS - 80))
        }
        return out
    }

    /** Giọng synth theo thể loại: 1 sáng (pop, EDM, latin, afro, rock), 2 mềm tối (lo-fi, jazz, R&B, ballad, acoustic), 3 gắt ngắn (trap, drill, hip-hop, melodic). */
    fun synthStyleOf(genreId: String): Int = when (genreId) {
        "pop", "edm", "remix", "latin", "afro", "rock" -> 1
        "lofi", "jazz", "rnb", "ballad", "acoustic" -> 2
        "trap", "drill", "hiphop", "melodic" -> 3
        else -> 0
    }

    /** Độ swing (0..0,5) theo thể loại: phần của một bước 1/16 mà nốt lẻ bị trễ. 0 = thẳng hàng. */
    fun swingOf(genreId: String): Float = when (genreId) {
        "jazz" -> 0.33f
        "lofi" -> 0.28f
        "rnb" -> 0.20f
        "hiphop" -> 0.12f
        else -> 0f
    }

    /** Trễ các nốt nằm trên bước 1/16 lẻ (chỗ "và" của nửa phách) để nhịp có độ lắc. Vòng lặp vẫn khớp từng bit. */
    fun swung(hits: List<Hit>, swing: Float): List<Hit> {
        if (swing <= 0f) return hits
        val shift = (STEP_TICKS * swing).toInt()
        return hits.map { h ->
            if (h.tick % (2 * STEP_TICKS) == STEP_TICKS) h.copy(tick = minOf(h.tick + shift, (h.tick / LEN_TICKS + 1) * LEN_TICKS - 1)) else h
        }
    }
}
