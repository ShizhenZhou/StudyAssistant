package com.zsz.studyassistant.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
internal fun CaptureProgress(step: Int, hint: String, firstPath: String?, enabled: Boolean,
                             onRestart: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val thumbnail by produceState<Pair<String, android.graphics.Bitmap?>?>(null, firstPath) {
        value = firstPath?.let { path ->
            path to kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.zsz.studyassistant.data.CaptureImage.decode(path, maxDim = 192) }.getOrNull()
            }
        }
    }
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = Color.Black.copy(alpha = .72f),
        contentColor = Color.White) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            thumbnail?.takeIf { it.first == firstPath }?.second?.let {
                Image(it.asImageBitmap(), contentDescription = s["camera.capturedFirst"],
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("$step / 2", style = MaterialTheme.typography.labelLarge)
                Text(hint, style = MaterialTheme.typography.bodySmall)
            }
            if (firstPath != null) TextButton(onClick = onRestart, enabled = enabled,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                Text(s["camera.restart"], style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun FocusRing(center: Offset, focused: Boolean) {
    val scale by animateFloatAsState(targetValue = if (focused) 1f else 0.7f, label = "focusScale")
    val alpha by animateFloatAsState(targetValue = if (focused) 1f else 0.55f, label = "focusAlpha")
    val density = LocalDensity.current
    Canvas(Modifier.fillMaxSize()) {
        val c = center
        val half = with(density) { 32.dp.toPx() } * scale   // 半边长 → 整框 ≈ 64dp
        val le = with(density) { 16.dp.toPx() } * scale     // 角长
        val th = with(density) { 3.dp.toPx() }              // 线宽
        val dot = with(density) { 3.dp.toPx() }
        val color = if (focused) Color(0xFF4CAF50) else Color.White
        // 四个 L 形角
        drawLine(color, Offset(c.x - half, c.y - half), Offset(c.x - half + le, c.y - half), th)
        drawLine(color, Offset(c.x - half, c.y - half), Offset(c.x - half, c.y - half + le), th)
        drawLine(color, Offset(c.x + half, c.y - half), Offset(c.x + half - le, c.y - half), th)
        drawLine(color, Offset(c.x + half, c.y - half), Offset(c.x + half, c.y - half + le), th)
        drawLine(color, Offset(c.x - half, c.y + half), Offset(c.x - half + le, c.y + half), th)
        drawLine(color, Offset(c.x - half, c.y + half), Offset(c.x - half, c.y + half - le), th)
        drawLine(color, Offset(c.x + half, c.y + half), Offset(c.x + half - le, c.y + half), th)
        drawLine(color, Offset(c.x + half, c.y + half), Offset(c.x + half, c.y + half - le), th)
        // 中心小点
        drawCircle(color.copy(alpha = alpha * 0.8f), radius = dot, center = c)
    }
}

/** 快门图标：外环 + 中心（相机光圈） */
@Composable
internal fun ShutterIcon(size: androidx.compose.ui.unit.Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val c = center
        val r = this.size.minDimension / 2f
        drawCircle(color = color, radius = r, center = c)
        drawCircle(color = Color(0x33000000), radius = r * 0.52f, center = c)
        drawCircle(color = color, radius = r * 0.5f, center = c)
    }
}

/** 花瓣图标：6 片花瓣环绕中心，粉→紫渐变，中心黄色花蕊（图库） */
@Composable
internal fun FlowerIcon(size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val c = center
        val r = this.size.minDimension / 5.2f
        // 花瓣：由中心向外 粉→紫 径向渐变
        val brush = Brush.radialGradient(
            colors = listOf(Color(0xFFF48FB1), Color(0xFFAB47BC)),
            center = c,
            radius = this.size.minDimension * 0.55f
        )
        for (i in 0 until 6) {
            rotate(i * 60f, c) {
                drawOval(
                    brush = brush,
                    topLeft = Offset(c.x - r * 0.52f, c.y - r * 2.2f),
                    size = Size(r, r * 2.2f)
                )
            }
        }
        // 花蕊：黄色圆心 + 白色描边
        drawCircle(color = Color(0xFFFFD54F), radius = r * 0.55f, center = c)
        drawCircle(color = Color.White, radius = r * 0.55f, center = c, style = Stroke(2f))
    }
}
