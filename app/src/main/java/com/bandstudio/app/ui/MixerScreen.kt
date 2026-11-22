package com.bandstudio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bandstudio.app.StudioViewModel
import com.bandstudio.app.model.Catalog
import com.bandstudio.app.model.Mood
import com.bandstudio.app.model.Track
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

/** Mixer + "Tạo beat": chọn BPM, tông, thể loại, kiểu đệm; app dựng sẵn Looper, Piano, Guitar, Drum Pad quanh giọng hát. */
@Composable
fun MixerScreen(vm: StudioViewModel) {
    val project = vm.project ?: return
    val hasVocal = project.tracks.any { it.instrument.isVocal && it.vocal != null }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::leaveToStudio) { Text("‹ Quay lại") }
            Text("Mixer & Tạo beat", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- Tạo beat ----
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Tạo beat từ giọng", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (hasVocal) "Chọn BPM, tông, thể loại rồi bấm Tạo beat: app dựng sẵn nhạc quanh giọng của bạn để bạn nghe và chỉnh."
                    else "Chưa có giọng hát: vẫn tạo được beat 8 ô nhịp để thử. Thu giọng ở nút Vocal ngoài màn chính.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("BPM", style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = { vm.setBpm(project.bpm - 1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) { Text("−") }
                    Text("${project.bpm}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
                    OutlinedButton(onClick = { vm.setBpm(project.bpm + 1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) { Text("+") }
                    Slider(value = project.bpm.toFloat(), onValueChange = { vm.setBpm(it.roundToInt()) }, valueRange = 40f..240f, modifier = Modifier.weight(1f))
                }
                KeyRow(vm, project.keyRoot, project.minor, vm.detectedKey)
                Text("Cảm xúc", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    for (m in Mood.entries) ToggleChip(m.label, m.id == project.mood, { vm.setMood(m) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Buồn", style = MaterialTheme.typography.labelMedium)
                    Slider(value = project.valence.toFloat(), onValueChange = { vm.setValence(it.roundToInt()) }, valueRange = 0f..100f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    Text("Vui", style = MaterialTheme.typography.labelMedium)
                }
                Text(Mood.of(project.mood).hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Thể loại", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    for (g in Catalog.genres) {
                        ToggleChip("${g.label} · ${g.bpm}", g.id == project.arrangeGenre, {
                            vm.setArrangeOptions(genreId = g.id)
                            vm.setBpm(g.bpm)  // mỗi thể loại có BPM gợi ý; muốn khác thì chỉnh lại ở trên
                            if (g.id == "remix") vm.setStructurePreset("remix")  // remix: intro, verse, build-up, drop, break...
                        })
                    }
                }
                Text("Cách đánh nhạc cụ", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToggleChip("Đệm hợp âm (rap)", !project.followMelody, { vm.setArrangeOptions(followMelody = false) })
                    ToggleChip("Theo giai điệu (melodic/pop)", project.followMelody, { vm.setArrangeOptions(followMelody = true) })
                }
                StructureEditor(vm, project, hasVocal)
                val beat = vm.hasImportedBeat(project)
                if (beat) {
                    Button(onClick = vm::markBeatStructure, modifier = Modifier.fillMaxWidth()) { Text("Đánh dấu đoạn + vòng đệm cho beat của bạn") }
                    Text(
                        "Beat nhập vào được giữ nguyên: app chia nó theo cấu trúc ở trên và thêm crash, clap dồn nhẹ để đẩy vào hook.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (beat) OutlinedButton(onClick = vm::arrangeBeat, enabled = !vm.vocalBusy, modifier = Modifier.fillMaxWidth()) { Text("Tạo thêm nhạc đệm (piano, guitar, looper)") }
                else Button(onClick = vm::arrangeBeat, enabled = !vm.vocalBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (vm.vocalBusy) "Đang xử lý…" else "Tạo beat")
                }
                vm.vocalMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }

            ExportCard(vm)

            // ---- Các kênh ----
            Text("Các kênh", style = MaterialTheme.typography.titleSmall)
            for (t in project.tracks) Strip(vm, t)
            if (project.tracks.isEmpty()) {
                Text("Chưa có track nào.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.size(8.dp))
        }
        // Phát / dừng
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(onClick = { vm.seekToBar(0) }) { RewindGlyph(Modifier.size(22.dp)) }
            RoundButton(onClick = vm::togglePlay, size = 54.dp, color = MaterialTheme.colorScheme.primary) {
                if (vm.playing) StopGlyph(Modifier.size(26.dp), Color(0xFF0B1330)) else PlayGlyph(Modifier.size(26.dp), Color(0xFF0B1330))
            }
        }
    }
}

/** Chọn mẫu cấu trúc, thời lượng và chỉnh từng đoạn (thứ tự, số ô, thêm / xóa). Cấu trúc được dùng ngay khi bấm Tạo beat. */
@Composable
private fun StructureEditor(vm: StudioViewModel, project: com.bandstudio.app.model.Project, hasVocal: Boolean) {
    val st = vm.effectiveStructure(project)
    val names = com.bandstudio.app.model.Structure.names(st)
    val total = com.bandstudio.app.model.Structure.totalBars(st)
    val secs = com.bandstudio.app.model.Structure.secondsOf(total, project.bpm)
    Text("Cấu trúc bài", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        for (pr in com.bandstudio.app.model.Structure.presets) ToggleChip(pr.label, project.structurePreset == pr.id, { vm.setStructurePreset(pr.id) })
    }
    Text("Thời lượng", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        if (hasVocal) ToggleChip("Theo giọng", project.targetSeconds == 0, { vm.setTargetSeconds(0) })
        for ((label, sec) in listOf("1:30" to 90, "2:00" to 120, "2:30" to 150, "3:00" to 180, "3:30" to 210, "4:00" to 240)) {
            ToggleChip(label, project.targetSeconds == sec, { vm.setTargetSeconds(sec) })
        }
    }
    Text(
        "Hiện tại: ${secs / 60}:${"%02d".format(secs % 60)} • $total ô" + when {
            hasVocal && project.targetSeconds == 0 -> " • nhạc phủ hết giọng, giọng được đặt sau intro"
            project.targetSeconds > 0 -> ""
            else -> ""
        },
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for ((i, s) in st.withIndex()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(s.type.color)))
            Text(names[i], style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp).weight(1f), maxLines = 1)
            TextButton(onClick = { vm.resizeSection(i, -4) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(32.dp)) { Text("−") }
            Text("${s.bars} ô", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
            TextButton(onClick = { vm.resizeSection(i, 4) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(32.dp)) { Text("+") }
            TextButton(onClick = { vm.moveSection(i, -1) }, enabled = i > 0, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(32.dp)) { Text("↑") }
            TextButton(onClick = { vm.moveSection(i, 1) }, enabled = i < st.size - 1, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(32.dp)) { Text("↓") }
            TextButton(onClick = { vm.removeSection(i) }, enabled = st.size > 1, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(32.dp)) { Text("✕") }
        }
    }
    Text("Thêm đoạn", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        for (t in com.bandstudio.app.model.SectionType.entries) ToggleChip("+ ${t.label}", false, { vm.addSection(t) })
    }
}

@Composable
private fun ExportCard(vm: StudioViewModel) {
    val ctx = LocalContext.current
    var saved by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Xuất bản nhạc", style = MaterialTheme.typography.titleSmall)
        Text(
            "M4A nhẹ, dễ gửi; WAV là bản gốc không nén. Cả hai đều dùng được trong mọi app dựng video. Xuất đúng như bạn đang nghe; project vẫn giữ nguyên để chỉnh tiếp.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { saved = null; vm.exportMix(StudioViewModel.ExportKind.M4A) }, enabled = !vm.exporting, modifier = Modifier.weight(1f)) { Text("Xuất M4A") }
            OutlinedButton(onClick = { saved = null; vm.exportMix(StudioViewModel.ExportKind.WAV) }, enabled = !vm.exporting, modifier = Modifier.weight(1f)) { Text("Xuất WAV") }
        }
        if (vm.exporting) {
            Text(vm.exportStage, style = MaterialTheme.typography.labelLarge)
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surface)) {
                Box(Modifier.fillMaxWidth(vm.exportProgress.coerceIn(0f, 1f)).height(8.dp).background(MaterialTheme.colorScheme.primary))
            }
            Text("Đừng thoát ứng dụng cho tới khi xong.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        vm.exportMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        val f = vm.exportedFile
        if (f != null && !vm.exporting) {
            val kind = vm.exportedKind
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ExportActions.share(ctx, f, kind.mime) }) { Text("Gửi / đăng") }
                OutlinedButton(onClick = {
                    val ok = ExportActions.saveToDevice(ctx, f, kind.mime)
                    saved = if (ok) "Đã lưu vào Music/Makeabeat." else "Máy cũ hoặc lỗi: dùng nút Gửi / đăng để lưu vào Drive hay Zalo."
                }) { Text("Lưu vào máy") }
            }
            saved?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun Strip(vm: StudioViewModel, t: Track) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(t.instrument.color)))
            Text(t.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1)
            SmallToggle("M", t.mute, Color(0xFFFFC857)) { vm.toggleMute(t.id) }
            SmallToggle("S", t.solo, Color(0xFF7CE0A0)) { vm.toggleSolo(t.id) }
        }
        LabeledSlider("Âm lượng", t.volume, 0f..1.2f) { vm.setTrackVolume(t.id, it) }
        LabeledSlider("Trái – Phải", t.pan, -1f..1f) { vm.setTrackPan(t.id, it) }
        t.vocal?.let { v ->
            LabeledSlider("Độ to của giọng", v.gain, 0.1f..1.5f) { vm.setVocalMix(gain = it) }
            LabeledSlider("Vang (reverb)", v.reverb, 0f..1f) { vm.setVocalMix(reverb = it) }
            LabeledSlider("Nén (giọng chắc, đều)", v.comp, 0f..1f) { vm.setVocalMix(comp = it) }
            LabeledSlider("Độ sáng", v.bright, 0f..1f) { vm.setVocalMix(bright = it) }
        }
    }
}
