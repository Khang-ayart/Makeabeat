package com.bandstudio.app.model

/**
 * Chỉnh sửa clip âm thanh không phá hủy (tệp gốc giữ nguyên, chỉ đổi điểm vào / độ dài / vị trí).
 * Mọi thao tác theo ranh giới ô nhịp. Hàm thuần: trả về Project mới, hoặc null nếu thao tác không hợp lệ.
 */
object AudioEdit {

    /** Số ms của tệp gốc ứng với một ô nhịp của bài (đã tính co/giãn). */
    fun srcMsPerBar(src: AudioSource, bpm: Int): Double = 240000.0 / bpm / src.ratio(bpm)

    private fun Project.mapAudio(trackId: Int, f: (List<AudioClip>) -> List<AudioClip>?): Project? {
        val t = track(trackId) ?: return null
        if (!t.instrument.isAudio) return null
        val next = f(t.audio) ?: return null
        return copy(tracks = tracks.map { if (it.id == trackId) it.copy(audio = next) else it })
    }

    /** Thêm clip [srcLenMs] của nguồn [sourceId] vào track, đặt ở ô [startBar]. */
    fun addClip(p: Project, trackId: Int, sourceId: Int, startBar: Int, srcStartMs: Int = 0, srcLenMs: Int? = null, label: String = ""): Project? {
        val src = p.source(sourceId) ?: return null
        val len = srcLenMs ?: (src.lengthMs - srcStartMs)
        if (len <= 0 || startBar !in 0 until MAX_BARS) return null
        return p.mapAudio(trackId) { list ->
            if (list.size >= MAX_AUDIO_CLIPS) null
            else list + AudioClip(p.nextId, sourceId, startBar, srcStartMs, len, label = label)
        }?.copy(nextId = p.nextId + 1)
    }

    /** Cắt clip tại ô [bar] (phải nằm trong clip, không phải đầu clip). */
    fun split(p: Project, trackId: Int, clipId: Int, bar: Int): Project? {
        return p.mapAudio(trackId) { list ->
            val c = list.firstOrNull { it.id == clipId } ?: return@mapAudio null
            val src = p.source(c.sourceId) ?: return@mapAudio null
            if (bar <= c.startBar || bar >= c.endBar(src, p.bpm) || list.size >= MAX_AUDIO_CLIPS) return@mapAudio null
            val cut = ((bar - c.startBar) * srcMsPerBar(src, p.bpm)).toInt()
            if (cut <= 0 || cut >= c.srcLenMs) return@mapAudio null
            val right = c.copy(id = p.nextId, startBar = bar, srcStartMs = c.srcStartMs + cut, srcLenMs = c.srcLenMs - cut)
            list.map { if (it.id == clipId) it.copy(srcLenMs = cut) else it } + right
        }?.let { it.copy(nextId = p.nextId + 1) }
    }

    /** Nhân bản clip, đặt ngay sau clip gốc. */
    fun duplicate(p: Project, trackId: Int, clipId: Int): Project? {
        return p.mapAudio(trackId) { list ->
            val c = list.firstOrNull { it.id == clipId } ?: return@mapAudio null
            val src = p.source(c.sourceId) ?: return@mapAudio null
            val at = c.endBar(src, p.bpm)
            if (at + c.bars(src, p.bpm) > MAX_BARS || list.size >= MAX_AUDIO_CLIPS) return@mapAudio null
            list + c.copy(id = p.nextId, startBar = at)
        }?.let { it.copy(nextId = p.nextId + 1) }
    }

    fun delete(p: Project, trackId: Int, clipId: Int): Project? =
        p.mapAudio(trackId) { list -> if (list.any { it.id == clipId }) list.filter { it.id != clipId } else null }

    /** Dời clip [deltaBars] ô (âm = sớm hơn). */
    fun move(p: Project, trackId: Int, clipId: Int, deltaBars: Int): Project? {
        return p.mapAudio(trackId) { list ->
            val c = list.firstOrNull { it.id == clipId } ?: return@mapAudio null
            val src = p.source(c.sourceId) ?: return@mapAudio null
            val to = c.startBar + deltaBars
            if (to < 0 || to + c.bars(src, p.bpm) > MAX_BARS) return@mapAudio null
            list.map { if (it.id == clipId) it.copy(startBar = to) else it }
        }
    }

    /** Cắt bớt [bars] ô ở đầu (bars > 0) hoặc ở cuối (fromEnd = true). Giữ lại ít nhất 1 ô. */
    fun trim(p: Project, trackId: Int, clipId: Int, bars: Int, fromEnd: Boolean): Project? {
        if (bars <= 0) return null
        return p.mapAudio(trackId) { list ->
            val c = list.firstOrNull { it.id == clipId } ?: return@mapAudio null
            val src = p.source(c.sourceId) ?: return@mapAudio null
            if (c.bars(src, p.bpm) - bars < 1) return@mapAudio null
            val cut = (bars * srcMsPerBar(src, p.bpm)).toInt()
            val n = if (fromEnd) c.copy(srcLenMs = c.srcLenMs - cut)
            else c.copy(startBar = c.startBar + bars, srcStartMs = c.srcStartMs + cut, srcLenMs = c.srcLenMs - cut)
            list.map { if (it.id == clipId) n else it }
        }
    }

    fun setLabel(p: Project, trackId: Int, clipId: Int, label: String): Project? =
        p.mapAudio(trackId) { list -> list.map { if (it.id == clipId) it.copy(label = label) else it } }

    fun setGain(p: Project, trackId: Int, clipId: Int, gain: Float): Project? =
        p.mapAudio(trackId) { list -> list.map { if (it.id == clipId) it.copy(gain = gain.coerceIn(0.1f, 1.5f)) else it } }

    /** Ô còn trống đầu tiên trong dải slot audio, hoặc null nếu hết (84 ô). */
    fun freeSlot(p: Project): Int? =
        (FIRST_AUDIO_SLOT..LAST_AUDIO_SLOT).firstOrNull { s -> p.sources.none { it.slot == s } }
}

/**
 * Cắt giọng hát tại vị trí đầu phát (ms trong bài), chính xác từng mili giây (UI làm tròn theo 1/32 nhịp).
 * Đoạn nào cũng tham chiếu bản thu đã xử lý, không tạo tệp mới nên hoàn tác thoải mái.
 */
object VocalEdit {
    const val MIN_MS = 80

    private fun Project.mapVocal(f: (VocalClip) -> VocalClip?): Project? {
        val t = tracks.firstOrNull { it.instrument.isVocal && it.vocal != null } ?: return null
        val next = f(t.vocal!!) ?: return null
        return copy(tracks = tracks.map { if (it.id == t.id) it.copy(vocal = next) else it })
    }

    /** Đoạn đang phát tại [ms] (đầu đoạn tính, cuối đoạn không). */
    fun segAt(c: VocalClip, ms: Int): VocalSeg? = c.segments().firstOrNull { ms >= it.atMs && ms < it.endMs }

    fun split(p: Project, ms: Int): Project? = p.mapVocal { c ->
        val segs = c.segments()
        if (segs.size >= MAX_AUDIO_CLIPS) return@mapVocal null
        val s = segs.firstOrNull { ms - it.atMs >= MIN_MS && it.endMs - ms >= MIN_MS } ?: return@mapVocal null
        val cut = ms - s.atMs
        val id = (segs.maxOf { it.id }) + 1
        val left = s.copy(lenMs = cut)
        val right = VocalSeg(id, s.srcStartMs + cut, s.lenMs - cut, ms)
        c.copy(segs = segs.flatMap { if (it.id == s.id) listOf(left, right) else listOf(it) })
    }

    /** Xóa đoạn tại [ms]; không xóa đoạn cuối cùng (dùng "Xóa giọng" cho việc đó). */
    fun delete(p: Project, ms: Int): Project? = p.mapVocal { c ->
        val segs = c.segments()
        val s = segAt(c, ms) ?: return@mapVocal null
        if (segs.size <= 1) return@mapVocal null
        c.copy(segs = segs.filter { it.id != s.id })
    }

    /** Dời đoạn tại [ms] đi [deltaMs] (âm = sớm hơn). Không ra khỏi đầu bài hoặc quá [MAX_BARS] ô. */
    fun move(p: Project, ms: Int, deltaMs: Int): Project? = p.mapVocal { c ->
        val segs = c.segments()
        val s = segAt(c, ms) ?: return@mapVocal null
        val at = s.atMs + deltaMs
        if (at < 0 || at + s.lenMs > (MAX_BARS * 240000.0 / p.bpm).toInt()) return@mapVocal null
        c.copy(segs = segs.map { if (it.id == s.id) it.copy(atMs = at) else it })
    }

    /** Bỏ mọi vết cắt, giọng phát nguyên bản thu. */
    fun reset(p: Project): Project? = p.mapVocal { c -> if (c.segs.isEmpty()) null else c.copy(segs = emptyList()) }
}
