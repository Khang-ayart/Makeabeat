package com.bandstudio.app.model

import kotlin.math.roundToInt

/** Loại đoạn của bài. Hook = chorus. */
enum class SectionType(val label: String, val color: Long, val defaultBars: Int, val minBars: Int, val maxBars: Int) {
    INTRO("Intro", 0xFF7FB7FF, 8, 4, 16),
    VERSE("Verse", 0xFFFFD166, 16, 8, 32),
    PREHOOK("Pre-hook", 0xFFFF9F6B, 4, 4, 8),
    HOOK("Hook", 0xFFFF6B8A, 8, 4, 16),
    BRIDGE("Bridge", 0xFFB48CFF, 8, 4, 16),
    OUTRO("Outro", 0xFF8AD1C2, 8, 4, 16),
}

/** Một đoạn của bài: [type] kéo dài [bars] ô nhịp (bội của 4 để khớp cụm 4 ô của nhịp trống). */
data class SongSection(val type: SectionType, val bars: Int)

/** Cấu trúc bài + thời lượng: mẫu dựng sẵn, co giãn cho vừa số ô cần, tính vị trí đoạn. */
object Structure {
    const val UNIT = 4

    class Preset(val id: String, val label: String, val types: List<SectionType>)

    private val T = SectionType.entries.associateBy { it.name }
    private fun types(vararg s: SectionType) = s.toList()
    private val I = SectionType.INTRO; private val V = SectionType.VERSE; private val P = SectionType.PREHOOK
    private val H = SectionType.HOOK; private val B = SectionType.BRIDGE; private val O = SectionType.OUTRO

    val presets: List<Preset> = listOf(
        Preset("melodic", "Hook trước (Melodic Trap)", types(I, H, V, P, H, V, P, H, B, H, O)),
        Preset("standard", "Chuẩn", types(I, V, P, H, V, P, H, B, H, O)),
        Preset("short", "Ngắn", types(I, V, H, V, H, O)),
    )

    fun preset(id: String): Preset = presets.firstOrNull { it.id == id } ?: presets[1]

    fun defaults(types: List<SectionType>): List<SongSection> = types.map { SongSection(it, it.defaultBars) }

    fun totalBars(list: List<SongSection>): Int = list.sumOf { it.bars }

    /** Số ô nhịp gần nhất (bội của 4) cho [seconds] giây ở [bpm], trong [8, MAX_BARS]. */
    fun barsForSeconds(seconds: Int, bpm: Int): Int {
        val raw = seconds * bpm / 240.0
        val r = (raw / UNIT).roundToInt() * UNIT
        return r.coerceIn(8, MAX_BARS / UNIT * UNIT)
    }

    fun secondsOf(bars: Int, bpm: Int): Int = (bars * 240.0 / bpm.coerceAtLeast(1)).roundToInt()

    /** Thứ tự loại đoạn được kéo dài / thu ngắn khi co giãn cấu trúc cho vừa thời lượng. */
    private val growOrder = listOf(SectionType.VERSE, SectionType.HOOK, SectionType.BRIDGE, SectionType.INTRO, SectionType.OUTRO, SectionType.PREHOOK)

    /**
     * Co giãn [list] cho tổng đúng [target] ô (làm tròn về bội của 4 và về giới hạn có thể đạt): mỗi bước 4 ô cho đoạn nào đang
     * ngắn nhất so với độ dài chuẩn của loại đó (thu ngắn thì đoạn dài nhất), ưu tiên Verse, Hook, Bridge, Intro, Outro, Pre-hook.
     */
    fun fit(list: List<SongSection>, target: Int): List<SongSection> {
        if (list.isEmpty()) return list
        val lo = list.sumOf { it.type.minBars }
        val hi = list.sumOf { it.type.maxBars }
        val goal = ((target.coerceIn(lo, hi) + UNIT / 2) / UNIT * UNIT).coerceIn(lo, hi)
        val cur = list.map { it.bars }.toIntArray()
        var sum = cur.sum()
        while (sum != goal) {
            val grow = sum < goal
            var pick = -1
            var pickScore = 0.0
            for ((rank, t) in growOrder.withIndex()) {
                for (i in list.indices) {
                    if (list[i].type != t) continue
                    val next = cur[i] + if (grow) UNIT else -UNIT
                    if (next < t.minBars || next > t.maxBars) continue
                    val ratio = cur[i].toDouble() / t.defaultBars
                    // thấp hơn = được chọn: ưu tiên tỉ lệ nhỏ (grow) hoặc lớn (shrink), rồi tới thứ tự loại
                    val score = (if (grow) ratio else -ratio) * 10 + rank * 0.01
                    if (pick < 0 || score < pickScore) { pick = i; pickScore = score }
                }
            }
            if (pick < 0) break
            cur[pick] += if (grow) UNIT else -UNIT
            sum = cur.sum()
        }
        return list.mapIndexed { i, s -> s.copy(bars = cur[i]) }
    }

    /** Cấu trúc dựng từ mẫu [presetId], co giãn cho vừa [targetBars] ô. */
    fun plan(presetId: String, targetBars: Int): List<SongSection> = fit(defaults(preset(presetId).types), targetBars)

    /** Ô bắt đầu của từng đoạn. */
    fun starts(list: List<SongSection>): List<Int> {
        var b = 0
        return list.map { val s = b; b += it.bars; s }
    }

    /** Tên hiển thị: Verse được đánh số (Verse 1, Verse 2), các loại khác giữ nguyên. */
    fun names(list: List<SongSection>): List<String> {
        val total = list.groupingBy { it.type }.eachCount()
        val seen = HashMap<SectionType, Int>()
        return list.map { s ->
            val n = (seen[s.type] ?: 0) + 1
            seen[s.type] = n
            if (s.type == SectionType.VERSE && (total[s.type] ?: 0) > 1) "${s.type.label} $n" else s.type.label
        }
    }

    /** Cấu trúc theo kiểu cũ (intro, verse verse hook hook lặp lại, outro) cho [total] ô, để dự án chưa chọn cấu trúc vẫn dựng được. */
    fun legacy(total: Int): List<SongSection> {
        val blocks = (total / UNIT).coerceAtLeast(1)
        val types = List(blocks) { b ->
            when {
                blocks >= 3 && b == 0 -> SectionType.INTRO
                blocks >= 3 && b == blocks - 1 -> SectionType.OUTRO
                (b - 1).coerceAtLeast(0) % 4 < 2 -> SectionType.VERSE
                else -> SectionType.HOOK
            }
        }
        val out = ArrayList<SongSection>()
        for (t in types) {
            if (out.isNotEmpty() && out.last().type == t) out[out.size - 1] = out.last().copy(bars = out.last().bars + UNIT)
            else out.add(SongSection(t, UNIT))
        }
        return out
    }

    fun typeOf(name: String): SectionType? = T[name]
}
