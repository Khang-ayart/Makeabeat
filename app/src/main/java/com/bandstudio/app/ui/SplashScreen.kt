package com.bandstudio.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bandstudio.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Magenta = Color(0xFFE040FF)
private val Lavender = Color(0xFFB9A8FF)
private val Cyan = Color(0xFF35E6FF)

/** Chữ neon: một lớp nhòe rộng phía dưới + một lớp nhòe hẹp + chữ sáng. */
@Composable
private fun NeonText(text: String, color: Color, size: Int, weight: FontWeight, modifier: Modifier = Modifier) {
    Box(modifier) {
        for ((blur, a) in listOf(46f to 0.55f, 22f to 0.8f)) {
            Text(
                text,
                modifier = Modifier.alpha(a),
                style = TextStyle(
                    color = color, fontSize = size.sp, fontWeight = weight, letterSpacing = 2.sp,
                    shadow = Shadow(color = color, offset = Offset.Zero, blurRadius = blur),
                ),
            )
        }
        Text(
            text,
            style = TextStyle(
                color = Color.White.copy(alpha = 0.92f).let { lerpToward(it, color, 0.25f) }, fontSize = size.sp, fontWeight = weight, letterSpacing = 2.sp,
                shadow = Shadow(color = color, offset = Offset.Zero, blurRadius = 10f),
            ),
        )
    }
}

private fun lerpToward(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f,
)

/**
 * Màn hình chờ: ảnh nền, chữ "Make" "a" "beat" lần lượt hiện lên màu neon, dưới là "Made by RyoK" gõ chữ dần.
 * Chạm để bỏ qua. Khoảng 4 giây rồi mờ dần vào ứng dụng.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val words = listOf("Make" to Magenta, "a" to Lavender, "beat" to Cyan)
    val shown = remember { List(words.size) { Animatable(0f) } }
    val fade = remember { Animatable(1f) }
    val credit = "Made by RyoK"
    var typed by remember { mutableStateOf(0) }
    var done by remember { mutableStateOf(false) }

    val flick = rememberInfiniteTransition(label = "neon")
    val pulse by flick.animateFloat(
        initialValue = 0.82f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "pulse",
    )
    val cursor by flick.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse), label = "cursor",
    )

    LaunchedEffect(Unit) {
        delay(450)
        for ((i, a) in shown.withIndex()) {
            launch { a.animateTo(1f, tween(520)) }
            delay(if (i == 0) 620L else 420L)
        }
        delay(500)
        for (i in 1..credit.length) { typed = i; delay(90) }
        delay(1100)
        if (!done) { done = true; fade.animateTo(0f, tween(450)); onFinished() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF05030F))
            .alpha(fade.value)
            .pointerInput(Unit) { detectTapGestures { if (!done) { done = true; onFinished() } } },
    ) {
        Image(
            painter = painterResource(R.drawable.splash_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(0.70f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                for ((i, w) in words.withIndex()) {
                    val v = shown[i].value
                    NeonText(
                        w.first, w.second, if (w.first == "a") 40 else 48, FontWeight.ExtraBold,
                        Modifier.graphicsLayer { alpha = v * pulse; translationY = (1f - v) * 36f; scaleX = 0.85f + 0.15f * v; scaleY = 0.85f + 0.15f * v },
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                credit.take(typed) + if (typed < credit.length || cursor > 0.5f) "▌" else " ",
                style = TextStyle(
                    color = Color(0xFFD9E4FF), fontSize = 17.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp,
                    shadow = Shadow(color = Cyan, offset = Offset.Zero, blurRadius = 14f),
                ),
                modifier = Modifier.alpha(if (typed == 0) 0f else 1f),
            )
            Spacer(Modifier.weight(0.30f))
        }
    }
}
