package com.bandstudio.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Biểu tượng vẽ bằng Canvas để không phụ thuộc thư viện icon.

@Composable
fun PlayGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val p = Path().apply {
            moveTo(size.width * 0.32f, size.height * 0.2f)
            lineTo(size.width * 0.84f, size.height * 0.5f)
            lineTo(size.width * 0.32f, size.height * 0.8f)
            close()
        }
        drawPath(p, color)
    }
}

@Composable
fun StopGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        drawRect(
            color,
            topLeft = Offset(size.width * 0.25f, size.height * 0.25f),
            size = Size(size.width * 0.5f, size.height * 0.5f),
        )
    }
}

@Composable
fun RecordGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) { drawCircle(color, radius = size.minDimension * 0.28f) }
}

@Composable
fun RewindGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        drawRect(
            color,
            topLeft = Offset(size.width * 0.22f, size.height * 0.25f),
            size = Size(size.width * 0.09f, size.height * 0.5f),
        )
        val p = Path().apply {
            moveTo(size.width * 0.78f, size.height * 0.22f)
            lineTo(size.width * 0.78f, size.height * 0.78f)
            lineTo(size.width * 0.36f, size.height * 0.5f)
            close()
        }
        drawPath(p, color)
    }
}

@Composable
fun RoundButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) cs.primary else cs.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) cs.onPrimary else cs.onSurface,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Nút tròn nhỏ có chữ (M = tắt tiếng, S = nghe riêng). */
@Composable
fun SmallToggle(label: String, on: Boolean, onColor: Color, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(if (on) onColor else cs.outline.copy(alpha = 0.35f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) Color.Black else cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
}
