package com.bandstudio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bandstudio.app.StudioViewModel
import com.bandstudio.app.model.Project
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(vm: StudioViewModel) {
    var toDelete by remember { mutableStateOf<Project?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Text("Makeabeat", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Phòng thu trong túi bạn. Làm nhạc hoàn toàn offline, không tài khoản, không quảng cáo.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        UpdateCard(vm.updates)
        Spacer(Modifier.height(12.dp))
        Button(onClick = vm::newProject, modifier = Modifier.fillMaxWidth()) {
            Text("+  Tạo bản nhạc mới")
        }
        Spacer(Modifier.height(16.dp))

        if (vm.projects.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Chưa có bản nhạc nào.\nBấm \"Tạo bản nhạc mới\" để bắt đầu:\nthêm nhạc cụ, vẽ beat rồi ghép thành bài.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text("Bản nhạc của bạn", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.projects, key = { it.id }) { p ->
                    ProjectCard(p, onOpen = { vm.openProject(p.id) }, onDelete = { toDelete = p })
                }
            }
        }
    }

    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Xóa \"${p.name}\"?") },
            text = { Text("Bản nhạc sẽ bị xóa khỏi máy và không khôi phục được.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProject(p.id)
                    toDelete = null
                }) { Text("Xóa") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Hủy") } },
        )
    }
}

/** Phiên bản đang dùng, trạng thái cập nhật và nút thao tác; các tùy chọn tự động nằm trong phần mở rộng. */
@Composable
private fun UpdateCard(u: com.bandstudio.app.update.UpdateController) {
    var open by remember { mutableStateOf(false) }
    val st = u.state
    val err = com.bandstudio.app.update.InstallResultReceiver.lastError
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Phiên bản ${u.versionName}", style = MaterialTheme.typography.titleSmall)
                    val line = when (st) {
                        is com.bandstudio.app.update.UpdateController.State.Idle -> "Chưa kiểm tra cập nhật."
                        is com.bandstudio.app.update.UpdateController.State.Checking -> "Đang kiểm tra…"
                        is com.bandstudio.app.update.UpdateController.State.UpToDate -> "Bạn đang dùng bản mới nhất."
                        is com.bandstudio.app.update.UpdateController.State.Available ->
                            "Có bản mới ${st.info.versionName}" + (if (st.info.prerelease) " (thử nghiệm)" else "") +
                                (if (st.info.size > 0) " • ${"%.1f".format(st.info.size / 1048576.0)} MB" else "")
                        is com.bandstudio.app.update.UpdateController.State.Downloading -> "Đang tải bản ${st.info.versionName}: ${(st.progress * 100).toInt()}%"
                        is com.bandstudio.app.update.UpdateController.State.Ready -> "Đã tải bản ${st.info.versionName}, sẵn sàng cài."
                        is com.bandstudio.app.update.UpdateController.State.Error -> st.message
                    }
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (st) {
                    is com.bandstudio.app.update.UpdateController.State.Available -> Button(onClick = { u.download(st.info) }) { Text("Tải") }
                    is com.bandstudio.app.update.UpdateController.State.Ready -> Button(onClick = u::install) { Text("Cài đặt") }
                    is com.bandstudio.app.update.UpdateController.State.Checking, is com.bandstudio.app.update.UpdateController.State.Downloading -> Unit
                    else -> TextButton(onClick = { u.check(manual = true) }) { Text("Kiểm tra") }
                }
            }
            if (st is com.bandstudio.app.update.UpdateController.State.Downloading) {
                androidx.compose.material3.LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
            }
            u.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (err != null) Text(
                "Cài đặt không thành công: $err" + "\nNếu báo xung đột / chữ ký: bản đang cài dùng khóa ký khác, cần gỡ app rồi cài lại bản mới một lần.",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF8A80),
            )
            TextButton(onClick = { open = !open }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(if (open) "Ẩn cài đặt cập nhật" else "Cài đặt cập nhật")
            }
            if (open) {
                SwitchRow("Tự kiểm tra và tải bản mới", u.auto, u::updateAuto)
                SwitchRow("Chỉ tải khi có Wi-Fi", u.wifiOnly, u::updateWifiOnly)
                SwitchRow("Nhận cả bản thử nghiệm", u.includePrerelease, u::updateIncludePrerelease)
                Text(
                    "Mạng chỉ dùng để hỏi GitHub có bản mới không và tải bản đó; không gửi bất kỳ dữ liệu nào của bạn. Android luôn hỏi xác nhận trước khi cài.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        androidx.compose.material3.Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun ProjectCard(p: Project, onOpen: () -> Unit, onDelete: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.width3()) {
                p.tracks.take(4).forEach { t ->
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(t.instrument.color)))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(p.updatedAt))
                Text(
                    "${p.tracks.size} track  •  ${p.bpm} BPM  •  $date",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDelete) { Text("Xóa") }
        }
    }
}

// Khoảng trống cố định cho cụm chấm màu để các thẻ thẳng hàng.
private fun Modifier.width3(): Modifier = this.padding(end = 12.dp)
