package com.bandstudio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

// Phím đen nằm sau phím trắng thứ 0, 1, 3, 4, 5 của mỗi quãng tám (C#, D#, F#, G#, A#).
private val BlackAfterWhite = setOf(0, 1, 3, 4, 5)
private val WhiteSteps = intArrayOf(0, 2, 4, 5, 7, 9, 11)

/**
 * Bàn phím piano cảm ứng đa điểm: mỗi phím tự xử lý ngón tay của nó nên bấm nhiều phím cùng lúc được.
 * [startNote] phải là nốt C. Màn hình rộng hiện 2 quãng tám, hẹp hiện 1 quãng tám.
 */
@Composable
fun PianoKeyboard(
    startNote: Int,
    accent: Color,
    onNoteOn: (Int) -> Unit,
    onNoteOff: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val octaves = if (maxWidth > 600.dp) 2 else 1
        val whiteCount = octaves * 7 + 1 // kết thúc bằng phím C của quãng tám kế
        val whiteNotes = List(whiteCount) { i -> startNote + (i / 7) * 12 + WhiteSteps[i % 7] }
        val keyWidth = maxWidth / whiteCount
        val blackWidth = keyWidth * 0.62f

        Row(Modifier.fillMaxSize()) {
            for (note in whiteNotes) {
                PianoKey(note, false, accent, onNoteOn, onNoteOff, Modifier.weight(1f).fillMaxHeight())
            }
        }

        // Phím đen vẽ sau cùng nên nằm trên và nhận cảm ứng trước phím trắng.
        for (i in 0 until whiteCount - 1) {
            if (i % 7 in BlackAfterWhite) {
                PianoKey(
                    whiteNotes[i] + 1, true, accent, onNoteOn, onNoteOff,
                    Modifier
                        .offset(x = keyWidth * (i + 1) - blackWidth / 2)
                        .width(blackWidth)
                        .fillMaxHeight(0.62f),
                )
            }
        }
    }
}

@Composable
private fun PianoKey(
    note: Int,
    black: Boolean,
    accent: Color,
    onNoteOn: (Int) -> Unit,
    onNoteOff: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val fill = when {
        pressed -> accent
        black -> Color(0xFF15171C)
        else -> Color(0xFFF4F4F6)
    }
    Box(
        modifier
            .padding(horizontal = if (black) 0.dp else 1.dp)
            .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
            .background(fill)
            .pointerInput(note) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        onNoteOn(note)
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                            onNoteOff(note)
                        }
                    },
                )
            },
    )
}
