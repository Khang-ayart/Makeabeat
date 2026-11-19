package com.bandstudio.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bandstudio.app.StudioViewModel
import com.bandstudio.app.model.BAR_TICKS
import com.bandstudio.app.model.Catalog
import com.bandstudio.app.model.Clip
import com.bandstudio.app.model.DRUM_NAMES
import com.bandstudio.app.model.Genre
import com.bandstudio.app.model.Instrument
import com.bandstudio.app.model.MAX_BARS
import com.bandstudio.app.model.MAX_CLIP_BARS
import com.bandstudio.app.model.MAX_TRACKS
import com.bandstudio.app.model.KIT_COUNT
import com.bandstudio.app.model.Project
import com.bandstudio.app.model.Track
import kotlin.math.roundToInt

private val HeaderW = 112.dp
private val LaneH = 76.dp
private val BarW = 76.dp
private val RulerH = 30.dp

/** Màn hình chính kiểu BandLab: mỗi nhạc cụ một track, các clip (beat) xếp trên timeline chung. */
@Composable
fun StudioScreen(vm: StudioViewModel) {
    val project = vm.project ?: return
    var showPicker by remember { mutableStateOf(false) }
    var showGenrePicker by remember { mutableStateOf(false) }
    var trackDialogId by remember { mutableStateOf<Int?>(null) }
    var showRename by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        StudioTopBar(vm, project, onRename = { showRename = true })
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::openVocal, modifier = Modifier.weight(1f)) { Text("Vocal: thu giọng") }
            OutlinedButton(onClick = vm::openMixer, modifier = Modifier.weight(1f)) { Text("Mixer & Tạo beat") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (project.tracks.isEmpty()) {
                EmptyStudio(onAdd = { showPicker = true })
            } else {
                Timeline(vm, project, onAddTrack = { showPicker = true }, onTrack = { trackDialogId = it })
            }
        }
        SelectedClipBar(vm, project)
        TransportBar(vm, project)
    }

    if (showPicker) {
        InstrumentPicker(
            onPick = {
                showPicker = false
                if (it.isLooper) showGenrePicker = true else vm.addTrack(it)
            },
            onDismiss = { showPicker = false },
        )
    }
    if (showGenrePicker) {
        GenrePicker(
            title = "Chọn thể loại cho Looper",
            current = null,
            onPick = { vm.addLooperAndOpen(it); showGenrePicker = false },
            onDismiss = { showGenrePicker = false },
        )
    }
    trackDialogId?.let { id ->
        val t = project.track(id)
        if (t != null) TrackDialog(vm, t, onClose = { trackDialogId = null }) else trackDialogId = null
    }
    if (showRename) RenameDialog(project.name, onDone = { vm.renameProject(it); showRename = false }, onCancel = { showRename = false })
}

@Composable
private fun StudioTopBar(vm: StudioViewModel, project: Project, onRename: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = vm::closeProject) { Text("‹ Danh sách") }
        Box(Modifier.weight(1f).clickable(onClick = onRename), contentAlignment = Alignment.Center) {
            Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = vm::undo, enabled = vm.canUndo) { Text("Hoàn tác") }
        TextButton(onClick = vm::redo, enabled = vm.canRedo) { Text("Làm lại") }
    }
}

@Composable
private fun EmptyStudio(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Bắt đầu bằng một nhạc cụ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Mỗi nhạc cụ là một track. Chạm vào ô trống trên track để tạo một đoạn (clip), chạm lần nữa để vẽ beat hoặc chơi nhạc. Tất cả track chạy chung một nhịp.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAdd) { Text("+  Thêm nhạc cụ") }
    }
}

// =========================================================================================
// Timeline
// =========================================================================================

@Composable
private fun Timeline(vm: StudioViewModel, project: Project, onAddTrack: () -> Unit, onTrack: (Int) -> Unit) {
    val barCount = (project.endBar + 4).coerceIn(16, MAX_BARS)
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val laneWidth = BarW * barCount

    Row(Modifier.fillMaxSize()) {
        // Cột tên track cố định bên trái, cuộn dọc cùng với các lane.
        Column(Modifier.width(HeaderW)) {
            Spacer(Modifier.height(RulerH))
            Column(Modifier.weight(1f).verticalScroll(vScroll)) {
                for (t in project.tracks) {
                    TrackHeader(t, onClick = { onTrack(t.id) }, onMute = { vm.toggleMute(t.id) }, onSolo = { vm.toggleSolo(t.id) })
                }
                if (project.tracks.size < MAX_TRACKS) AddTrackButton(onAddTrack)
            }
        }
        // Timeline cuộn ngang.
        Column(Modifier.weight(1f).horizontalScroll(hScroll)) {
            Ruler(vm, barCount)
            Box(Modifier.weight(1f)) {
                Column(Modifier.width(laneWidth).fillMaxHeight().verticalScroll(vScroll)) {
                    for (t in project.tracks) Lane(vm, t, barCount, project.bpm)
                }
                Playhead(vm, laneWidth)
            }
        }
    }
}

@Composable
private fun TrackHeader(t: Track, onClick: () -> Unit, onMute: () -> Unit, onSolo: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(LaneH)
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(Color(t.instrument.color)))
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(t.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallToggle("M", t.mute, Color(0xFFFFC857), onMute)
                SmallToggle("S", t.solo, Color(0xFF7CE0A0), onSolo)
            }
        }
    }
}

@Composable
private fun AddTrackButton(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("+ Thêm track", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Ruler(vm: StudioViewModel, barCount: Int) {
    Row(
        Modifier
            .width(BarW * barCount)
            .height(RulerH)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        for (i in 0 until barCount) {
            Box(
                Modifier.width(BarW).fillMaxHeight().clickable { vm.seekToBar(i) }, // chạm vào số ô nhịp để đặt vị trí phát
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    "${i + 1}",
                    Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Lane(vm: StudioViewModel, t: Track, barCount: Int, bpm: Int) {
    val barPx = with(LocalDensity.current) { BarW.toPx() }
    val lineColor = MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .width(BarW * barCount)
            .height(LaneH)
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(t.id) {
                detectTapGestures { off ->
                    // Chạm vào chỗ trống: bỏ chọn clip trước, chạm lần nữa mới tạo clip mới.
                    if (t.instrument.isVocal) vm.openVocal()
                    else if (vm.selectedClipId != null) vm.selectClip(null) else vm.addClip(t.id, (off.x / barPx).toInt())
                }
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            for (i in 0..barCount) {
                val a = if (i % 4 == 0) 0.55f else 0.2f
                drawLine(lineColor.copy(alpha = a), Offset(i * barPx, 0f), Offset(i * barPx, size.height), 1f)
            }
        }
        t.vocal?.let { v ->
            val startBars = v.offsetMs / 1000f * bpm / 240f
            val lenBars = v.playMs / 1000f * bpm / 240f
            Box(
                Modifier
                    .offset(x = BarW * startBars)
                    .width(BarW * lenBars)
                    .fillMaxHeight()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(t.instrument.color).copy(alpha = 0.55f)),
                contentAlignment = Alignment.CenterStart,
            ) { Text("Giọng thu", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium, color = Color.Black) }
        }
        for (c in t.clips) {
            ClipBlock(
                c, t, selected = vm.selectedClipId == c.id,
                onClick = { if (vm.selectedClipId == c.id) vm.openEditor(t.id, c.id) else vm.selectClip(c.id) },
            )
        }
    }
}

@Composable
private fun ClipBlock(clip: Clip, track: Track, selected: Boolean, onClick: () -> Unit) {
    val color = Color(track.instrument.color)
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .offset(x = BarW * clip.startBar)
            .width(BarW * clip.bars)
            .fillMaxHeight()
            .padding(2.dp)
            .clip(shape)
            .background(color.copy(alpha = if (track.mute) 0.12f else 0.28f))
            .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else color, shape)
            .clickable(onClick = onClick),
    ) {
        MiniNotes(clip, track.instrument, color)
    }
}

/** Xem trước các nốt trong clip (như vân sóng của BandLab). */
@Composable
private fun MiniNotes(clip: Clip, inst: Instrument, color: Color) {
    Canvas(Modifier.fillMaxSize().padding(4.dp)) {
        if (clip.notes.isEmpty()) return@Canvas
        val len = clip.lengthTicks.toFloat()
        val lo: Int
        val hi: Int
        if (inst.oneShot) {
            lo = 0
            hi = (if (inst.isLooper) KIT_COUNT else DRUM_NAMES.size) - 1
        } else {
            lo = clip.notes.minOf { it.pitch }
            hi = maxOf(clip.notes.maxOf { it.pitch }, lo + 7)
        }
        val rowH = size.height / (hi - lo + 1)
        for (n in clip.notes) {
            if (inst.oneShot && n.pitch > hi) continue // nốt keyboard (>=128) không có hàng riêng trong bản xem nhanh
            val x = n.start / len * size.width
            val w = maxOf(2f, n.dur / len * size.width)
            val y = (hi - n.pitch) * rowH
            drawRect(color, Offset(x, y), Size(w, maxOf(2f, rowH * 0.8f)))
        }
    }
}

@Composable
private fun Playhead(vm: StudioViewModel, width: Dp) {
    val barPx = with(LocalDensity.current) { BarW.toPx() }
    Canvas(Modifier.width(width).fillMaxHeight()) {
        val tick = if (vm.playing) vm.playheadTick else vm.cursorTick
        val x = tick / BAR_TICKS.toFloat() * barPx
        drawLine(Color(0xFFFF5C5C), Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
    }
}

// =========================================================================================
// Thanh thao tác clip + transport
// =========================================================================================

@Composable
private fun SelectedClipBar(vm: StudioViewModel, project: Project) {
    val id = vm.selectedClipId ?: return
    val found = project.tracks.firstNotNullOfOrNull { t ->
        t.clips.firstOrNull { it.id == id }?.let { c -> t to c }
    } ?: return
    val (track, clip) = found

    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "${track.name} • ô ${clip.startBar + 1}–${clip.endBar}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(end = 6.dp),
            )
            Button(onClick = { vm.openEditor(track.id, clip.id) }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Sửa") }
            TextButton(onClick = { vm.duplicateClip(track.id, clip.id) }) { Text("Nhân đôi") }
            TextButton(onClick = { vm.moveClip(track.id, clip.id, -1) }) { Text("‹ Lùi") }
            TextButton(onClick = { vm.moveClip(track.id, clip.id, 1) }) { Text("Tiến ›") }
            TextButton(onClick = { vm.resizeClip(track.id, clip.id, -1) }, enabled = clip.bars > 1) { Text("− ô") }
            TextButton(onClick = { vm.resizeClip(track.id, clip.id, 1) }, enabled = clip.bars < MAX_CLIP_BARS) { Text("+ ô") }
            TextButton(onClick = { vm.deleteClip(track.id, clip.id) }) { Text("Xóa") }
        }
    }
}

@Composable
private fun TransportBar(vm: StudioViewModel, project: Project) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoundButton(onClick = { vm.seekToBar(0) }) { RewindGlyph(Modifier.size(22.dp)) }
                RoundButton(onClick = vm::togglePlay, size = 54.dp, color = MaterialTheme.colorScheme.primary) {
                    if (vm.playing) StopGlyph(Modifier.size(26.dp), Color(0xFF0B1330)) else PlayGlyph(Modifier.size(26.dp), Color(0xFF0B1330))
                }
                Spacer(Modifier.weight(1f))
                ToggleChip("Lặp", vm.loopAll, vm::toggleLoop)
                ToggleChip("Metronome", vm.metronome, vm::toggleMetronome)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("BPM", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(
                    onClick = { vm.setBpm(project.bpm - 1) },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(36.dp),
                ) { Text("−") }
                Text(
                    "${project.bpm}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.width(40.dp),
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(
                    onClick = { vm.setBpm(project.bpm + 1) },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(36.dp),
                ) { Text("+") }
                Slider(
                    value = project.bpm.toFloat(),
                    onValueChange = { vm.setBpm(it.roundToInt()) },
                    valueRange = 40f..240f,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// =========================================================================================
// Hộp thoại
// =========================================================================================

@Composable
private fun InstrumentPicker(onPick: (Instrument) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chọn nhạc cụ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (pair in Instrument.entries.filter { !it.isVocal }.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (inst in pair) InstrumentCard(inst, Modifier.weight(1f)) { onPick(inst) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
    )
}

@Composable
private fun InstrumentCard(inst: Instrument, modifier: Modifier, onClick: () -> Unit) {
    val hint = when (inst) {
        Instrument.PIANO -> "Phím đàn, hợp âm"
        Instrument.ACOUSTIC -> "Dây gảy mộc"
        Instrument.ELECTRIC -> "Có độ méo"
        Instrument.BASS -> "Trầm, giữ nhịp"
        Instrument.DRUM_KIT -> "Trống mộc"
        Instrument.DRUM_PAD -> "Trống điện tử 808"
        Instrument.LOOPER -> "Pad theo thể loại"
        Instrument.VOCAL -> "Thu giọng hát"
    }
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(inst.color)))
        Text(inst.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Danh sách thể loại; mỗi thể loại sẽ có kick / snare / hi-hat / percussion / bass riêng, mỗi hàng 4 lựa chọn. */
@Composable
private fun GenrePicker(title: String, current: String?, onPick: (Genre) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (pair in Catalog.genres.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (g in pair) {
                            val selected = g.id == current
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(if (selected) 2.dp else 0.dp, if (selected) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
                                    .clickable { onPick(g) }
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(g.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${g.bpm} BPM", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
    )
}

@Composable
private fun TrackDialog(vm: StudioViewModel, t: Track, onClose: () -> Unit) {
    var showGenres by remember { mutableStateOf(false) }
    if (showGenres) {
        GenrePicker(
            title = "Đổi thể loại",
            current = t.genreId,
            onPick = { vm.setLooperGenre(t.id, it); showGenres = false },
            onDismiss = { showGenres = false },
        )
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(t.name) },
        text = {
            Column {
                Text("Âm lượng", style = MaterialTheme.typography.labelLarge)
                Slider(value = t.volume, onValueChange = { vm.setTrackVolume(t.id, it) }, valueRange = 0f..1.2f)
                Text("Trái  –  Phải", style = MaterialTheme.typography.labelLarge)
                Slider(value = t.pan, onValueChange = { vm.setTrackPan(t.id, it) }, valueRange = -1f..1f)
                if (t.instrument.isVocal) {
                    Button(onClick = { onClose(); vm.openVocal() }) { Text("Mở màn thu giọng") }
                }
                if (t.instrument.isLooper) {
                    Button(onClick = { onClose(); vm.openLauncher(t.id) }) { Text("Mở lưới Looper") }
                    OutlinedButton(onClick = { showGenres = true }) {
                        Text("Thể loại: ${Catalog.genre(t.genreId)?.label ?: "—"}  ›")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Xong") } },
        dismissButton = {
            TextButton(onClick = {
                vm.deleteTrack(t.id)
                onClose()
            }) { Text("Xóa track") }
        },
    )
}

@Composable
private fun RenameDialog(current: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Tên bản nhạc") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text("Lưu") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Hủy") } },
    )
}
