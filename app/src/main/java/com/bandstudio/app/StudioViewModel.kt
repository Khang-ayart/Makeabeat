package com.bandstudio.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bandstudio.app.audio.AudioEngine
import com.bandstudio.app.model.BAR_TICKS
import com.bandstudio.app.model.Clip
import com.bandstudio.app.model.Instrument
import com.bandstudio.app.model.MAX_BARS
import com.bandstudio.app.model.MAX_CLIP_BARS
import com.bandstudio.app.model.MAX_TRACKS
import com.bandstudio.app.model.NoteEvent
import com.bandstudio.app.model.PPQ
import com.bandstudio.app.model.Project
import com.bandstudio.app.model.ProjectStore
import com.bandstudio.app.model.Quantizer
import com.bandstudio.app.model.STEP_TICKS
import com.bandstudio.app.model.Track
import com.bandstudio.app.model.encodeForEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

sealed interface Screen {
    data object Home : Screen
    data object Studio : Screen
    data class Editor(val trackId: Int, val clipId: Int) : Screen
}

/**
 * Trạng thái ứng dụng + chuyển lệnh xuống engine.
 * Mọi lệnh gọi AudioEngine đều từ luồng chính (hàng đợi phía C++ chỉ có một người ghi).
 */
class StudioViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ProjectStore(File(app.filesDir, "projects"))

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    var projects by mutableStateOf<List<Project>>(emptyList())
        private set
    var project by mutableStateOf<Project?>(null)
        private set

    var playing by mutableStateOf(false)
        private set
    var metronome by mutableStateOf(true)
        private set
    var loopAll by mutableStateOf(false)
        private set
    var cursorTick by mutableIntStateOf(0)
        private set
    var playheadTick by mutableIntStateOf(0)
        private set
    var selectedClipId by mutableStateOf<Int?>(null)
        private set

    var gridStep by mutableIntStateOf(STEP_TICKS)
        private set
    var noteLen by mutableIntStateOf(STEP_TICKS)
        private set
    var recording by mutableStateOf(false)
        private set
    /** Bật: nốt ghi xong được căn vào lưới ngay. Tắt: giữ thời điểm thô, căn sau bằng nút Quantize. */
    var autoQuantize by mutableStateOf(true)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()
    private val held = HashMap<Int, Int>() // pitch -> tick (trong clip, chưa quantize) lúc bấm khi đang ghi
    private var graceUntilNanos = 0L
    private val saveChannel = Channel<Project>(Channel.CONFLATED)

    init {
        projects = store.list()
        viewModelScope.launch(Dispatchers.IO) {
            for (p in saveChannel) store.save(p)
        }
    }

    // =====================================================================================
    // Engine
    // =====================================================================================

    /** Đẩy lại toàn bộ trạng thái xuống engine (sau khi mở / mở lại luồng âm thanh). */
    fun syncEngine() {
        AudioEngine.setBpm((project?.bpm ?: 100).toFloat())
        AudioEngine.setMetronome(metronome)
        syncMix()
        publishSong()
    }

    private fun syncMix() {
        val tracks = project?.tracks.orEmpty()
        for (i in 0 until MAX_TRACKS) {
            val t = tracks.getOrNull(i)
            AudioEngine.setTrackMix(i, t?.volume ?: 0.8f, t?.pan ?: 0f, t?.mute ?: false, t?.solo ?: false)
        }
    }

    private fun publishSong() {
        val p = project ?: return
        val s = screen
        if (s is Screen.Editor) {
            val clip = p.track(s.trackId)?.clips?.firstOrNull { it.id == s.clipId }
            if (clip != null) { // trình soạn: lặp đúng đoạn clip đang sửa, vẫn nghe các track khác làm nền
                AudioEngine.setSong(p.encodeForEngine(true, clip.startBar * BAR_TICKS, clip.endBar * BAR_TICKS, 0))
                return
            }
        }
        val end = maxOf(p.endBar, 1) * BAR_TICKS
        AudioEngine.setSong(p.encodeForEngine(loopAll, 0, end, p.endBar * BAR_TICKS))
    }

    // =====================================================================================
    // Phát / dừng
    // =====================================================================================

    fun togglePlay() {
        if (playing) stopPlayback() else startPlayback()
    }

    fun startPlayback() {
        if (project == null) return
        publishSong()
        val from = (screen as? Screen.Editor)?.let { editorClip()?.let { c -> c.startBar * BAR_TICKS } } ?: cursorTick
        AudioEngine.play(from)
        playing = true
        playheadTick = from
        graceUntilNanos = System.nanoTime() + 400_000_000L // chờ engine nhận lệnh trước khi coi là "đã dừng"
    }

    fun stopPlayback() {
        AudioEngine.stopTransport()
        playing = false
        recording = false
        held.clear()
        playheadTick = cursorTick
    }

    /** Gọi mỗi khung hình từ giao diện: cập nhật vị trí đầu phát, phát hiện hết bài. */
    fun poll() {
        if (!playing) return
        if (AudioEngine.isPlaying()) {
            playheadTick = (AudioEngine.beatPosition() * PPQ).toInt()
        } else if (System.nanoTime() > graceUntilNanos) {
            playing = false
            recording = false
            playheadTick = cursorTick
        }
    }

    fun seekToBar(bar: Int) {
        cursorTick = bar.coerceIn(0, MAX_BARS) * BAR_TICKS
        if (playing) {
            AudioEngine.play(cursorTick)
            graceUntilNanos = System.nanoTime() + 400_000_000L
        }
        playheadTick = cursorTick
    }

    fun toggleMetronome() {
        metronome = !metronome
        AudioEngine.setMetronome(metronome)
    }

    fun toggleLoop() {
        loopAll = !loopAll
        publishSong()
    }

    fun setBpm(value: Int) {
        val bpm = value.coerceIn(40, 240)
        update { it.copy(bpm = bpm) }
        AudioEngine.setBpm(bpm.toFloat())
    }

    /** Ứng dụng xuống nền: dừng phát, lưu ngay, engine sẽ bị đóng. */
    fun onBackground() {
        stopPlayback()
        AudioEngine.allNotesOff()
        project?.let { store.save(it) }
    }

    // =====================================================================================
    // Điều hướng / dự án
    // =====================================================================================

    fun newProject() {
        val p = Project(
            id = UUID.randomUUID().toString().take(12),
            name = "Bản nhạc ${projects.size + 1}",
            updatedAt = System.currentTimeMillis(),
        )
        store.save(p)
        projects = store.list()
        openProject(p.id)
    }

    fun openProject(id: String) {
        val p = projects.firstOrNull { it.id == id } ?: return
        project = p
        undoStack.clear()
        redoStack.clear()
        updateUndoFlags()
        cursorTick = 0
        playheadTick = 0
        selectedClipId = null
        screen = Screen.Studio
        syncEngine()
    }

    fun closeProject() {
        stopPlayback()
        AudioEngine.allNotesOff()
        project?.let { store.save(it) }
        project = null
        projects = store.list()
        screen = Screen.Home
    }

    fun deleteProject(id: String) {
        store.delete(id)
        projects = store.list()
    }

    fun renameProject(name: String) {
        val n = name.trim().ifEmpty { return }
        update { it.copy(name = n.take(40)) }
    }

    /** Trả true nếu đã xử lý (quay lại màn hình trước); false nếu nên thoát ứng dụng. */
    fun back(): Boolean = when (screen) {
        is Screen.Editor -> { exitEditor(); true }
        Screen.Studio -> { closeProject(); true }
        Screen.Home -> false
    }

    fun openEditor(trackId: Int, clipId: Int) {
        stopPlayback()
        AudioEngine.allNotesOff()
        screen = Screen.Editor(trackId, clipId)
        publishSong()
    }

    fun exitEditor() {
        stopPlayback()
        AudioEngine.allNotesOff()
        screen = Screen.Studio
        publishSong()
    }

    // =====================================================================================
    // Chỉnh sửa dự án (có Undo / Redo)
    // =====================================================================================

    private fun applyProject(p: Project) {
        project = p
        saveChannel.trySend(p)
        syncMix()
        publishSong()
    }

    /** Thay đổi có thể hoàn tác. */
    private fun commit(f: (Project) -> Project) {
        val old = project ?: return
        val next = f(old)
        if (next == old) return
        undoStack.addLast(old)
        while (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear()
        applyProject(next.copy(updatedAt = System.currentTimeMillis()))
        updateUndoFlags()
    }

    /** Thay đổi liên tục (kéo thanh trượt, BPM): lưu nhưng không đưa vào lịch sử hoàn tác. */
    private fun update(f: (Project) -> Project) {
        val old = project ?: return
        applyProject(f(old).copy(updatedAt = System.currentTimeMillis()))
    }

    private fun updateUndoFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    fun undo() {
        val cur = project ?: return
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(cur)
        applyProject(prev)
        updateUndoFlags()
        fixSelection()
    }

    fun redo() {
        val cur = project ?: return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(cur)
        applyProject(next)
        updateUndoFlags()
        fixSelection()
    }

    private fun fixSelection() {
        val p = project ?: return
        val exists = { id: Int? -> id != null && p.tracks.any { t -> t.clips.any { it.id == id } } }
        if (!exists(selectedClipId)) selectedClipId = null
        val s = screen
        if (s is Screen.Editor && !exists(s.clipId)) exitEditor()
    }

    private fun mapTrack(p: Project, id: Int, f: (Track) -> Track) =
        p.copy(tracks = p.tracks.map { if (it.id == id) f(it) else it })

    private fun mapClip(p: Project, trackId: Int, clipId: Int, f: (Clip) -> Clip) =
        mapTrack(p, trackId) { t -> t.copy(clips = t.clips.map { if (it.id == clipId) f(it) else it }) }

    // ---- Track ----

    fun addTrack(inst: Instrument) = commit { p ->
        if (p.tracks.size >= MAX_TRACKS) return@commit p
        val same = p.tracks.count { it.instrument == inst }
        val name = if (same == 0) inst.label else "${inst.label} ${same + 1}"
        p.copy(tracks = p.tracks + Track(p.nextId, name, inst), nextId = p.nextId + 1)
    }

    fun deleteTrack(id: Int) {
        commit { p -> p.copy(tracks = p.tracks.filter { it.id != id }) }
        fixSelection()
    }

    fun setTrackVolume(id: Int, v: Float) = update { p -> mapTrack(p, id) { it.copy(volume = v.coerceIn(0f, 1.2f)) } }
    fun setTrackPan(id: Int, v: Float) = update { p -> mapTrack(p, id) { it.copy(pan = v.coerceIn(-1f, 1f)) } }
    fun toggleMute(id: Int) = update { p -> mapTrack(p, id) { it.copy(mute = !it.mute) } }
    fun toggleSolo(id: Int) = update { p -> mapTrack(p, id) { it.copy(solo = !it.solo) } }

    // ---- Clip ----

    fun selectClip(id: Int?) {
        selectedClipId = id
    }

    /** Tạo clip trống tại ô nhịp [bar] (2 ô nhịp, hoặc 1 nếu chỉ còn chỗ cho 1). */
    fun addClip(trackId: Int, bar: Int, bars: Int = 2) {
        var created = -1
        commit { p ->
            val t = p.track(trackId) ?: return@commit p
            val len = if (t.isFree(bar, bars)) bars else if (t.isFree(bar, 1)) 1 else return@commit p
            created = p.nextId
            mapTrack(p, trackId) { it.copy(clips = it.clips + Clip(p.nextId, bar, len)) }.copy(nextId = p.nextId + 1)
        }
        if (created >= 0) selectedClipId = created
    }

    /** Nhân đôi clip vào chỗ trống đầu tiên phía sau nó (cách nhanh nhất để "ghép" lặp một beat). */
    fun duplicateClip(trackId: Int, clipId: Int) {
        var created = -1
        commit { p ->
            val t = p.track(trackId) ?: return@commit p
            val c = t.clips.firstOrNull { it.id == clipId } ?: return@commit p
            val bar = (c.endBar..(MAX_BARS - c.bars)).firstOrNull { t.isFree(it, c.bars) } ?: return@commit p
            created = p.nextId
            mapTrack(p, trackId) { it.copy(clips = it.clips + c.copy(id = p.nextId, startBar = bar)) }
                .copy(nextId = p.nextId + 1)
        }
        if (created >= 0) selectedClipId = created
    }

    fun deleteClip(trackId: Int, clipId: Int) {
        commit { p -> mapTrack(p, trackId) { t -> t.copy(clips = t.clips.filter { it.id != clipId }) } }
        fixSelection()
    }

    fun moveClip(trackId: Int, clipId: Int, deltaBars: Int) = commit { p ->
        val t = p.track(trackId) ?: return@commit p
        val c = t.clips.firstOrNull { it.id == clipId } ?: return@commit p
        val bar = c.startBar + deltaBars
        if (!t.isFree(bar, c.bars, c.id)) p else mapClip(p, trackId, clipId) { it.copy(startBar = bar) }
    }

    fun resizeClip(trackId: Int, clipId: Int, deltaBars: Int) = commit { p ->
        val t = p.track(trackId) ?: return@commit p
        val c = t.clips.firstOrNull { it.id == clipId } ?: return@commit p
        val bars = (c.bars + deltaBars).coerceIn(1, MAX_CLIP_BARS)
        if (!t.isFree(c.startBar, bars, c.id)) p else mapClip(p, trackId, clipId) { it.copy(bars = bars) }
    }

    // =====================================================================================
    // Trình soạn clip
    // =====================================================================================

    fun editorTrack(): Track? = (screen as? Screen.Editor)?.let { project?.track(it.trackId) }

    fun editorClip(): Clip? =
        (screen as? Screen.Editor)?.let { s -> project?.track(s.trackId)?.clips?.firstOrNull { it.id == s.clipId } }

    fun chooseGrid(step: Int) {
        gridStep = step
        if (noteLen < step) noteLen = step
    }

    fun setNoteLength(len: Int) {
        noteLen = len
    }

    fun resizeEditorClip(deltaBars: Int) {
        val s = screen as? Screen.Editor ?: return
        resizeClip(s.trackId, s.clipId, deltaBars)
    }

    fun toggleAutoQuantize() {
        autoQuantize = !autoQuantize
    }

    /** Căn toàn bộ nốt của clip vào lưới đang chọn (1/4, 1/8, 1/16, 1/32). */
    fun quantizeEditorClip() {
        val s = screen as? Screen.Editor ?: return
        val drum = editorTrack()?.instrument?.isDrum == true
        val step = if (drum) STEP_TICKS else gridStep
        commit { p ->
            mapClip(p, s.trackId, s.clipId) { c -> c.copy(notes = Quantizer.quantizeNotes(c.notes, step, c.lengthTicks)) }
        }
    }

    fun clearEditorClip() {
        val s = screen as? Screen.Editor ?: return
        commit { p -> mapClip(p, s.trackId, s.clipId) { it.copy(notes = emptyList()) } }
    }

    /** Chạm vào ô Piano Roll: có nốt thì xóa, chưa có thì thêm nốt (dài [noteLen], căn theo lưới). */
    fun tapRollCell(pitch: Int, tick: Int) {
        val s = screen as? Screen.Editor ?: return
        val clip = editorClip() ?: return
        val hit = clip.notes.firstOrNull { it.pitch == pitch && tick >= it.start && tick < it.start + it.dur }
        if (hit != null) {
            commit { p -> mapClip(p, s.trackId, s.clipId) { it.copy(notes = it.notes - hit) } }
            return
        }
        val start = (tick / gridStep) * gridStep
        val dur = minOf(noteLen, clip.lengthTicks - start)
        if (dur <= 0) return
        commit { p -> mapClip(p, s.trackId, s.clipId) { it.copy(notes = it.notes + NoteEvent(start, dur, pitch)) } }
        audition(pitch)
    }

    /** Chạm vào ô Drum Sequencer (luôn lưới 1/16). */
    fun toggleDrumStep(piece: Int, step: Int) {
        val s = screen as? Screen.Editor ?: return
        val tick = step * STEP_TICKS
        val clip = editorClip() ?: return
        if (tick >= clip.lengthTicks) return
        val existing = clip.notes.firstOrNull { it.pitch == piece && it.start == tick }
        if (existing != null) {
            commit { p -> mapClip(p, s.trackId, s.clipId) { it.copy(notes = it.notes - existing) } }
        } else {
            commit { p -> mapClip(p, s.trackId, s.clipId) { it.copy(notes = it.notes + NoteEvent(tick, STEP_TICKS, piece)) } }
            audition(piece)
        }
    }

    // ---- Chơi trực tiếp bằng phím / pad, có thể ghi vào clip ----

    fun keyDown(pitch: Int) {
        val t = editorTrack() ?: return
        val idx = project?.trackIndex(t.id) ?: return
        AudioEngine.noteOn(t.instrument.id, idx, pitch, 100)
        if (recording && playing) {
            val clip = editorClip() ?: return
            val tick = heardTickInClip(clip)
            if (t.instrument.isDrum) addRecordedNote(pitch, tick, STEP_TICKS, true) else held[pitch] = tick
        }
    }

    fun keyUp(pitch: Int) {
        val t = editorTrack() ?: return
        val idx = project?.trackIndex(t.id) ?: return
        AudioEngine.noteOff(idx, pitch)
        val start = held.remove(pitch) ?: return
        val clip = editorClip() ?: return
        var dur = heardTickInClip(clip) - start
        if (dur < 0) dur += clip.lengthTicks
        addRecordedNote(pitch, start, dur, false)
    }

    fun toggleRecord() {
        if (recording) {
            recording = false
            held.clear()
        } else {
            recording = true
            if (!playing) startPlayback()
            recording = true // startPlayback không đổi cờ này, nhưng giữ rõ ràng
        }
    }

    private fun audition(pitch: Int) {
        val t = editorTrack() ?: return
        val idx = project?.trackIndex(t.id) ?: return
        AudioEngine.noteOn(t.instrument.id, idx, pitch, 100)
        viewModelScope.launch {
            delay(250)
            AudioEngine.noteOff(idx, pitch)
        }
    }

    /**
     * Vị trí "đang nghe thấy" trong clip, tính bằng tick. Vị trí engine công bố là ở cuối khối đã dựng,
     * còn tai nghe trễ thêm độ trễ đầu ra, nên trừ độ trễ này để nốt ghi rơi đúng chỗ người chơi cảm nhận.
     */
    private fun heardTickInClip(clip: Clip): Int {
        val bpm = project?.bpm ?: 100
        val latencyMs = AudioEngine.latencyMs().coerceAtLeast(0.0)
        val beats = AudioEngine.beatPosition() - latencyMs / 1000.0 * bpm / 60.0
        val tick = (beats * PPQ).roundToInt() - clip.startBar * BAR_TICKS
        return Math.floorMod(tick, clip.lengthTicks)
    }

    private fun addRecordedNote(pitch: Int, rawStart: Int, rawDur: Int, drum: Boolean) {
        val s = screen as? Screen.Editor ?: return
        val clip = editorClip() ?: return
        val step = if (drum) STEP_TICKS else gridStep
        val start: Int
        val dur: Int
        if (autoQuantize || drum) {
            start = Quantizer.snapStart(rawStart, step, clip.lengthTicks)
            dur = Quantizer.snapDuration(rawDur, step, clip.lengthTicks - start)
        } else { // ghi thô: giữ nguyên thời điểm người chơi bấm, có thể Quantize sau
            start = rawStart.coerceIn(0, clip.lengthTicks - 1)
            dur = rawDur.coerceIn(1, clip.lengthTicks - start)
        }
        commit { p ->
            mapClip(p, s.trackId, s.clipId) { c ->
                c.copy(notes = c.notes.filterNot { it.pitch == pitch && it.start == start } + NoteEvent(start, dur, pitch))
            }
        }
    }
}
