package com.bandstudio.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bandstudio.app.StudioViewModel
import com.bandstudio.app.audio.AudioEngine
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun StudioScreen(vm: StudioViewModel) {
    // Phách hiện tại (0..3) để nhấp nháy theo nhịp; -1 khi dừng.
    var beat by remember { mutableIntStateOf(-1) }
    LaunchedEffect(vm.playing) {
        if (!vm.playing) {
            beat = -1
        } else {
            while (true) {
                beat = floor(AudioEngine.beatPosition()).toInt().mod(4)
                withFrameNanos { }
            }
        }
    }

    // Thông tin kỹ thuật, cập nhật mỗi giây.
    var info by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            val latency = AudioEngine.latencyMs()
            val latencyText = if (latency >= 0) "%.1f ms".format(latency) else "—"
            info = "${AudioEngine.sampleRate()} Hz • trễ $latencyText • xrun ${AudioEngine.xRunCount()} • " +
                "${AudioEngine.activeVoices()} nốt"
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text("BandStudio", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Bản thử nền tảng âm thanh",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TransportCard(vm, beat)

        Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.weight(1f))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Quãng tám", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = { vm.changeOctave(-1) }) { Text("−") }
            Text("C${vm.octave}", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { vm.changeOctave(+1) }) { Text("+") }
        }

        PianoKeyboard(
            startNote = vm.startNote,
            onNoteOn = vm::noteOn,
            onNoteOff = vm::noteOff,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
        )
    }
}

@Composable
private fun TransportCard(vm: StudioViewModel, beat: Int) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("BPM", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { vm.changeBpm(vm.bpm - 1) }) { Text("−") }
                Text(vm.bpm.toString(), style = MaterialTheme.typography.headlineSmall)
                OutlinedButton(onClick = { vm.changeBpm(vm.bpm + 1) }) { Text("+") }
            }

            Slider(
                value = vm.bpm.toFloat(),
                onValueChange = { vm.changeBpm(it.roundToInt()) },
                valueRange = 40f..240f,
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = vm::togglePlay) { Text(if (vm.playing) "Dừng" else "Phát") }
                Text("Metronome", style = MaterialTheme.typography.labelLarge)
                Switch(checked = vm.metronome, onCheckedChange = { vm.toggleMetronome() })
            }

            BeatDots(beat)
        }
    }
}

@Composable
private fun BeatDots(beat: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        for (i in 0 until 4) {
            val active = i == beat
            Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            active && i == 0 -> MaterialTheme.colorScheme.primary
                            active -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.outline
                        },
                    ),
            )
        }
    }
}
