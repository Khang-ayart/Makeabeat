package com.bandstudio.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bandstudio.app.StudioViewModel
import com.bandstudio.app.model.KEY_NAMES
import kotlin.math.roundToInt

/** Thu giọng: lọc ồn mặc định bật, auto-tune tùy chọn, dịch giọng cho khớp beat, chọn tông. */
@Composable
fun VocalScreen(vm: StudioViewModel) {
    val project = vm.project ?: return
    val clip = project.tracks.firstOrNull { it.instrument.isVocal }?.vocal
    val ctx = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startVocalRecording() else vm.vocalMessage = "Cần quyền micro để thu giọng."
    }
    fun record() {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startVocalRecording()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::leaveToStudio) { Text("‹ Quay lại") }
            Text("Thu giọng", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Đeo tai nghe để beat không lọt vào micro. App đếm 1 ô nhịp rồi thu; các track có sẵn sẽ phát làm nền.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // BPM + tông
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("BPM", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { vm.setBpm(project.bpm - 1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp), enabled = !vm.vocalRecording) { Text("−") }
                Text("${project.bpm}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(40.dp))
                OutlinedButton(onClick = { vm.setBpm(project.bpm + 1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp), enabled = !vm.vocalRecording) { Text("+") }
            }
            KeyRow(vm, project.keyRoot, project.minor, vm.detectedKey)

            // Nút thu
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundButton(
                    onClick = { if (vm.vocalRecording) vm.stopVocalRecording() else if (!vm.vocalBusy) record() },
                    size = 84.dp,
                    color = if (vm.vocalRecording) Color(0xFFE5484D) else MaterialTheme.colorScheme.primary,
                ) {
                    if (vm.vocalRecording) StopGlyph(Modifier.size(34.dp), Color.White) else RecordGlyph(Modifier.size(34.dp), Color(0xFF0B1330))
                }
                Text(
                    when {
                        vm.vocalBusy -> "Đang xử lý giọng…"
                        vm.vocalRecording && vm.vocalCountIn -> "Đếm nhịp… chuẩn bị hát"
                        vm.vocalRecording -> "Đang thu ${vm.vocalSeconds.roundToInt()} giây. Bấm để dừng"
                        clip != null -> "Bấm để thu lại (thay bản cũ)"
                        else -> "Bấm để thu"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                Box(
                    Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(vm.vocalLevel.coerceIn(0f, 1f)).background(if (vm.vocalLevel > 0.95f) Color(0xFFE5484D) else Color(0xFF7CE0A0)))
                }
            }

            // Xử lý
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Xử lý giọng", style = MaterialTheme.typography.titleSmall)
                SwitchRow("Làm rõ giọng", "Cứu bản thu nhỏ / xa mic: rõ lời hơn, tự cân to nhỏ.", vm.vocalEnhance) { vm.vocalEnhance = it }
                SwitchRow("Lọc ồn", "Bớt tiếng quạt, tiếng nền. Nên để bật.", vm.vocalDenoise) { vm.vocalDenoise = it }
                SwitchRow("Auto-tune", "Kéo giọng về đúng nốt trong tông đã chọn.", vm.vocalTune) { vm.vocalTune = it }
                if (vm.vocalTune) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        val presets = listOf(Triple("Nhẹ", 0.6f, 0.25f), Triple("Vừa", 0.85f, 0.6f), Triple("Mạnh (robot)", 1f, 1f))
                        for ((label, str, spd) in presets) {
                            ToggleChip(label, vm.vocalTuneStrength == str && vm.vocalTuneSpeed == spd, {
                                vm.vocalTuneStrength = str; vm.vocalTuneSpeed = spd
                            })
                        }
                        ToggleChip("Mọi nốt", vm.vocalChromatic, { vm.vocalChromatic = !vm.vocalChromatic })
                    }
                }
                if (clip != null) {
                    Button(onClick = vm::applyVocalOptions, enabled = !vm.vocalBusy && !vm.vocalRecording) { Text("Áp dụng lên bản thu") }
                }
            }

            // Bản thu
            if (clip != null) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Bản thu: ${clip.lengthMs / 1000} giây", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Giọng nghe lệch so với beat? Dịch giọng: ${if (clip.nudgeMs >= 0) "+" else ""}${clip.nudgeMs} ms (dương = sớm hơn, âm = muộn hơn).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        for (d in intArrayOf(-50, -10, 10, 50)) {
                            OutlinedButton(onClick = { vm.nudgeVocal(d) }, enabled = !vm.vocalBusy) { Text(if (d > 0) "+$d" else "$d") }
                        }
                        OutlinedButton(onClick = { vm.nudgeVocal(-clip.nudgeMs) }, enabled = !vm.vocalBusy && clip.nudgeMs != 0) { Text("Về 0") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = vm::togglePlay, enabled = !vm.vocalBusy && !vm.vocalRecording) { Text(if (vm.playing) "Dừng" else "Nghe cả bài") }
                        TextButton(onClick = vm::deleteVocal, enabled = !vm.vocalBusy && !vm.vocalRecording) { Text("Xóa giọng") }
                    }
                }
            }
            vm.vocalMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/** Chọn tông của bài (chủ âm + trưởng/thứ). Tông đoán được từ giọng hiện ra bên cạnh. */
@Composable
fun KeyRow(vm: StudioViewModel, root: Int, minor: Boolean, detected: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Tông của bài${if (detected != null) " (đoán từ giọng: $detected)" else ""}",
            style = MaterialTheme.typography.labelLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            KEY_NAMES.forEachIndexed { i, n -> ToggleChip(n, i == root, { vm.setKey(i, minor) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleChip("Trưởng", !minor, { vm.setKey(root, false) })
            ToggleChip("Thứ", minor, { vm.setKey(root, true) })
        }
    }
}

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}
