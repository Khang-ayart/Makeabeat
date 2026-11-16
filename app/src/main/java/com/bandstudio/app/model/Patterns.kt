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

    /** Một cú đánh của nhịp: bước (0-15), velocity, chỉ số âm trong kit. */
    data class Hit(val step: Int, val vel: Int, val kitIdx: Int)

    fun hits(family: String, pad: Int): List<Hit> {
        val p = pattern(family, pad) ?: return emptyList()
        val row = pad / PADS_PER_ROW
        val out = ArrayList<Hit>()
        p.forEachIndexed { step, ch ->
            val vel = when (ch) {
                'X' -> 124
                'x' -> 100
                'g' -> 58
                in '0'..'3' -> 108
                else -> 0
            }
            if (vel == 0) return@forEachIndexed
            // Hàng Bass: chữ số chọn một trong 4 nốt; các hàng khác: pad dùng đúng âm của mình.
            val kitIdx = if (row == SoundCat.BASS.ordinal) row * PADS_PER_ROW + (ch - '0').coerceIn(0, 3) else pad
            out.add(Hit(step, vel, kitIdx))
        }
        return out
    }

    /** Các nốt (tick tương đối trong 1 ô nhịp) của pad, dùng khi chuyển phiên chơi thành clip. */
    fun notes(family: String, pad: Int): List<NoteEvent> =
        hits(family, pad).map { NoteEvent(it.step * STEP_TICKS, STEP_TICKS, it.kitIdx, it.vel) }
}
