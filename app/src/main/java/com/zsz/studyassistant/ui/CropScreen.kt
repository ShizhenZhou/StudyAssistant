package com.zsz.studyassistant.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel
import java.io.ByteArrayOutputStream

private const val EDGE = 36f        // 边缘命中带宽度(px)
private const val MIN_SIZE = 60f    // 选框最小尺寸(px)
private val DIM = Color(0x66000000) // 选区外部遮罩
private val BORDER = Color(0xFFFF5252)

@Composable
fun CropScreen(nav: NavHostController, vm: MainViewModel) {
    val path = vm.pendingImagePath
    val bitmap = remember(path) {
        path?.let {
            try { ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.io.File(it))) } catch (e: Exception) { null }
        }
    }

    if (bitmap == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text("未找到图片，请重新拍照", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { nav.popBackStack() }) { Text("返回") }
        }
        return
    }

    var sel by remember { mutableStateOf<Rect?>(null) }
    var dispRect by remember { mutableStateOf(Rect.Zero) }
    var mode by remember { mutableStateOf("NONE") }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(bottom = 120.dp)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val dens = LocalDensity.current
                val bw = with(dens) { maxWidth.toPx() }
                val bh = with(dens) { maxHeight.toPx() }
                val scale = minOf(bw / bitmap.width, bh / bitmap.height)
                val dw = bitmap.width * scale
                val dh = bitmap.height * scale
                val dl = (bw - dw) / 2f
                val dt = (bh - dh) / 2f
                val disp = Rect(Offset(dl, dt), Offset(dl + dw, dt + dh))

                LaunchedEffect(disp) {
                    dispRect = disp
                    if (sel == null) {
                        // 初始：居中的 70% 子矩形（不占满屏，方便拖动）
                        val w = disp.width * 0.7f
                        val h = disp.height * 0.7f
                        sel = Rect(disp.center.x - w / 2, disp.center.y - h / 2,
                            disp.center.x + w / 2, disp.center.y + h / 2)
                    }
                }

                Image(bitmap = bitmap.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)

                // 选区外遮罩 + 选框边框
                Canvas(Modifier.fillMaxSize()) {
                    val r = sel
                    val d2 = disp
                    if (r != null && d2 != Rect.Zero) {
                        // 四块遮罩
                        drawRect(DIM, Offset(d2.left, d2.top), Size(d2.width, (r.top - d2.top).coerceAtLeast(0f)))
                        drawRect(DIM, Offset(d2.left, r.bottom), Size(d2.width, (d2.bottom - r.bottom).coerceAtLeast(0f)))
                        drawRect(DIM, Offset(d2.left, r.top), Size((r.left - d2.left).coerceAtLeast(0f), r.height))
                        drawRect(DIM, Offset(r.right, r.top), Size((d2.right - r.right).coerceAtLeast(0f), r.height))
                        // 边框
                        drawRect(Color(0xFF000000), topLeft = Offset(r.left, r.top),
                            size = Size(r.width, r.height), style = Stroke(1.5.dp.toPx()))
                        drawRect(BORDER, topLeft = Offset(r.left, r.top),
                            size = Size(r.width, r.height), style = Stroke(2.dp.toPx()))
                        // 四条边的高亮手柄
                        val e = 6.dp.toPx()
                        drawRect(Color(0xAA2196F3), Offset(r.left, r.top), Size(r.width, e))
                        drawRect(Color(0xAA2196F3), Offset(r.left, r.bottom - e), Size(r.width, e))
                        drawRect(Color(0xAA2196F3), Offset(r.left, r.top), Size(e, r.height))
                        drawRect(Color(0xAA2196F3), Offset(r.right - e, r.top), Size(e, r.height))
                    }
                }

                // 手势
                Box(Modifier.fillMaxSize().pointerInput(dispRect) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val r = sel
                            mode = if (r == null) "NONE" else {
                                val d2 = dispRect
                                when {
                                    d2 != Rect.Zero && pos.y >= r.top - EDGE && pos.y <= r.top + EDGE &&
                                        pos.x >= r.left - EDGE && pos.x <= r.right + EDGE -> "TOP"
                                    d2 != Rect.Zero && pos.y >= r.bottom - EDGE && pos.y <= r.bottom + EDGE &&
                                        pos.x >= r.left - EDGE && pos.x <= r.right + EDGE -> "BOTTOM"
                                    d2 != Rect.Zero && pos.x >= r.left - EDGE && pos.x <= r.left + EDGE &&
                                        pos.y >= r.top - EDGE && pos.y <= r.bottom + EDGE -> "LEFT"
                                    d2 != Rect.Zero && pos.x >= r.right - EDGE && pos.x <= r.right + EDGE &&
                                        pos.y >= r.top - EDGE && pos.y <= r.bottom + EDGE -> "RIGHT"
                                    r.contains(pos) -> "MOVE"
                                    else -> "NONE"
                                }
                            }
                        },
                        onDrag = { change, drag ->
                            val r = sel
                            val d2 = dispRect
                            if (r != null && d2 != Rect.Zero) {
                                val n = when (mode) {
                                    "TOP" -> Rect(r.left, (r.top + drag.y).coerceIn(d2.top, r.bottom - MIN_SIZE), r.right, r.bottom)
                                    "BOTTOM" -> Rect(r.left, r.top, r.right, (r.bottom + drag.y).coerceIn(r.top + MIN_SIZE, d2.bottom))
                                    "LEFT" -> Rect((r.left + drag.x).coerceIn(d2.left, r.right - MIN_SIZE), r.top, r.right, r.bottom)
                                    "RIGHT" -> Rect(r.left, r.top, (r.right + drag.x).coerceIn(r.left + MIN_SIZE, d2.right), r.bottom)
                                    "MOVE" -> {
                                        val dt = (r.left + drag.x).coerceIn(d2.left, d2.right - r.width)
                                        val dtp = (r.top + drag.y).coerceIn(d2.top, d2.bottom - r.height)
                                        Rect(dt, dtp, dt + r.width, dtp + r.height)
                                    }
                                    else -> null
                                }
                                if (n != null) sel = n
                            }
                            change.consume()
                        }
                    )
                })
            }
        }

        // 底部按钮
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
            Button(
                onClick = {
                    val r = sel ?: dispRect
                    if (r != null && dispRect != Rect.Zero) {
                        val d2 = dispRect
                        val x = ((r.left - d2.left) / d2.width * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                        val y = ((r.top - d2.top) / d2.height * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                        val w = ((r.width / d2.width) * bitmap.width).toInt().coerceIn(1, bitmap.width - x)
                        val h = ((r.height / d2.height) * bitmap.height).toInt().coerceIn(1, bitmap.height - y)
                        val cropped = Bitmap.createBitmap(bitmap, x, y, w, h)
                        vm.solveWithImage(compressBitmap(cropped))
                        nav.navigate("solve") { popUpTo("crop") { inclusive = true } }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("✅ 确认框选并解答") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    vm.solveWithImage(compressBitmap(bitmap))
                    nav.navigate("solve") { popUpTo("crop") { inclusive = true } }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("整张图片（跳过框选）") }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { sel = dispRect }) { Text("重置框选") }
                Row {
                    TextButton(onClick = { nav.navigate("camera") }) { Text("📷 重新拍摄") }
                    TextButton(onClick = { nav.popBackStack() }) { Text("← 返回") }
                }
            }
        }
    }
}

private fun compressBitmap(bmp: Bitmap, quality: Int = 85): ByteArray {
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}
