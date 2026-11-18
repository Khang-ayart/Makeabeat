package com.bandstudio.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bandstudio.app.audio.AudioEngine
import com.bandstudio.app.model.BAR_TICKS
import com.bandstudio.app.model.Clip
import com.bandstudio.app.model.Genre
import com.bandstudio.app.model.Instrument
import com.bandstudio.app.model.MAX_BARS
import com.bandstudio.app.model.MAX_CLIP_BARS
import com.bandstudio.app.model.MAX_TRACKS
import com.bandstudio.app.model.PADS_PER_ROW
import com.bandstudio.app.model.Catalog
import com.bandstudio.app.model.DEFAULT_KEY
import com.bandstudio.app.model.PAD_COUNT
import com.bandstudio.app.model.Patterns
import com.bandstudio.app.model.Randomizer
import com.bandstudio.app.model.SoundCat
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
    /** Lưới Looper: pad là nhịp lặp, bật/tắt bám theo đồng hồ chung. */
    data class Launcher(val trackId: Int) : Screen
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

    // ---- Lưới Looper ----
    /** Bit 0-19: pad đang chạy; 20-39: chờ bật; 40-59: chờ tắt (lấy từ engine mỗi khung hình). */
    var padMask by mutableLongStateOf(0L)
        private set
    var launcherRecording by mutableStateOf(false)
        private set
    private var launcherRecStartTick = 0
    private val launcherEvents = ArrayList<IntArray>() // {tick, track, kitIdx, vel}
    private val eventBuf = IntArray(5)
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
        if (s is Screen.Launcher) { // chạy tự do, không có vùng lặp: pad tự lặp theo từng ô nhịp
            AudioEngine.setSong(p.encodeForEngine(false, 0, 0, 0, launcherTrackId = s.trackId))
            return
        }
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
        if (screen is Screen.Launcher) pollLauncher()
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
        if (launcherRecording) finishLauncherRecording()
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
        is Screen.Launcher -> { exitLauncher(); true }
        Screen.Studio -> { closeProject(); true }
        Screen.Home -> false
    }

    fun openEditor(trackId: Int, clipId: Int) {
        stopPlayback()
        AudioEngine.allNotesOff()
        screen = Screen.Editor(trackId, clipId)
        publishSong()
    }

    // =====================================================================================
    // Lưới Looper
    // =====================================================================================

    fun openLauncher(trackId: Int) {
        val p = project ?: return
        if (p.track(trackId)?.instrument?.isLooper != true) return
        stopPlayback()
        AudioEngine.allNotesOff()
        launcherRecording = false
        padMask = 0L
        screen = Screen.Launcher(trackId)
        publishSong()
    }

    /** Thêm track Looper theo thể loại rồi mở thẳng lưới pad. */
    fun addLooperAndOpen(genre: Genre) {
        addTrack(Instrument.LOOPER, genre)
        val t = project?.tracks?.lastOrNull { it.instrument.isLooper } ?: return
        openLauncher(t.id)
        // Đợi engine nhận bản nhạc mới (một vài buffer) rồi mới bật beat để không lỡ nhịp đầu.
        starterAtNanos = System.nanoTime() + 250_000_000L
    }

    fun exitLauncher() {
        starterAtNanos = 0L
        if (launcherRecording) finishLauncherRecording()
        AudioEngine.setPadRecording(false)
        stopPlayback()
        AudioEngine.allNotesOff()
        padMask = 0L
        screen = Screen.Studio
        publishSong()
    }

    private fun launcherIndex(): Int {
        val s = screen as? Screen.Launcher ?: return -1
        return project?.trackIndex(s.trackId) ?: -1
    }

    /**
     * Chạm pad: chưa chạy thì bật (vào đầu ô nhịp kế, hoặc ngay nếu đang dừng), đang chạy thì tắt.
     * Mỗi hàng chỉ một pad; bật pad khác trong hàng thì pad cũ nhường chỗ ở đầu ô nhịp kế.
     */
    fun tapPad(pad: Int) {
        val idx = launcherIndex()
        if (idx < 0 || pad !in 0 until PAD_COUNT) return
        val row = pad / PADS_PER_ROW
        val m = padMask
        val active = (m shr pad) and 1L == 1L
        val queuedOff = active && (m shr (24 + pad)) and 1L == 1L
        when {
            active && !queuedOff -> AudioEngine.padLaunch(idx, row, -1)
            else -> AudioEngine.padLaunch(idx, row, pad) // chưa chạy, hoặc đang chờ tắt -> huỷ lệnh tắt
        }
        startLauncherTransport()
    }

    /** Nút "Ngẫu nhiên": chọn tổ hợp pad hợp thể loại, vào ở đầu ô nhịp kế tiếp. */
    fun randomizeLauncher() {
        val idx = launcherIndex()
        if (idx < 0) return
        val picks = Randomizer.pick(kotlin.random.Random(System.nanoTime()))
        picks.forEachIndexed { row, pad -> AudioEngine.padLaunch(idx, row, pad) }
        startLauncherTransport()
    }

    private val auditioned = ArrayList<Int>()

    /** Giữ pad để nghe thử âm của nó (không vào beat). Keyboard phát hợp âm đầu của vòng; thả ra thì nhả phím. */
    fun auditionPad(pad: Int, down: Boolean) {
        val idx = launcherIndex()
        if (idx < 0 || pad !in 0 until PAD_COUNT) return
        val t = project?.tracks?.getOrNull(idx) ?: return
        if (down) {
            if (Patterns.isKeyPad(pad)) {
                val g = Catalog.genre(t.genreId) ?: return
                val notes = Patterns.chordNotes(t.keyRoot, Patterns.progression(g.minor, pad)[0])
                for (n in notes) { AudioEngine.noteOn(Instrument.LOOPER.id, idx, Patterns.KEY_NOTE_BASE + n, 90); auditioned.add(n) }
            } else {
                AudioEngine.noteOn(Instrument.LOOPER.id, idx, pad, 110)
            }
        } else {
            for (n in auditioned) AudioEngine.noteOff(idx, n)
            auditioned.clear()
        }
    }

    private fun startLauncherTransport() {
        if (playing) return
        AudioEngine.play(0)
        playing = true
        playheadTick = 0
        cursorTick = 0
        graceUntilNanos = System.nanoTime() + 400_000_000L
        if (launcherRecording) launcherRecStartTick = 0
    }

    // Beat gợi ý cho người mới: kick + hi-hat + bass (cột đầu, nhịp chính) chạy sẵn ngay khi mở track Looper mới.
    private var starterAtNanos = 0L

    private fun launchStarterBeat() {
        val idx = launcherIndex()
        if (idx < 0) return
        for (row in intArrayOf(SoundCat.KICK.ordinal, SoundCat.HAT.ordinal, SoundCat.BASS.ordinal)) {
            AudioEngine.padLaunch(idx, row, row * PADS_PER_ROW)
        }
        startLauncherTransport()
    }

    fun launcherStopAll() {
        if (launcherRecording) finishLauncherRecording()
        stopPlayback()
        AudioEngine.allNotesOff()
        padMask = 0L
    }

    fun toggleLauncherRecord() {
        if (launcherRecording) {
            finishLauncherRecording()
            return
        }
        launcherEvents.clear()
        while (AudioEngine.popPadEvent(eventBuf)) { /* bỏ sự kiện cũ */ }
        // Đang chạy: bắt đầu ghi từ đầu ô nhịp kế để clip khớp ô nhịp. Đang dừng: ghi từ lúc pad đầu tiên được bật.
        launcherRecStartTick = if (playing) {
            val now = (AudioEngine.beatPosition() * PPQ).toInt()
            (now / BAR_TICKS + 1) * BAR_TICKS
        } else 0
        AudioEngine.setPadRecording(true)
        launcherRecording = true
    }

    private fun pollLauncher() {
        val idx = launcherIndex()
        if (idx < 0) return
        if (starterAtNanos != 0L && System.nanoTime() >= starterAtNanos) {
            starterAtNanos = 0L
            launchStarterBeat()
        }
        padMask = AudioEngine.padMask(idx)
        while (AudioEngine.popPadEvent(eventBuf)) {
            if (launcherRecording && eventBuf[1] == idx && eventBuf[0] >= launcherRecStartTick) launcherEvents.add(eventBuf.copyOf())
        }
    }

    /** Kết thúc ghi: biến các cú đánh thành clip(s) (mỗi clip tối đa 8 ô nhịp) đặt sau clip cuối của track. */
    private fun finishLauncherRecording() {
        val s = screen as? Screen.Launcher ?: return
        pollLauncher()
        launcherRecording = false
        AudioEngine.setPadRecording(false)
        val startTick = launcherRecStartTick
        val nowTick = if (playing) (AudioEngine.beatPosition() * PPQ).toInt() else startTick
        val lastEvent = launcherEvents.maxOfOrNull { it[0] } ?: startTick
        // Ô nhịp nào đã trôi qua kể từ lúc ghi (kể cả khoảng lặng ở cuối) đều nằm trong clip.
        val endTick = maxOf(nowTick, lastEvent + 1)
        val bars = ((endTick - startTick + BAR_TICKS - 1) / BAR_TICKS).coerceIn(1, MAX_BARS)
        if (launcherEvents.isEmpty()) return
        val events = launcherEvents.toList()
        launcherEvents.clear()
        commit { p ->
            val track = p.track(s.trackId) ?: return@commit p
            var nextId = p.nextId
            var bar = track.clips.maxOfOrNull { it.endBar } ?: 0
            val clips = ArrayList<Clip>()
            var done = 0
            while (done < bars && bar < MAX_BARS) {
                val n = minOf(MAX_CLIP_BARS, bars - done, MAX_BARS - bar)
                val lo = startTick + done * BAR_TICKS
                val hi = lo + n * BAR_TICKS
                val notes = events.filter { it[0] in lo until hi }
                    .map { NoteEvent(it[0] - lo, if (it[4] > 0) it[4] else STEP_TICKS, it[2], it[3]) }
                    .distinctBy { it.pitch to it.start }
                    .sortedBy { it.start }
                clips.add(Clip(nextId++, bar, n, notes))
                bar += n
                done += n
            }
            p.copy(tracks = p.tracks.map { if (it.id == s.trackId) it.copy(clips = it.clips + clips) else it }, nextId = nextId)
        }
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
        AudioEngine.setBpm(p.bpm.toFloat())  // BPM có thể đổi do thêm Looper đầu tiên hoặc hoàn tác
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

    /** [genre] chỉ dùng cho Looper: quyết định 20 pad (5 hàng x 4 lựa chọn). Track đầu tiên của bài sẽ lấy BPM gợi ý của thể loại. */
    fun addTrack(inst: Instrument, genre: Genre? = null) = commit { p ->
        if (p.tracks.size >= MAX_TRACKS) return@commit p
        val same = p.tracks.count { it.instrument == inst }
        val base = if (inst.isLooper && genre != null) "Looper · ${genre.label}" else inst.label
        val name = if (same == 0) base else "$base ${same + 1}"
        val track = if (inst.isLooper && genre != null) {
            Track(p.nextId, name, inst, genreId = genre.id, kit = genre.kit(DEFAULT_KEY))
        } else {
            Track(p.nextId, name, inst)
        }
        val bpm = if (p.tracks.isEmpty() && inst.isLooper && genre != null) genre.bpm else p.bpm
        p.copy(tracks = p.tracks + track, nextId = p.nextId + 1, bpm = bpm)
    }

    /** Đổi thể loại của track Looper: nốt giữ nguyên (theo số pad), âm của từng pad đổi theo thể loại mới. */
    fun setLooperGenre(trackId: Int, genre: Genre) = commit { p ->
        mapTrack(p, trackId) { t ->
            if (!t.instrument.isLooper) t
            else t.copy(genreId = genre.id, kit = genre.kit(t.keyRoot), name = "Looper · ${genre.label}")
        }
    }

    /** Đổi tông chung của track Looper: bass và hợp âm Keyboard đi theo. */
    fun setLooperKey(trackId: Int, key: Int) = commit { p ->
        mapTrack(p, trackId) { t ->
            val g = Catalog.genre(t.genreId)
            if (!t.instrument.isLooper || g == null) t
            else t.copy(keyRoot = Math.floorMod(key, 12), kit = g.kit(Math.floorMod(key, 12)))
        }
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
        val drum = editorTrack()?.instrument?.oneShot == true
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
            if (t.instrument.oneShot) addRecordedNote(pitch, tick, STEP_TICKS, true) else held[pitch] = tick
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
