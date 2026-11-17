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
            "xxxxxxxxxxxxxxxx x.x.x.x.x.x.x.x. xxxxxxx.xxxxxxxx x.x.xxxxx.x.xxxx",
            "........x....... ....x.......x... ..x.....x...x... x...x...x...x...",
            "0.........0..... 0.....0.....0... 0..0..2.....3... 0.........2..3..",
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

    const val KEY_NOTE_BASE = 128
    private val KEYS_FIRST_PAD = KIT_COUNT  // pad 20..23 là hàng Keyboard
    const val KEYS_BARS = 4                 // vòng hợp âm dài 4 ô nhịp

    fun isKeyPad(pad: Int) = pad >= KEYS_FIRST_PAD

    /** Độ dài chu kỳ (tick) của pad: nhịp 1 ô, vòng hợp âm 4 ô. */
    fun lenTicks(pad: Int) = if (isKeyPad(pad)) KEYS_BARS * BAR_TICKS else LEN_TICKS

    /** Nhịp trống/bass của pad 0-19 (1 ô nhịp). Hàng Keyboard dùng [keyHits]. */
    fun hits(family: String, pad: Int): List<Hit> {
        if (isKeyPad(pad)) return emptyList()
        val p = pattern(family, pad) ?: return emptyList()
        val row = pad / PADS_PER_ROW
        val out = ArrayList<Hit>()
        p.forEachIndexed { step, ch ->
            var vel = when (ch) {
                'X' -> 124
                'x' -> 100
                'g' -> 58
                in '0'..'3' -> 108
                else -> 0
            }
            if (vel == 0) return@forEachIndexed
            // Hi-hat: nhấn mạnh theo phách (người chơi thật không đánh đều tăm tắp) -> nhịp có "nhấp nhô".
            if (row == SoundCat.HAT.ordinal && ch == 'x') vel = when { step % 4 == 0 -> 108; step % 2 == 0 -> 94; else -> 80 }
            // Hàng Bass: chữ số chọn một trong 4 nốt; các hàng khác: pad dùng đúng âm của mình.
            val kitIdx = if (row == SoundCat.BASS.ordinal) row * PADS_PER_ROW + (ch - '0').coerceIn(0, 3) else pad
            out.add(Hit(step * STEP_TICKS, vel, kitIdx, STEP_TICKS))
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
    fun padHits(genre: Genre, key: Int, pad: Int): List<Hit> =
        if (isKeyPad(pad)) keyHits(genre.family, pad, key, genre.minor) else swung(hits(genre.family, pad), swingOf(genre.id))

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
            if (h.tick % (2 * STEP_TICKS) == STEP_TICKS) h.copy(tick = minOf(h.tick + shift, LEN_TICKS - 1)) else h
        }
    }
}
