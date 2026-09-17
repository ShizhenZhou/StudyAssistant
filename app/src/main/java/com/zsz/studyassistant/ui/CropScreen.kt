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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.zsz.studyassistant.data.StudyAssistant
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
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

private const val EDGE = 48f        // 边缘命中带宽度(px)（加粗，增大判定面积）
private const val CORNER = 56f      // 四角命中半径(px)
private const val MIN_SIZE = 60f    // 选框最小尺寸(px)
private val DIM = Color(0x66000000) // 选区外部遮罩
private val BORDER = Color(0xFFFF5252)

@Composable
fun CropScreen(nav: NavHostController, vm: MainViewModel) {
    val s = LocalStrings.current
    val path = vm.pendingImagePath
    val bitmap = remember(path) {
        path?.let {
            try { ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.io.File(it))) } catch (e: Exception) { null }
        }
    }

    if (bitmap == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text(s["crop.noImage"], style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { nav.popBackStack() }) { Text(s["common.back"]) }
        }
        return
    }

    var sel by remember { mutableStateOf<Rect?>(null) }
    var dispRect by remember { mutableStateOf(Rect.Zero) }
    var mode by remember { mutableStateOf("NONE") }
    // 预框选：用户一旦手动调整过，就不再被自动结果覆盖（避免"手还没松又被改回去"）
    var userTouched by remember { mutableStateOf(false) }
    // 是否已跑过一次自动识别（每次换图重来）
    var autoTried by remember(path) { mutableStateOf(false) }
    // 命中自动框选时给用户的提示
    var autoHint by remember { mutableStateOf(false) }
    // 手动 AI 识别中
    var aiBusy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    /** 归一化矩形 → 屏幕显示坐标（相对当前显示的图片区域） */
    fun toDisp(n: com.zsz.studyassistant.data.ImageAutoCrop.NormRect, d: Rect): Rect {
        val l = d.left + n.x * d.width
        val t = d.top + n.y * d.height
        val r = l + n.w * d.width
        val b = t + n.h * d.height
        return Rect(
            l.coerceIn(d.left, d.right - 8f), t.coerceIn(d.top, d.bottom - 8f),
            r.coerceIn(d.left + 8f, d.right), b.coerceIn(d.top + 8f, d.bottom)
        )
    }

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

                // ★ 键必须同时包含 disp 与 path：首次组合时 path 可能还没就绪，
                //   若只以 disp 为键，检测就永远不会触发（预框选静默失效）
                LaunchedEffect(disp, path) {
                    if (disp == Rect.Zero) return@LaunchedEffect
                    dispRect = disp
                    if (sel == null) {
                        // 默认框：居中的 70% 子矩形（不占满屏，方便拖动）
                        // ★ 预框选失败/超时/结果可疑时，就保持这个默认框
                        val w = disp.width * 0.7f
                        val h = disp.height * 0.7f
                        sel = Rect(disp.center.x - w / 2, disp.center.y - h / 2,
                            disp.center.x + w / 2, disp.center.y + h / 2)
                    }
                    // ── 预框选（总预算 0.5 秒）：本地算法与 AI **并行**跑 ──
                    //   · 本地结果（约 100ms）先应用，页面立刻有框
                    //   · AI 若在 500ms 内返回且合法 → 覆盖本地结果
                    //   · 两者都不可靠 → 保持默认框
                    val p = path ?: return@LaunchedEffect
                    if (autoTried) return@LaunchedEffect
                    autoTried = true
                    val bytes = runCatching {
                        withContext(Dispatchers.IO) { java.io.File(p).readBytes() }
                    }.getOrNull()
                    val aiDeferred = if (bytes != null) {
                        async { runCatching { StudyAssistant.detectQuestionBoxesAi(bytes, timeoutMs = 500) }.getOrNull() }
                    } else null
                    // ① 本地投影法：毫秒级，先出结果
                    val local = runCatching {
                        withContext(Dispatchers.Default) {
                            com.zsz.studyassistant.data.ImageAutoCrop.detectQuestionRect(p)
                        }
                    }.getOrNull()
                    val localOk = local?.takeIf { !it.looksUnreliable() }
                    if (localOk != null && !userTouched) {
                        sel = toDisp(localOk, disp)
                        autoHint = true
                    }
                    // ② AI（≤500ms）：合法就覆盖
                    val ai = aiDeferred?.await()?.maxByOrNull { it.area }?.takeIf { !it.looksUnreliable() }
                    if (ai != null && !userTouched) {
                        sel = toDisp(ai, disp)
                        autoHint = true
                    }
                }

                // 自动框选提示 3 秒后淡出
                LaunchedEffect(autoHint) {
                    if (autoHint) { delay(3000); autoHint = false }
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
                        // 边框（加粗，红色可拖拽判定条）
                        drawRect(Color(0xFF000000), topLeft = Offset(r.left, r.top),
                            size = Size(r.width, r.height), style = Stroke(3.dp.toPx()))
                        drawRect(BORDER, topLeft = Offset(r.left, r.top),
                            size = Size(r.width, r.height), style = Stroke(4.dp.toPx()))
                        // 四个角手柄（可任意方向拖拽同时改长宽）
                        val ch = 10.dp.toPx()
                        drawRect(BORDER, Offset(r.left - ch / 2, r.top - ch / 2), Size(ch, ch))
                        drawRect(BORDER, Offset(r.right - ch / 2, r.top - ch / 2), Size(ch, ch))
                        drawRect(BORDER, Offset(r.left - ch / 2, r.bottom - ch / 2), Size(ch, ch))
                        drawRect(BORDER, Offset(r.right - ch / 2, r.bottom - ch / 2), Size(ch, ch))
                    }
                }

                // 手势
                Box(Modifier.fillMaxSize().pointerInput(dispRect) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            userTouched = true
                            val r = sel
                            mode = if (r == null) "NONE" else {
                                val d2 = dispRect
                                when {
                                    // 四角优先
                                    d2 != Rect.Zero && pos.x <= r.left + CORNER && pos.y <= r.top + CORNER -> "TL"
                                    d2 != Rect.Zero && pos.x >= r.right - CORNER && pos.y <= r.top + CORNER -> "TR"
                                    d2 != Rect.Zero && pos.x <= r.left + CORNER && pos.y >= r.bottom - CORNER -> "BL"
                                    d2 != Rect.Zero && pos.x >= r.right - CORNER && pos.y >= r.bottom - CORNER -> "BR"
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
                            userTouched = true
                            val r = sel
                            val d2 = dispRect
                            if (r != null && d2 != Rect.Zero) {
                                val n = when (mode) {
                                    "TOP" -> Rect(r.left, (r.top + drag.y).coerceIn(d2.top, r.bottom - MIN_SIZE), r.right, r.bottom)
                                    "BOTTOM" -> Rect(r.left, r.top, r.right, (r.bottom + drag.y).coerceIn(r.top + MIN_SIZE, d2.bottom))
                                    "LEFT" -> Rect((r.left + drag.x).coerceIn(d2.left, r.right - MIN_SIZE), r.top, r.right, r.bottom)
                                    "RIGHT" -> Rect(r.left, r.top, (r.right + drag.x).coerceIn(r.left + MIN_SIZE, d2.right), r.bottom)
                                    // 四角：同时改长宽
                                    "TL" -> Rect((r.left + drag.x).coerceIn(d2.left, r.right - MIN_SIZE),
                                        (r.top + drag.y).coerceIn(d2.top, r.bottom - MIN_SIZE), r.right, r.bottom)
                                    "TR" -> Rect(r.left, (r.top + drag.y).coerceIn(d2.top, r.bottom - MIN_SIZE),
                                        (r.right + drag.x).coerceIn(r.left + MIN_SIZE, d2.right), r.bottom)
                                    "BL" -> Rect((r.left + drag.x).coerceIn(d2.left, r.right - MIN_SIZE), r.top,
                                        r.right, (r.bottom + drag.y).coerceIn(r.top + MIN_SIZE, d2.bottom))
                                    "BR" -> Rect(r.left, r.top,
                                        (r.right + drag.x).coerceIn(r.left + MIN_SIZE, d2.right),
                                        (r.bottom + drag.y).coerceIn(r.top + MIN_SIZE, d2.bottom))
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
            // 预框选命中提示（3 秒后自动消失）
            if (autoHint) {
                Text(
                    s["crop.autoSelected"],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    textAlign = TextAlign.Center
                )
            }
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
            ) { Text(s["crop.confirm"]) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    vm.solveWithImage(compressBitmap(bitmap))
                    nav.navigate("solve") { popUpTo("crop") { inclusive = true } }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text(s["crop.whole"]) }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { sel = dispRect }) { Text(s["crop.reset"]) }
                // 手动 AI 识别：本地框得不准时点它（这里不受 0.5s 限制，允许等 4 秒）
                TextButton(
                    enabled = !aiBusy && path != null,
                    onClick = {
                        val p = path ?: return@TextButton
                        aiBusy = true
                        scope.launch {
                            val bytes = runCatching {
                                withContext(Dispatchers.IO) { java.io.File(p).readBytes() }
                            }.getOrNull()
                            val boxes = if (bytes != null) runCatching {
                                StudyAssistant.detectQuestionBoxesAi(bytes, timeoutMs = 4000)
                            }.getOrNull() else null
                            val best = boxes?.maxByOrNull { it.area }
                            if (best != null) {
                                sel = toDisp(best, dispRect)
                                autoHint = true
                            }
                            aiBusy = false
                        }
                    }
                ) { Text(if (aiBusy) s["crop.aiBusy"] else s["crop.aiDetect"]) }
                Row {
                    TextButton(onClick = { nav.navigate("camera") }) { Text(s["crop.reshoot"]) }
                    TextButton(onClick = { nav.popBackStack() }) { Text(s["common.backArrow"]) }
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
