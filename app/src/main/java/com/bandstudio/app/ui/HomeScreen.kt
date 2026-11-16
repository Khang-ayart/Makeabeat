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
        Text("BandStudio", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Phòng thu trong túi bạn. Không cần mạng, không tài khoản, không quảng cáo.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
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
