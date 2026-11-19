package com.bandstudio.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bandstudio.app.audio.AudioEngine
import com.bandstudio.app.audio.VocalRecorder
import com.bandstudio.app.model.Arranger
import com.bandstudio.app.model.VOCAL_SLOT
import com.bandstudio.app.model.VocalClip
import com.bandstudio.app.model.Wav
import com.bandstudio.app.model.BAR_TICKS
import com.bandstudio.app.model.Clip
import com.bandstudio.app.model.Genre
import com.bandstudio.app.model.Instrument
import com.bandstudio.app.model.KEY_NAMES
import com.bandstudio.app.model.MAX_BARS
import com.bandstudio.app.model.MAX_CLIP_BARS
import com.bandstudio.app.model.MAX_TRACKS
import com.bandstudio.app.model.PADS_PER_ROW
import com.bandstudio.app.model.Catalog
import com.bandstudio.app.model.DEFAULT_KEY
import com.bandstudio.app.model.PAD_COUNT
import com.bandstudio.app.model.PENDING_SHIFT
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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

sealed interface Screen {
    data object Home : Screen
    data object Studio : Screen
    data class Editor(val trackId: Int, val clipId: Int) : Screen
    /** Lưới Looper: pad là nhịp lặp, bật/tắt bám theo đồng hồ chung. */
    data class Launcher(val trackId: Int) : Screen
    /** Thu giọng, lọc ồn, auto-tune, nghe lại. */
    data object Vocal : Screen
    /** Trộn các track, hiệu ứng giọng, "Tạo beat". */
    data object Mixer : Screen
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
            val muteForTake = vocalRecording && t?.instrument?.isVocal == true  // đang thu đè: không phát lại giọng cũ
            AudioEngine.setTrackMix(i, t?.volume ?: 0.8f, t?.pan ?: 0f, (t?.mute ?: false) || muteForTake, t?.solo ?: false)
            t?.vocal?.let { AudioEngine.setVocalFx(i, it.reverb, it.comp, it.bright) }
        }
    }

    private fun publishSong() {
        val p = project ?: return
        for (i in 0 until MAX_TRACKS) AudioEngine.setSynthStyle(i, Patterns.synthStyleOf(p.tracks.getOrNull(i)?.genreId ?: ""))
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
        pollVocal()
        if (!playing) return
        if (AudioEngine.isPlaying()) {
            playheadTick = (AudioEngine.beatPosition() * PPQ).toInt().coerceAtLeast(0)
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
        loadVocalFromProject()
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
        Screen.Vocal, Screen.Mixer -> { leaveToStudio(); true }
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
        val queuedOff = active && (m shr (PENDING_SHIFT + pad)) and 1L == 1L
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
                // Nghe thử hợp âm đầu của vòng, bằng đúng giọng của hàng (piano / synth lead / synth pad).
                val lift = if (SoundCat.entries[pad / PADS_PER_ROW] == SoundCat.LEAD) 12 else 0
                val notes = Patterns.chordNotes(t.keyRoot, Patterns.progression(g.minor, pad)[0]).map { it + lift }
                for (n in notes) { AudioEngine.noteOn(Instrument.LOOPER.id, idx, Patterns.noteBase(pad) + n, 90); auditioned.add(n) }
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

    // =====================================================================================
    // Vocal: thu âm, lọc ồn, auto-tune, nghe lại
    // =====================================================================================

    private val vocalDir = File(app.filesDir, "vocals")
    private val recorder = VocalRecorder()

    var vocalRecording by mutableStateOf(false)
        private set
    /** Đang xử lý bản thu (lọc ồn / auto-tune / nạp vào engine). */
    var vocalBusy by mutableStateOf(false)
        private set
    var vocalLevel by mutableFloatStateOf(0f)
        private set
    var vocalSeconds by mutableFloatStateOf(0f)
        private set
    var vocalCountIn by mutableStateOf(false)
        private set
    var vocalMessage by mutableStateOf<String?>(null)
    /** Tông đoán được từ giọng, ví dụ "Am" (để hiện cho người dùng). */
    var detectedKey by mutableStateOf<String?>(null)
        private set

    private var countInEndNanos = 0L
    private var recordPlayNanos = 0L
    private var recordCountInMs = 0
    private var vocalSamples: FloatArray? = null     // bản đã xử lý đang nằm trong engine (đủ độ dài, chưa cắt đầu)
    private var vocalSamplesFile: String? = null

    private fun vocalClip(): VocalClip? = project?.tracks?.firstOrNull { it.instrument.isVocal }?.vocal
    private fun vocalTrackId(): Int? = project?.tracks?.firstOrNull { it.instrument.isVocal }?.id

    fun openVocal() {
        if (project == null) return
        stopPlayback()
        AudioEngine.allNotesOff()
        vocalMessage = null
        screen = Screen.Vocal
        publishSong()
    }

    fun openMixer() {
        if (project == null) return
        stopPlayback()
        AudioEngine.allNotesOff()
        screen = Screen.Mixer
        publishSong()
    }

    fun leaveToStudio() {
        if (vocalRecording) stopVocalRecording()
        stopPlayback()
        AudioEngine.allNotesOff()
        screen = Screen.Studio
        publishSong()
    }

    private fun pollVocal() {
        if (vocalRecording) {
            vocalLevel = recorder.level
            vocalSeconds = recorder.seconds
            if (vocalCountIn && System.nanoTime() >= countInEndNanos) {
                vocalCountIn = false
                AudioEngine.setMetronome(metronome)  // hết đếm nhịp: trả metronome về ý người dùng
            }
            if (!recorder.running) stopVocalRecording()  // chạm trần độ dài
        }
    }

    /**
     * Bắt đầu thu: micro mở trước, rồi đếm nhịp 1 ô (metronome) và phát beat từ ô 1. Cần quyền micro (giao diện xin trước).
     * Giọng cũ (nếu có) tạm im để thu đè.
     */
    fun startVocalRecording(): Boolean {
        val p = project ?: return false
        if (vocalRecording || vocalBusy) return false
        if (vocalTrackId() == null && p.tracks.size >= MAX_TRACKS) {
            vocalMessage = "Đã đủ $MAX_TRACKS track, xóa bớt một track trước khi thu giọng."
            return false
        }
        stopPlayback()
        publishSong()
        vocalRecording = true
        syncMix()  // tắt tiếng giọng cũ trong lúc thu
        if (!recorder.start()) {
            vocalRecording = false
            syncMix()
            vocalMessage = "Không mở được micro. Kiểm tra quyền ghi âm của ứng dụng."
            return false
        }
        vocalMessage = null
        recordCountInMs = (240_000.0 / p.bpm).roundToInt()
        AudioEngine.setMetronome(true)
        AudioEngine.play(-BAR_TICKS)
        recordPlayNanos = System.nanoTime()
        playing = true
        playheadTick = 0
        graceUntilNanos = recordPlayNanos + 600_000_000L
        countInEndNanos = recordPlayNanos + recordCountInMs * 1_000_000L
        vocalCountIn = true
        return true
    }

    /** Dừng thu, rồi xử lý nền: lưu bản gốc, lọc ồn (mặc định bật), auto-tune nếu chọn, đoán tông, nạp vào engine. */
    fun stopVocalRecording() {
        if (!vocalRecording) return
        val data = recorder.stop()
        val startDelayMs = ((recordPlayNanos - recorder.startedNanos) / 1_000_000.0)
        stopPlayback()
        AudioEngine.setMetronome(metronome)
        vocalCountIn = false
        vocalRecording = false
        syncMix()
        if (data == null) {
            vocalMessage = "Bản thu quá ngắn, thử lại nhé."
            return
        }
        // Điểm giây 0 của bài trong bản thu = đếm nhịp + độ lệch giữa lúc mở micro và lúc engine phát + trễ loa + trễ micro.
        val outLat = AudioEngine.latencyMs().let { if (it > 0) it else 45.0 }
        val sync = (recordCountInMs + startDelayMs + 10.0 + outLat + IN_LATENCY_MS).roundToInt().coerceIn(0, 8000)
        val prev = vocalClip()
        val id = System.currentTimeMillis().toString(36)
        val clip = VocalClip(
            rawFile = "$id-raw.wav", file = "$id.wav", sampleRate = recorder.sampleRate,
            lengthMs = (data.size * 1000L / recorder.sampleRate).toInt(), syncMs = sync,
            denoise = prev?.denoise ?: true, tune = prev?.tune ?: false,
            tuneStrength = prev?.tuneStrength ?: 0.85f, tuneSpeed = prev?.tuneSpeed ?: 0.6f, tuneChromatic = prev?.tuneChromatic ?: false,
            reverb = prev?.reverb ?: 0.22f, comp = prev?.comp ?: 0.6f, bright = prev?.bright ?: 0.4f, gain = prev?.gain ?: 1.0f,
        )
        vocalBusy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    vocalDir.mkdirs()
                    File(vocalDir, clip.rawFile).writeBytes(Wav.encode16(data, clip.sampleRate))
                    val key = AudioEngine.detectKey(data, clip.sampleRate)
                    val keep = project ?: return@withContext null
                    val processed = renderVocal(clip, keep.keyRoot, keep.minor)
                    Pair(processed, key)
                } catch (e: Exception) {
                    null
                }
            }
            if (result == null || result.first == null) {
                vocalMessage = "Xử lý bản thu thất bại. Thử thu lại."
            } else {
                val k = result.second
                val confident = k.size >= 4 && k[2] >= 450 && k[3] >= 2000
                if (confident) detectedKey = KEY_NAMES[k[0]] + if (k[1] == 1) "m" else ""
                commitVocal(clip, if (confident) Pair(k[0], k[1] == 1) else null)
                vocalSamples = result.first
                vocalSamplesFile = clip.file
                withContext(Dispatchers.Default) { pushVocalToEngine(clip) }
                val old = prev
                if (old != null) { File(vocalDir, old.rawFile).delete(); File(vocalDir, old.file).delete() }
                vocalMessage = if (confident) "Đã thu xong. Tông đoán được: $detectedKey" else "Đã thu xong."
            }
            vocalBusy = false
        }
    }

    /** Chạy lại xử lý từ bản gốc với lựa chọn hiện tại (lọc ồn, auto-tune, tông). */
    private fun renderVocal(clip: VocalClip, key: Int, minor: Boolean): FloatArray? {
        val raw = Wav.parse(File(vocalDir, clip.rawFile).readBytes()) ?: return null
        val x = raw.samples
        if (clip.denoise) AudioEngine.denoiseVocal(x, raw.sampleRate, 0.6f, 0)
        if (clip.tune) AudioEngine.autoTuneVocal(x, raw.sampleRate, key, minor, clip.tuneChromatic, clip.tuneStrength, clip.tuneSpeed)
        var peak = 0f
        for (v in x) { val a = if (v < 0) -v else v; if (a > peak) peak = a }
        if (peak > 1e-4f) {  // chuẩn hóa đỉnh về ~ -1 dBFS (tối đa nâng 12 lần) để mọi bản thu có mức tương đương nhau
            val g = minOf(0.89f / peak, 12f)
            for (i in x.indices) x[i] *= g
        }
        File(vocalDir, clip.file).writeBytes(Wav.encode16(x, raw.sampleRate))
        return x
    }

    /** Cắt phần đầu theo độ dịch rồi nạp vào ô giọng của engine (luồng nền). */
    private fun pushVocalToEngine(clip: VocalClip) {
        val x = vocalSamples ?: return
        if (x.size < 4) return
        val start = (clip.trimMs / 1000.0 * clip.sampleRate).toInt().coerceIn(0, x.size - 2)
        AudioEngine.loadVocal(VOCAL_SLOT, if (start == 0) x else x.copyOfRange(start, x.size), clip.sampleRate)
    }

    private fun commitVocal(clip: VocalClip, key: Pair<Int, Boolean>?) {
        commit { p ->
            val existing = p.tracks.firstOrNull { it.instrument.isVocal }
            var np = when {
                existing != null -> mapTrack(p, existing.id) { it.copy(vocal = clip) }
                p.tracks.size >= MAX_TRACKS -> p
                else -> p.copy(tracks = p.tracks + Track(p.nextId, "Vocal", Instrument.VOCAL, volume = 0.9f, vocal = clip), nextId = p.nextId + 1)
            }
            if (key != null) np = np.copy(keyRoot = key.first, minor = key.second)
            np
        }
    }

    /** Mở dự án: nạp lại giọng (nếu có) vào engine. */
    private fun loadVocalFromProject() {
        val clip = vocalClip()
        if (clip == null) {
            vocalSamples = null
            vocalSamplesFile = null
            AudioEngine.clearVocal(VOCAL_SLOT)
            return
        }
        if (vocalSamplesFile == clip.file && vocalSamples != null) return
        viewModelScope.launch {
            vocalBusy = true
            withContext(Dispatchers.Default) {
                try {
                    val f = File(vocalDir, clip.file)
                    val w = if (f.exists()) Wav.parse(f.readBytes()) else null
                    if (w != null) {
                        vocalSamples = w.samples
                        vocalSamplesFile = clip.file
                        pushVocalToEngine(clip)
                    }
                } catch (e: Exception) { /* thiếu tệp: bỏ qua, track vẫn mở được */ }
            }
            vocalBusy = false
        }
    }

    /** Đổi lựa chọn xử lý rồi chạy lại từ bản gốc. */
    fun applyVocalOptions(denoise: Boolean, tune: Boolean, strength: Float, speed: Float, chromatic: Boolean) {
        val clip = vocalClip() ?: return
        val p = project ?: return
        if (vocalBusy || vocalRecording) return
        val next = clip.copy(denoise = denoise, tune = tune, tuneStrength = strength, tuneSpeed = speed, tuneChromatic = chromatic)
        vocalBusy = true
        viewModelScope.launch {
            val x = withContext(Dispatchers.Default) {
                try { renderVocal(next, p.keyRoot, p.minor) } catch (e: Exception) { null }
            }
            if (x == null) {
                vocalMessage = "Không đọc được bản thu gốc."
            } else {
                vocalSamples = x
                vocalSamplesFile = next.file
                withContext(Dispatchers.Default) { pushVocalToEngine(next) }
                setVocalClip(next)
                vocalMessage = "Đã áp dụng."
            }
            vocalBusy = false
        }
    }

    private fun setVocalClip(c: VocalClip) {
        val id = vocalTrackId() ?: return
        update { p -> mapTrack(p, id) { it.copy(vocal = c) } }
    }

    /** Dịch giọng sớm (+) / muộn (-) so với beat, tính bằng ms. */
    fun nudgeVocal(deltaMs: Int) {
        val clip = vocalClip() ?: return
        if (vocalBusy || vocalRecording) return
        val next = clip.copy(nudgeMs = (clip.nudgeMs + deltaMs).coerceIn(-1500, 1500))
        setVocalClip(next)
        viewModelScope.launch(Dispatchers.Default) { pushVocalToEngine(next) }
    }

    fun setVocalMix(reverb: Float? = null, comp: Float? = null, bright: Float? = null, gain: Float? = null) {
        val clip = vocalClip() ?: return
        setVocalClip(clip.copy(
            reverb = reverb ?: clip.reverb, comp = comp ?: clip.comp, bright = bright ?: clip.bright,
            gain = (gain ?: clip.gain).coerceIn(0.1f, 1.5f),
        ))
    }

    fun deleteVocal() {
        val id = vocalTrackId() ?: return
        val clip = vocalClip()
        stopPlayback()
        commit { p -> p.copy(tracks = p.tracks.filter { it.id != id }) }
        AudioEngine.clearVocal(VOCAL_SLOT)
        vocalSamples = null
        vocalSamplesFile = null
        detectedKey = null
        if (clip != null) viewModelScope.launch(Dispatchers.IO) { File(vocalDir, clip.rawFile).delete(); File(vocalDir, clip.file).delete() }
    }

    /** Tông và thang của bài (dùng cho auto-tune và "Tạo beat"). */
    fun setKey(root: Int, minor: Boolean) = update { it.copy(keyRoot = Math.floorMod(root, 12), minor = minor) }

    // =====================================================================================
    // Tạo beat từ giọng
    // =====================================================================================

    fun setArrangeOptions(genreId: String? = null, followMelody: Boolean? = null) =
        update { it.copy(arrangeGenre = genreId ?: it.arrangeGenre, followMelody = followMelody ?: it.followMelody) }

    /** Dựng Looper + Piano + Guitar + Drum Pad theo BPM, tông, thể loại hiện tại rồi phát ngay từ đầu. */
    fun arrangeBeat() {
        val p = project ?: return
        val genre = Catalog.genre(p.arrangeGenre) ?: Catalog.genres.first()
        val clip = vocalClip()
        val vocalBars = clip?.bars(p.bpm) ?: 8
        if (vocalBusy) return
        stopPlayback()
        viewModelScope.launch {
            vocalBusy = true
            val melody = if (p.followMelody && clip != null) {
                withContext(Dispatchers.Default) {
                    val x = vocalSamples
                    if (x == null) emptyList() else {
                        val hz = AudioEngine.trackVocalPitch(x, clip.sampleRate)
                        Arranger.melodyFromPitch(hz, AudioEngine.vocalPitchHop(clip.sampleRate), clip.sampleRate, clip.startMs, p.bpm, p.keyRoot, p.minor)
                    }
                }
            } else emptyList()
            commit { cur -> Arranger.arrange(cur, genre, vocalBars, melody) }
            vocalBusy = false
            vocalMessage = if (p.followMelody && melody.isEmpty()) "Không dò được giai điệu rõ ràng, piano đệm hợp âm." else null
            cursorTick = 0
            startPlayback()
        }
    }

    companion object {
        /** Ước lượng độ trễ micro (ms) khi không đo được. */
        const val IN_LATENCY_MS = 35.0
    }
}
