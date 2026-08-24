package com.zsz.studyassistant.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel
import java.io.ByteArrayOutputStream
import java.io.File

private const val HANDLE = 40f

@Composable
fun CropScreen(nav: NavHostController, vm: MainViewModel) {
    val context = LocalContext.current
    val path = vm.pendingImagePath
    val bitmap = remember(path) {
        path?.let {
            try { ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(it))) } catch (e: Exception) { null }
        }
    }

    if (bitmap == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text("未找到图片，请重新拍照")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { nav.popBackStack() }) { Text("返回") }
        }
        return
    }

    // 选择框（展示坐标），初始为空，布局后初始化为整图
    var sel by remember { mutableStateOf<Rect?>(null) }
    var mode by remember { mutableStateOf("NONE") }
    var dispRect by remember { mutableStateOf(Rect.Zero) }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = 120.dp)
        ) {
            // 计算图片显示区域(Fit 缩放，居中)
            val sizeState = androidx.compose.foundation.layout.BoxWithConstraints(
                Modifier.fillMaxSize()
            ) {
                val bw = maxWidth
                val bh = maxHeight
                val scale = minOf(bw.value / bitmap.width, bh.value / bitmap.height)
                val dispW = bitmap.width * scale
                val dispH = bitmap.height * scale
                val left = (bw.value - dispW) / 2f
                val top = (bh.value - dispH) / 2f
                val disp = Rect(Offset(left, top), Offset(left + dispW, top + dispH))

                LaunchedEffect(disp) {
                    dispRect = disp
                    if (sel == null) sel = disp
                }

                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )

                // 半透明遮罩之外的区域(简化：画一条边框矩形)
                sel?.let { r ->
                    Box(
                        Modifier
                            .offset { IntOffset(r.left.toInt(), r.top.toInt()) }
                            .size(r.width.dp, r.height.dp)
                            .border(2.dp, Color.Red)
                            .background(Color(0x22000000))
                    )
                    // 右下角手柄
                    Box(
                        Modifier
                            .offset { IntOffset((r.right - HANDLE).toInt(), (r.bottom - HANDLE).toInt()) }
                            .size(with(LocalDensity.current) { HANDLE.dp })
                            .border(2.dp, Color.White)
                            .background(Color(0x88000000))
                    )
                }
            }
            // 手势
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(dispRect) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                val r = sel
                                mode = if (r == null) "NONE" else {
                                    val handle = Rect(r.right - HANDLE, r.bottom - HANDLE, r.right, r.bottom)
                                    when {
                                        handle.contains(pos) -> "RESIZE"
                                        r.contains(pos) -> "MOVE"
                                        else -> "NONE"
                                    }
                                }
                            },
                            onDrag = { change, drag ->
                                val r = sel
                                if (r != null && dispRect != Rect.Zero) {
                                    val d = dispRect
                                    when (mode) {
                                        "MOVE" -> {
                                            val n = Rect(
                                                (r.left + drag.x).coerceIn(d.left, d.right - r.width),
                                                (r.top + drag.y).coerceIn(d.top, d.bottom - r.height),
                                                r.right + drag.x, r.bottom + drag.y
                                            )
                                            sel = Rect(n.left, n.top, n.right, n.bottom)
                                        }
                                        "RESIZE" -> {
                                            sel = Rect(
                                                r.left, r.top,
                                                (r.right + drag.x).coerceIn(r.left + 40, d.right),
                                                (r.bottom + drag.y).coerceIn(r.top + 40, d.bottom)
                                            )
                                        }
                                    }
                                }
                                change.consume()
                            }
                        )
                    }
            )
        }

        // 底部按钮
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Button(
                onClick = {
                    val r = sel ?: dispRect
                    if (r != null && dispRect != Rect.Zero) {
                        val d = dispRect
                        val x = ((r.left - d.left) / d.width * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                        val y = ((r.top - d.top) / d.height * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                        val w = ((r.width / d.width) * bitmap.width).toInt().coerceAtMost(bitmap.width - x)
                        val h = ((r.height / d.height) * bitmap.height).toInt().coerceAtMost(bitmap.height - y)
                        val cropped = Bitmap.createBitmap(bitmap, x, y, w.coerceAtLeast(1), h.coerceAtLeast(1))
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
                TextButton(onClick = { nav.popBackStack() }) { Text("← 返回") }
            }
        }
    }
}

private fun compressBitmap(bmp: Bitmap, quality: Int = 85): ByteArray {
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}
