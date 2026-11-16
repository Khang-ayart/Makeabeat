package com.bandstudio.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bandstudio.app.StudioViewModel
import com.bandstudio.app.model.BAR_TICKS
import com.bandstudio.app.model.Clip
import com.bandstudio.app.model.DRUM_NAMES
import com.bandstudio.app.model.MAX_CLIP_BARS
import com.bandstudio.app.model.PPQ
import com.bandstudio.app.model.Quantizer
import com.bandstudio.app.model.ROLL_HIGH
import com.bandstudio.app.model.ROLL_LOW
import com.bandstudio.app.model.STEP_TICKS
import com.bandstudio.app.model.Track
import com.bandstudio.app.model.isBlackKey
import com.bandstudio.app.model.noteName

private val NoteLengths = listOf(
    PPQ / 8 to "1/32", PPQ / 4 to "1/16", PPQ / 2 to "1/8", PPQ to "1/4", PPQ * 2 to "1/2", BAR_TICKS to "1 ô",
)
private val PlayheadColor = Color(0xFFFF5C5C)

/** Trình soạn một clip: Piano Roll + bàn phím (nhạc cụ giai điệu) hoặc Step Sequencer + Drum Pad (trống). */
@Composable
fun EditorScreen(vm: StudioViewModel) {
    val track = vm.editorTrack() ?: return
    val clip = vm.editorClip() ?: return
    val color = Color(track.instrument.color)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        EditorTopBar(vm, track, clip)
        EditorToolbar(vm, track, clip)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (track.instrument.isDrum) DrumSequencer(vm, clip, color) else PianoRoll(vm, track, clip, color)
        }
        if (track.instrument.isDrum) DrumPads(vm, color) else KeyboardSection(vm, track, color)
    }
}

@Composable
private fun EditorTopBar(vm: StudioViewModel, track: Track, clip: Clip) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = vm::exitEditor) { Text("‹ Studio") }
        Text(
            "${track.name} • ô ${clip.startBar + 1}–${clip.endBar}",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = vm::undo, enabled = vm.canUndo) { Text("Hoàn tác") }
        TextButton(onClick = vm::redo, enabled = vm.canRedo) { Text("Làm lại") }
    }
}

@Composable
private fun EditorToolbar(vm: StudioViewModel, track: Track, clip: Clip) {
    val drum = track.instrument.isDrum
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RoundButton(onClick = vm::togglePlay, color = cs.primary) {
                if (vm.playing) StopGlyph(Modifier.size(22.dp), Color(0xFF0B1330)) else PlayGlyph(Modifier.size(22.dp), Color(0xFF0B1330))
            }
            RoundButton(onClick = vm::toggleRecord, color = if (vm.recording) Color(0xFFE5334B) else cs.surfaceVariant) {
                RecordGlyph(Modifier.size(26.dp), if (vm.recording) Color.White else Color(0xFFE5334B))
            }
            ToggleChip("Metronome", vm.metronome, vm::toggleMetronome)
            ToggleChip("Tự quantize", vm.autoQuantize || drum, vm::toggleAutoQuantize)
            OutlinedButton(onClick = vm::quantizeEditorClip, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text("Quantize")
            }
            Text("Ô nhịp", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(
                onClick = { vm.resizeEditorClip(-1) }, enabled = clip.bars > 1,
                contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp),
            ) { Text("−") }
            Text("${clip.bars}", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = { vm.resizeEditorClip(1) }, enabled = clip.bars < MAX_CLIP_BARS,
                contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp),
            ) { Text("+") }
            TextButton(onClick = vm::clearEditorClip) { Text("Xóa hết") }
        }
        if (drum) {
            Text(
                "Chạm ô trên lưới để bật/tắt tiếng trống. Ghi trực tiếp: bấm ●, rồi gõ các pad.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        } else {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Lưới", style = MaterialTheme.typography.labelLarge)
                for (g in Quantizer.GRIDS) ToggleChip(Quantizer.label(g), vm.gridStep == g, { vm.chooseGrid(g) })
                Text("  Độ dài nốt", style = MaterialTheme.typography.labelLarge)
                for ((len, label) in NoteLengths) ToggleChip(label, vm.noteLen == len, { vm.setNoteLength(len) })
            }
        }
    }
}

// =========================================================================================
// Piano Roll
// =========================================================================================

@Composable
private fun PianoRoll(vm: StudioViewModel, track: Track, clip: Clip, color: Color) {
    val density = LocalDensity.current
    val rowH = 26.dp
    val stepW = 24.dp
    val rows = ROLL_HIGH - ROLL_LOW + 1
    val cols = clip.bars * 16
    val rowPx = with(density) { rowH.toPx() }
    val initial = ((ROLL_HIGH - track.instrument.rollCenter) * rowPx - with(density) { 110.dp.toPx() })
        .toInt().coerceAtLeast(0)
    val vScroll = rememberScrollState(initial)
    val hScroll = rememberScrollState()

    Row(Modifier.fillMaxSize()) {
        // Cột phím dọc: chạm để nghe / ghi từng nốt.
        Column(Modifier.width(46.dp).verticalScroll(vScroll)) {
            for (pitch in ROLL_HIGH downTo ROLL_LOW) RollKey(vm, pitch, rowH)
        }
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
            Box(Modifier.verticalScroll(vScroll)) {
                RollGrid(vm, clip, color, rows, cols, rowH, stepW)
            }
        }
    }
}

@Composable
private fun RollKey(vm: StudioViewModel, pitch: Int, h: Dp) {
    val black = isBlackKey(pitch)
    Box(
        Modifier
            .fillMaxWidth()
            .height(h)
            .background(if (black) Color(0xFF15171C) else Color(0xFFE9EAEE))
            .border(0.5.dp, Color(0xFF3A3F4D))
            .pointerInput(pitch) {
                detectTapGestures(
                    onPress = {
                        vm.keyDown(pitch)
                        try {
                            tryAwaitRelease()
                        } finally {
                            vm.keyUp(pitch)
                        }
                    },
                )
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (pitch % 12 == 0) {
            Text(noteName(pitch), Modifier.padding(end = 4.dp), fontSize = 10.sp, color = Color(0xFF30343F))
        }
    }
}

@Composable
private fun RollGrid(vm: StudioViewModel, clip: Clip, color: Color, rows: Int, cols: Int, rowH: Dp, stepW: Dp) {
    val density = LocalDensity.current
    val rowPx = with(density) { rowH.toPx() }
    val stepPx = with(density) { stepW.toPx() }
    val blackRow = Color(0xFF14161B)
    val whiteRow = Color(0xFF1C1F27)

    Box(Modifier.size(stepW * cols, rowH * rows)) {
        Canvas(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectTapGestures { off ->
                        val tick = (off.x / stepPx * STEP_TICKS).toInt()
                        val pitch = ROLL_HIGH - (off.y / rowPx).toInt()
                        if (pitch in ROLL_LOW..ROLL_HIGH) vm.tapRollCell(pitch, tick)
                    }
                },
        ) {
            for (r in 0 until rows) {
                val pitch = ROLL_HIGH - r
                drawRect(if (isBlackKey(pitch)) blackRow else whiteRow, Offset(0f, r * rowPx), Size(size.width, rowPx))
                if (pitch % 12 == 0) {
                    drawLine(Color(0x33FFFFFF), Offset(0f, (r + 1) * rowPx), Offset(size.width, (r + 1) * rowPx), 1f)
                }
            }
            for (c in 0..cols) {
                val x = c * stepPx
                val alpha = if (c % 16 == 0) 0.7f else if (c % 4 == 0) 0.35f else 0.12f
                drawLine(Color.White.copy(alpha = alpha), Offset(x, 0f), Offset(x, size.height), if (c % 16 == 0) 2f else 1f)
            }
            val corner = CornerRadius(4.dp.toPx())
            for (n in clip.notes) {
                val r = ROLL_HIGH - n.pitch
                if (r < 0 || r >= rows) continue
                val x = n.start.toFloat() / STEP_TICKS * stepPx
                val w = maxOf(3f, n.dur.toFloat() / STEP_TICKS * stepPx - 2f)
                drawRoundRect(color, Offset(x + 1f, r * rowPx + 1f), Size(w, rowPx - 2f), corner)
            }
        }
        PlayheadOverlay(vm, clip, stepPx)
    }
}

@Composable
private fun PlayheadOverlay(vm: StudioViewModel, clip: Clip, pxPerStep: Float) {
    Canvas(Modifier.fillMaxSize()) {
        if (vm.playing) {
            val local = vm.playheadTick - clip.startBar * BAR_TICKS
            val x = local.toFloat() / STEP_TICKS * pxPerStep
            drawLine(PlayheadColor, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
        }
    }
}

// =========================================================================================
// Drum Step Sequencer + Drum Pad
// =========================================================================================

@Composable
private fun DrumSequencer(vm: StudioViewModel, clip: Clip, color: Color) {
    val density = LocalDensity.current
    val rowH = 32.dp
    val cellW = 28.dp
    val rowsN = DRUM_NAMES.size
    val cols = clip.bars * 16
    val rowPx = with(density) { rowH.toPx() }
    val cellPx = with(density) { cellW.toPx() }
    val cs = MaterialTheme.colorScheme
    val hScroll = rememberScrollState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row {
            Column(Modifier.width(84.dp)) {
                DRUM_NAMES.forEachIndexed { i, name ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(rowH)
                            .background(cs.surfaceVariant)
                            .border(0.5.dp, cs.outline)
                            .pointerInput(i) {
                                detectTapGestures(
                                    onPress = {
                                        vm.keyDown(i)
                                        try {
                                            tryAwaitRelease()
                                        } finally {
                                            vm.keyUp(i)
                                        }
                                    },
                                )
                            },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(name, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
            Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
                Box(Modifier.size(cellW * cols, rowH * rowsN)) {
                    Canvas(
                        Modifier
                            .matchParentSize()
                            .pointerInput(Unit) {
                                detectTapGestures { off ->
                                    val row = (off.y / rowPx).toInt().coerceIn(0, rowsN - 1)
                                    val col = (off.x / cellPx).toInt()
                                    vm.toggleDrumStep(row, col)
                                }
                            },
                    ) {
                        for (r in 0 until rowsN) {
                            for (c in 0 until cols) {
                                val shade = if ((c / 4) % 2 == 0) Color(0xFF1E222B) else Color(0xFF171A21)
                                drawRect(shade, Offset(c * cellPx, r * rowPx), Size(cellPx, rowPx))
                            }
                        }
                        for (c in 0..cols) {
                            val x = c * cellPx
                            val alpha = if (c % 16 == 0) 0.7f else if (c % 4 == 0) 0.3f else 0.1f
                            drawLine(Color.White.copy(alpha = alpha), Offset(x, 0f), Offset(x, size.height), 1f)
                        }
                        for (r in 0..rowsN) {
                            drawLine(Color.White.copy(alpha = 0.1f), Offset(0f, r * rowPx), Offset(size.width, r * rowPx), 1f)
                        }
                        val corner = CornerRadius(5.dp.toPx())
                        for (n in clip.notes) {
                            if (n.pitch !in 0 until rowsN) continue
                            val x = n.start.toFloat() / STEP_TICKS * cellPx
                            drawRoundRect(color, Offset(x + 2f, n.pitch * rowPx + 2f), Size(cellPx - 4f, rowPx - 4f), corner)
                        }
                    }
                    PlayheadOverlay(vm, clip, cellPx)
                }
            }
        }
    }
}

@Composable
private fun DrumPads(vm: StudioViewModel, color: Color) {
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (group in DRUM_NAMES.indices.chunked(5)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in group) DrumPad(vm, i, color, Modifier.weight(1f).height(54.dp))
                repeat(5 - group.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DrumPad(vm: StudioViewModel, piece: Int, color: Color, modifier: Modifier) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (pressed) color else color.copy(alpha = 0.3f))
            .border(1.dp, color, RoundedCornerShape(10.dp))
            .pointerInput(piece) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        vm.keyDown(piece)
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                            vm.keyUp(piece)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(DRUM_NAMES[piece], style = MaterialTheme.typography.labelMedium, maxLines = 1, textAlign = TextAlign.Center)
    }
}

// =========================================================================================
// Bàn phím piano mini (cho Piano / Guitar / Bass)
// =========================================================================================

@Composable
private fun KeyboardSection(vm: StudioViewModel, track: Track, color: Color) {
    var start by remember(track.instrument) { mutableStateOf(track.instrument.keyStart) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Quãng tám", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(
                onClick = { start = (start - 12).coerceAtLeast(24) },
                contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp),
            ) { Text("−") }
            Text(noteName(start), style = MaterialTheme.typography.titleSmall)
            OutlinedButton(
                onClick = { start = (start + 12).coerceAtMost(84) },
                contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp),
            ) { Text("+") }
        }
        PianoKeyboard(
            startNote = start,
            accent = color,
            onNoteOn = vm::keyDown,
            onNoteOff = vm::keyUp,
            modifier = Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}
