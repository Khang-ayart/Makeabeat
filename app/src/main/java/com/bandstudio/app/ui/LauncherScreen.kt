package com.bandstudio.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bandstudio.app.StudioViewModel
import com.bandstudio.app.Screen
import com.bandstudio.app.model.BAR_TICKS
import com.bandstudio.app.model.Catalog
import com.bandstudio.app.model.PADS_PER_ROW
import com.bandstudio.app.model.Patterns
import com.bandstudio.app.model.SoundCat

/**
 * Lưới Looper kiểu BandLab: mỗi pad là một nhịp lặp. Chạm pad để bật (vào đúng đầu ô nhịp kế tiếp),
 * chạm lại để tắt. Mỗi hàng một pad; mọi pad chạy trên cùng đồng hồ nên luôn khớp nhau.
 */
@Composable
fun LauncherScreen(vm: StudioViewModel) {
    val s = vm.screen as? Screen.Launcher ?: return
    val project = vm.project ?: return
    val track = project.track(s.trackId) ?: return
    val genre = Catalog.genre(track.genreId)
    val cs = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .safeDrawingPadding()
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::exitLauncher) { Text("‹ Studio") }
            Text(
                "Looper · ${genre?.label ?: ""}",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            KeyPicker(vm, s.trackId, track.keyRoot)
        }

        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SoundCat.entries.forEachIndexed { row, cat ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (col in 0 until PADS_PER_ROW) {
                        val pad = row * PADS_PER_ROW + col
                        val label = if (cat == SoundCat.KEYS && genre != null) Patterns.progressionLabel(track.keyRoot, genre.minor, pad) else padTitle(track, pad, cat, col)
                        LoopPad(vm, pad, label, genre?.family ?: "", Color(cat.color), Modifier.weight(1f).fillMaxSize())
                    }
                }
            }
        }

        LauncherTransport(vm, project.bpm)
    }
}

@Composable
private fun KeyPicker(vm: StudioViewModel, trackId: Int, key: Int) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.width(80.dp)) {
            Text("Tông ${com.bandstudio.app.model.KEY_NAMES[key]}", maxLines = 1)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            com.bandstudio.app.model.KEY_NAMES.forEachIndexed { i, n ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(n) }, onClick = { vm.setLooperKey(trackId, i); open = false })
            }
        }
    }
}

private fun padTitle(track: com.bandstudio.app.model.Track, pad: Int, cat: SoundCat, col: Int): String {
    val spec = track.kit.getOrNull(pad) ?: return cat.label
    val snd = Catalog.sound(spec.soundId)
    return if (cat == SoundCat.BASS) "${snd?.label ?: cat.label} ${col + 1}" else snd?.label ?: cat.label
}

@Composable
private fun LoopPad(vm: StudioViewModel, pad: Int, title: String, family: String, color: Color, modifier: Modifier) {
    val mask = vm.padMask
    val active = (mask shr pad) and 1L == 1L
    val pending = (mask shr (24 + pad)) and 1L == 1L
    val queuedOn = pending && !active
    val queuedOff = pending && active
    val blink by rememberInfiniteTransition(label = "queued").animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(280), RepeatMode.Reverse), label = "blink",
    )
    val pattern = (if (Patterns.isKeyPad(pad)) Patterns.keyRhythm(family, pad) else Patterns.pattern(family, pad)) ?: ""
    val shape = RoundedCornerShape(10.dp)
    val fill = when {
        active && !queuedOff -> color
        active && queuedOff -> color.copy(alpha = 0.35f + 0.4f * blink)
        queuedOn -> color.copy(alpha = 0.12f + 0.3f * blink)
        else -> color.copy(alpha = 0.14f)
    }

    Box(
        modifier
            .clip(shape)
            .background(fill)
            .pointerInput(pad) { detectTapGestures(onPress = { vm.tapPad(pad) }) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            // Vòng tiến trình của ô nhịp (chỉ pad đang chạy), ở góc trên trái.
            if (active && vm.playing) {
                val progress = (vm.playheadTick % BAR_TICKS).toFloat() / BAR_TICKS
                val d = 18.dp.toPx()
                drawArc(Color.White.copy(alpha = 0.35f), 0f, 360f, false, Offset(0f, 0f), Size(d, d), style = Stroke(2.dp.toPx()))
                drawArc(Color.White, -90f, 360f * progress, false, Offset(0f, 0f), Size(d, d), style = Stroke(2.dp.toPx()))
            }
            // 16 vạch nhỏ ở đáy cho thấy nhịp của pad.
            val gap = 2.dp.toPx()
            val w = (size.width - gap * 15) / 16
            val h = 6.dp.toPx()
            pattern.forEachIndexed { i, ch ->
                val on = ch != '.'
                val c = if (active) Color.White.copy(alpha = if (on) 0.9f else 0.18f)
                else color.copy(alpha = if (on) 0.9f else 0.18f)
                drawRoundRect(c, Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(1.5.dp.toPx()))
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) Color(0xFF10131A) else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun LauncherTransport(vm: StudioViewModel, bpm: Int) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RoundButton(onClick = vm::launcherStopAll, size = 48.dp) { StopGlyph(Modifier.size(22.dp)) }
            RoundButton(
                onClick = vm::toggleLauncherRecord, size = 48.dp,
                color = if (vm.launcherRecording) Color(0xFFE5334B) else cs.surfaceVariant,
            ) { RecordGlyph(Modifier.size(26.dp), if (vm.launcherRecording) Color.White else Color(0xFFE5334B)) }
            ToggleChip("Metronome", vm.metronome, vm::toggleMetronome)
            Text("BPM", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(
                onClick = { vm.setBpm(bpm - 1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp),
            ) { Text("−") }
            Text("$bpm", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
            OutlinedButton(
                onClick = { vm.setBpm(bpm + 1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp),
            ) { Text("+") }
        }
        Text(
            if (vm.launcherRecording) "Đang ghi: phần bạn chơi sẽ thành clip khi bấm ● lần nữa."
            else "Chạm pad để bật nhịp, chạm lại để tắt. Pad mới vào ở đầu ô nhịp kế tiếp. Bấm ● để ghi thành clip.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
    }
}
