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
