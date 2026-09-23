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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 「通用 → AI 框选时限」：AI 自动框选的等待上限
    val aiCropMs = com.zsz.studyassistant.data.CapturePrefs.aiCropTimeoutMs(ctx)
    val path = vm.currentCropPath
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
    // AI 预框选每次换图只请求一次
    var aiLaunched by remember(path) { mutableStateOf(false) }
    // 手动 AI 识别中
    var aiBusy by remember { mutableStateOf(false) }
val scope = androidx.compose.runtime.rememberCoroutineScope()

    /** 返回 = 回到拍摄界面（放弃本次框选） */
    fun backToCamera() {
        vm.cancelCropFlow()
        nav.navigate("camera") { popUpTo("crop") { inclusive = true } }
    }

    /**
     * 返回键 / 返回手势：
     *  · 正在框第 2 张 → 回到第 1 张重新框（autoTried 因 remember(path) 自动重置 → 本地+AI 重新跑一次）
     *  · 正在框第 1 张 → 回拍摄界面
     */
    fun handleBack() {
        if (vm.cropIndex > 0) {
            vm.cropGoBackOne()
            userTouched = false
            autoHint = false
        } else {
            backToCamera()
        }
    }

    /** 提交一张之后：还差一张 → 回拍摄界面；队列还有下一张 → 留在本页继续框；都完成 → 进入解题/批改 */
    fun afterSubmit() {
        when {
            vm.cropNeedsMore -> nav.navigate("camera") { popUpTo("crop") { inclusive = true } }
            vm.cropHasNext -> Unit   // ★ 留在框选页继续框下一张（界面会自动换图）
            else -> nav.navigate("solve") { popUpTo("crop") { inclusive = true } }
        }
    }

    // 系统返回键 / 返回手势 → 也回拍摄界面
    androidx.activity.compose.BackHandler { handleBack() }

    /** 归一化矩形 → 屏幕显示坐标（相对当前显示的图片区域） */
    /** 安全钳制：区间非法（min > max，例如显示矩形瞬时退化成 0 宽/高）时取中点，
 *  绝不抛 IllegalArgumentException（曾导致"拖动选框时应用自动退出"）。 */
fun safeCoerceRange(v: Float, a: Float, b: Float): Float =
    if (a <= b) v.coerceIn(a, b) else (a + b) / 2f
fun toDisp(n: com.zsz.studyassistant.data.ImageAutoCrop.NormRect, d: Rect): Rect {
        // 布局未就绪 / 缩放过程中 d 可能退化（宽或高接近 0）→ 直接返回，避免后续出现非法区间
    if (d.width < 2f || d.height < 2f) return Rect(d.left, d.top, d.left + 1f, d.top + 1f)
    val l = d.left + n.x * d.width
        val t = d.top + n.y * d.height
        val r = l + n.w * d.width
        val b = t + n.h * d.height
        return Rect(
            safeCoerceRange(l, d.left, d.right - 8f), safeCoerceRange(t, d.top, d.bottom - 8f),
            safeCoerceRange(r, d.left + 8f, d.right), safeCoerceRange(b, d.top + 8f, d.bottom)
        )
    }

    // 缩放 / 平移（双指捏合调整图片大小；1x~4x）
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    // zoom=1 时的图片显示矩形（按钮里复位缩放用）
    var dispBase by remember { mutableStateOf(Rect.Zero) }
    var panY by remember { mutableFloatStateOf(0f) }

    // ★ 用 Column 布局：图片显示区 = weight(1f)，下缘正好挨着按钮上缘（不会再被按钮盖住）
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val dens = LocalDensity.current
                val bw = with(dens) { maxWidth.toPx() }
                val bh = with(dens) { maxHeight.toPx() }
                val scale = minOf(bw / bitmap.width, bh / bitmap.height) * zoom
                val dw = bitmap.width * scale
                val dh = bitmap.height * scale
                val dl = (bw - dw) / 2f + panX
                val dt = (bh - dh) / 2f + panY
                val disp = Rect(Offset(dl, dt), Offset(dl + dw, dt + dh))
                LaunchedEffect(bw, bh, bitmap) {
                    val bs = minOf(bw / bitmap.width, bh / bitmap.height)
                    val bdw = bitmap.width * bs
                    val bdh = bitmap.height * bs
                    dispBase = Rect(
                        Offset((bw - bdw) / 2f, (bh - bdh) / 2f),
                        Offset((bw + bdw) / 2f, (bh + bdh) / 2f)
                    )
                }

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
                    // ★ AI 请求挂到 composable 作用域的 scope 上（**不在这里 await**）：
                    //   本 effect 的键含 disp，而 disp 在页面布局稳定时会变 → effect 重启；
                    //   若在 effect 内 await，请求会被取消，AI 预框选等于白等（曾长期拿不到结果）。
                    if (bytes != null && !aiLaunched) {
                        aiLaunched = true
                        scope.launch {
                            val ai = runCatching {
                                StudyAssistant.detectQuestionBoxesAi(bytes, timeoutMs = aiCropMs)
                            }.getOrNull()?.maxByOrNull { it.area }?.takeIf { !it.looksUnreliable() }
                            if (ai == null || userTouched) return@launch
                            val d = dispRect
                            if (d.width < 2f) return@launch
                            sel = toDisp(ai, d)
                            autoHint = true
                        }
                    }
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
                }

                // 自动框选提示 3 秒后淡出
                LaunchedEffect(autoHint) {
                    if (autoHint) { delay(3000); autoHint = false }
                }

                // 按计算出的 disp 绘制图片（这样缩放/平移后选区仍与图片对齐）
                Canvas(Modifier.fillMaxSize()) {
                    val d2 = disp
                    if (d2 != Rect.Zero) {
                        drawImage(
                            image = bitmap.asImageBitmap(),
                            dstOffset = androidx.compose.ui.unit.IntOffset(d2.left.toInt(), d2.top.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(d2.width.toInt(), d2.height.toInt())
                        )
                    }
                }

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

                // 手势层：**同一个 Box** 上挂两个 pointerInput
                //  ① 双指缩放：走 Initial pass，先于拖动看到事件；只在 ≥2 指时消费（所以不会影响单指拖框）
                //  ② 单指拖动/改框
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                var prevDist = 0f
                                // ★ 记录当前参与缩放的两根手指，换手指就重新起算（防止把单指拖动误判成缩放）
                                var idA = -1L
                                var idB = -1L
                                var zoomed = false
                                while (true) {
                                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                                    val pressed = ev.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break
                                    if (pressed.size < 2) { prevDist = 0f; idA = -1L; idB = -1L; continue }
                                    if (pressed[0].id.value.toLong() != idA || pressed[1].id.value.toLong() != idB) {
                                        // 换了一对手指 → 只记基准，不做缩放
                                        idA = pressed[0].id.value.toLong()
                                        idB = pressed[1].id.value.toLong()
                                        prevDist = (pressed[0].position - pressed[1].position).getDistance()
                                        continue
                                    }
                                    val p0 = pressed[0].position
                                    val p1 = pressed[1].position
                                    val dist = (p0 - p1).getDistance()
                                    // 允许在红框内捏合，也允许在红框外捏合；但两指必须在同一侧（不跨越框边）
                                    val r0 = sel
                                    val sameSide = if (r0 == null) true
                                    else (r0.contains(p0) && r0.contains(p1)) ||
                                        (!r0.contains(p0) && !r0.contains(p1))
                                    // ★ 两指中点必须落在图片内：落在图片外的黑色区域不响应缩放
                                    val mid = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
                                    val dNow = dispRect
                                    val onImage = dNow.width <= 1f || dNow.contains(mid)
                                    if (sameSide && onImage && prevDist > 1f && dist > 1f) {
                                        // 单次事件限幅 ±10%，避免异常比值把图瞬间放大/缩小
                                        val ratio = safeCoerceRange(dist / prevDist, 0.9f, 1.1f)
                                        val newZoom = safeCoerceRange(zoom * ratio, 1f, 4f)
                                        if (newZoom != zoom) {
                                            // ★ 以两指中点为中心缩放：中点下方的图像内容保持不动
                                            val oldDisp = dispRect
                                            val k = newZoom / zoom          // 实际比例（含夹紧后的修正）
                                            val cx = (p0.x + p1.x) / 2f
                                            val cy = (p0.y + p1.y) / 2f
                                            zoom = newZoom
                                            val nScale = minOf(bw / bitmap.width, bh / bitmap.height) * newZoom
                                            val nw = bitmap.width * nScale
                                            val nh = bitmap.height * nScale
                                            // newDl = c - k*(c - oldDl)  →  反解出 panX / panY
                                            val wantDl = if (oldDisp.width > 0f) cx - k * (cx - oldDisp.left) else (bw - nw) / 2f
                                            val wantDt = if (oldDisp.height > 0f) cy - k * (cy - oldDisp.top) else (bh - nh) / 2f
                                            // ★ 允许边缘留黑边：不做"必须盖满可视区"的夹紧，
                                            //   只保证至少 15% 的图像仍在视图内（避免整张图被推出屏幕）
                                            val limX = (nw / 2f + bw / 2f - nw * 0.15f).coerceAtLeast(0f)
                                            val limY = (nh / 2f + bh / 2f - nh * 0.15f).coerceAtLeast(0f)
                                            panX = (wantDl - (bw - nw) / 2f).coerceIn(-limX, limX)
                                            panY = (wantDt - (bh - nh) / 2f).coerceIn(-limY, limY)
                                            val nl = (bw - nw) / 2f + panX
                                            val nt = (bh - nh) / 2f + panY
                                            // ★ 红框保持"屏幕上原大小原位"不动（只动图片）
                                            dispRect = Rect(Offset(nl, nt), Offset(nl + nw, nt + nh))
                                            zoomed = true
                                            userTouched = true
                                        }
                                    }
                                    prevDist = dist
                                    ev.changes.forEach { it.consume() }
                                }
                                // ★ 一次缩放结束后：把越界的红框边收回图片范围内
                                if (zoomed) {
                                    val d2 = dispRect
                                    val c = sel
                                    if (c != null && d2.width > 20f && d2.height > 20f) {
                                        val l = c.left.coerceIn(d2.left, (d2.right - MIN_SIZE).coerceAtLeast(d2.left + 10f))
                                        val t = c.top.coerceIn(d2.top, (d2.bottom - MIN_SIZE).coerceAtLeast(d2.top + 10f))
                                        val rr = c.right.coerceIn((l + MIN_SIZE).coerceAtMost(d2.right), d2.right)
                                        val bb = c.bottom.coerceIn((t + MIN_SIZE).coerceAtMost(d2.bottom), d2.bottom)
                                        sel = Rect(l, t, rr, bb)
                                    }
                                }
                            }
                        }
                        .pointerInput(dispRect) {
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
                                    "TOP" -> Rect(r.left, safeCoerceRange(r.top + drag.y, d2.top, r.bottom - MIN_SIZE), r.right, r.bottom)
                                    "BOTTOM" -> Rect(r.left, r.top, r.right, safeCoerceRange(r.bottom + drag.y, r.top + MIN_SIZE, d2.bottom))
                                    "LEFT" -> Rect(safeCoerceRange(r.left + drag.x, d2.left, r.right - MIN_SIZE), r.top, r.right, r.bottom)
                                    "RIGHT" -> Rect(r.left, r.top, safeCoerceRange(r.right + drag.x, r.left + MIN_SIZE, d2.right), r.bottom)
                                    // 四角：同时改长宽
                                    "TL" -> Rect(safeCoerceRange(r.left + drag.x, d2.left, r.right - MIN_SIZE),
                                        safeCoerceRange(r.top + drag.y, d2.top, r.bottom - MIN_SIZE), r.right, r.bottom)
                                    "TR" -> Rect(r.left, safeCoerceRange(r.top + drag.y, d2.top, r.bottom - MIN_SIZE),
                                        safeCoerceRange(r.right + drag.x, r.left + MIN_SIZE, d2.right), r.bottom)
                                    "BL" -> Rect(safeCoerceRange(r.left + drag.x, d2.left, r.right - MIN_SIZE), r.top,
                                        r.right, safeCoerceRange(r.bottom + drag.y, r.top + MIN_SIZE, d2.bottom))
                                    "BR" -> Rect(r.left, r.top,
                                        safeCoerceRange(r.right + drag.x, r.left + MIN_SIZE, d2.right),
                                        safeCoerceRange(r.bottom + drag.y, r.top + MIN_SIZE, d2.bottom))
                                    "MOVE" -> {
                                        val dt = safeCoerceRange(r.left + drag.x, d2.left, d2.right - r.width)
                                        val dtp = safeCoerceRange(r.top + drag.y, d2.top, d2.bottom - r.height)
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

        // 底部按钮（在 Column 里自然位于图片区下方，不再遮挡图片）
        // Android 15+ 强制 edge-to-edge：补上导航栏内边距，避免按钮贴住手势条
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            // 多张时提示当前是第几张
            if (vm.cropTotal > 1) {
                Text(
                    s.format("crop.multiPos", "i" to "${vm.cropPos}", "n" to "${vm.cropTotal}"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    textAlign = TextAlign.Center
                )
            }
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
                        // 裁剪区域：全部用 safeCoerceRange（原来 1..bitmap.width-x 在贴边时会出现 1 > max → 崩溃）
                        val x = safeCoerceRange((r.left - d2.left) / d2.width * bitmap.width, 0f, (bitmap.width - 1).toFloat()).toInt()
                        val y = safeCoerceRange((r.top - d2.top) / d2.height * bitmap.height, 0f, (bitmap.height - 1).toFloat()).toInt()
                        val w = safeCoerceRange((r.width / d2.width) * bitmap.width, 1f, (bitmap.width - x).toFloat()).toInt()
                        val h = safeCoerceRange((r.height / d2.height) * bitmap.height, 1f, (bitmap.height - y).toFloat()).toInt()
                        val cropped = Bitmap.createBitmap(bitmap, x, y, w, h)
                        vm.submitCropResult(compressBitmap(cropped))
                        afterSubmit()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text(if (vm.cropExpect > 1 && vm.cropPaths.size < vm.cropExpect) s["crop.confirmMore"] else s["crop.confirm"]) }
            Spacer(Modifier.height(8.dp))
            // 「整张图片」：单击 = 跳过这一张（多张时进入下一张）；**按住 2 秒填满 → 松开 = 剩下全部跳过**
            // 没填满松手 = 取消；填满后上滑（填充变浅红）= 取消
            HoldToSkipButton(
                text = s["crop.whole"],
                holdHint = if (vm.cropExpect > 1) s["crop.wholeHoldHint"] else "",
                holdEnabled = vm.cropExpect > 1,
                onTap = {
                    vm.submitCropResult(compressBitmap(bitmap))
                    afterSubmit()
                },
                onSkip = {
                    vm.skipRemainingCrop { p ->
                        runCatching {
                            val b = android.graphics.BitmapFactory.decodeFile(p) ?: return@runCatching null
                            compressBitmap(b)
                        }.getOrNull()
                    }
                    afterSubmit()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // AI 自动框选：等待「AI 框选时限 + 1s」；AI 不行就用本地算法；都不行恢复默认框
                TextButton(
                    enabled = !aiBusy,
                    onClick = {
                        aiBusy = true
                        scope.launch {
                            val p2 = vm.currentCropPath
                            // ★ 先复位缩放/平移：把图片重新填回原位，自动框选的结果才可见可调
                            zoom = 1f
                            panX = 0f
                            panY = 0f
                            if (dispBase.width > 1f) dispRect = dispBase
                            val fallbackDefault = {
                                val w = dispRect.width * 0.7f
                                val h = dispRect.height * 0.7f
                                sel = Rect(dispRect.center.x - w / 2, dispRect.center.y - h / 2,
                                    dispRect.center.x + w / 2, dispRect.center.y + h / 2)
                            }
                            var done = false
                            if (p2 != null) {
                                val bytes = runCatching {
                                    withContext(Dispatchers.IO) { java.io.File(p2).readBytes() }
                                }.getOrNull()
                                if (bytes != null) {
                                    val boxes = runCatching {
                                        StudyAssistant.detectQuestionBoxesAi(bytes, timeoutMs = aiCropMs + 1000L)   // 手动按钮：设置值 + 1s
                                    }.getOrNull()
                                    val best = boxes?.maxByOrNull { it.area }
                                    if (best != null) {
                                        sel = toDisp(best, dispRect); autoHint = true; done = true
                                    }
                                }
                                if (!done) {
                                    val local = runCatching {
                                        withContext(Dispatchers.Default) {
                                            com.zsz.studyassistant.data.ImageAutoCrop.detectQuestionRect(p2)
                                        }
                                    }.getOrNull()
                                    if (local != null && !local.looksUnreliable()) {
                                        sel = toDisp(local, dispRect); autoHint = true; done = true
                                    }
                                }
                            }
                            if (!done) fallbackDefault()
                            userTouched = true
                            aiBusy = false
                        }
                    }
                ) { Text(if (aiBusy) s["crop.aiBusy"] else s["crop.aiAutoCrop"]) }
                // 返回 = 回到拍摄界面（不换行）
                TextButton(onClick = { handleBack() }) {
                    Text(if (vm.cropIndex > 0) s["crop.prevImage"] else s["crop.backToCamera"], maxLines = 1, softWrap = false)
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

/**
 * 「整张图片」按钮（跳过框选）：
 *  · **按住 2 秒**：紫色进度从左往右填满 → **松开才执行跳过**
 *  · 没填满就松手 → 取消（不跳过）
 *  · 填满后**上滑** → 取消跳过
 */
@Composable
private fun HoldToSkipButton(
    text: String,
    holdHint: String,
    onTap: () -> Unit,
    onSkip: () -> Unit,
    holdEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    holdMs: Long = 2000L
) {
    var progress by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }        // 是否已填满（等待松手）
    var slideCancel by remember { mutableStateOf(false) }  // 已上滑到取消区（填充变浅红）
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    armed = false
                    val startY = down.position.y
                    val slideUpPx = 36.dp.toPx()
                    // 单张模式不需要长按：holdEnabled=false 时完全不填充，只保留单击
                    val job = scope.launch {
                        if (!holdEnabled) return@launch
                        val start = System.currentTimeMillis()
                        while (true) {
                            val p = ((System.currentTimeMillis() - start).toFloat() / holdMs).coerceIn(0f, 1f)
                            progress = p
                            if (p >= 1f) { armed = true; break }   // 填满后停住，等松手
                            delay(16)
                        }
                    }
                    var cancelled = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break                                  // 松手
                        val upDist = startY - ch.position.y
                        if (armed) {
                            if (upDist > slideUpPx) {                            // 进入取消区 → 填充变浅红
                                slideCancel = true
                                ev.changes.forEach { it.consume() }
                            } else if (upDist < slideUpPx * 0.6f) {               // 滑回来 → 恢复可跳过
                                slideCancel = false
                            }
                        }
                    }
                    job.cancel()
                    val doSkip = armed && !slideCancel
                    // ★ 只有"几乎没来得及填充"才算单击 —— 否则"想长按但没按满就松手"会被误判成单击、白白跳掉一张
                    val doTap = !armed && progress < 0.2f
                    armed = false
                    slideCancel = false
                    cancelled = false
                    progress = 0f
                    if (doSkip) onSkip() else if (doTap) onTap()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // 紫色进度：从左往右填充
        if (progress > 0f) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(
                        if (slideCancel) Color(0xFFFFCDD2)   // 上滑到取消区：浅红
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
                    )
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, maxLines = 1, softWrap = false)
            // 只有需要提示长按手势时才显示第二行（否则单行文字会偏离按钮中心、显得偏上）
            if (holdHint.isNotEmpty()) {
                Text(
                    holdHint,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}
