package com.zsz.studyassistant.ui

import android.graphics.Bitmap
import com.zsz.studyassistant.data.CaptureImage
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel
import java.io.ByteArrayOutputStream

private const val EDGE = 48f        // 边缘命中带宽度(px)（加粗，增大判定面积）
private const val CORNER = 56f      // 四角命中半径(px)
private val DIM = Color(0x66000000) // 选区外部遮罩
private val BORDER = Color(0xFFFF5252)
// 注：框选相关的**纯数学**（MIN_SIZE / safeCoerceRange / normalizeSelRect）在 `ui/capture/CropMath.kt`，
//     单独成文件是为了能被本机单测加载（那个文件不碰 Compose 的 Color，见 CropMathTest）。

@Composable
fun CropScreen(nav: NavHostController, vm: MainViewModel, attachment: Boolean = false) {
    val s = LocalStrings.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val flow = if (attachment) vm.attachmentCropFlow else vm.captureFlow
    if (flow == null) {
        androidx.activity.compose.BackHandler { AppNav.backFromAttachmentCrop(nav) }
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s["crop.noImage"])
            TextButton(onClick = { AppNav.backFromAttachmentCrop(nav) }) { Text(s["crop.backToInput"]) }
        }
        return
    }
    // The preference controls automatic AI only; manual AI remains available.
    val autoAiCrop = com.zsz.studyassistant.data.CapturePrefs.aiCropEnabled(ctx)
    // 「通用 → AI 框选时限」：AI 自动框选的等待上限
    val aiCropMs = com.zsz.studyassistant.data.CapturePrefs.aiCropTimeoutMs(ctx)
    val path = flow.currentCropPath
    fun handleBack() {
        if (flow.cropIndex > 0) {
            flow.cropGoBackOne()
        } else {
            flow.cancelCropFlow()
            if (attachment) AppNav.backFromAttachmentCrop(nav) else AppNav.backFromCropToCamera(nav)
        }
    }
    androidx.activity.compose.BackHandler { handleBack() }
    var loading by remember(path) { mutableStateOf(true) }
    LaunchedEffect(flow, path) {
        loading = true
        try { path?.let { flow.loadPreview(it) } }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { /* Keep the normal unreadable-image/back UI. */ }
        finally { loading = false }
    }
    val preview = flow.preview?.takeIf { it.path == path }
    val bitmap = preview?.bitmap
    val turns = preview?.turns ?: 0

    if (bitmap == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            if (loading) androidx.compose.material3.CircularProgressIndicator()
            else Text(s["crop.noImage"], style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { handleBack() }) { Text(s["common.back"]) }
        }
        return
    }

    val region = remember(path) { flow.cropSelection(requireNotNull(path)) }
    val selection = remember(region, turns) { DisplayedCrop(region) }
    var mode by remember { mutableStateOf("NONE") }
    /** 把当前选框夹回图片显示范围（换图、自动框写回、缩放结束后各调一次） */
    fun normalizeSel() { selection.rect = selection.rect?.let { normalizeSelRect(it, selection.bounds) } }
    // 预框选：用户一旦手动调整过，就不再被自动结果覆盖（避免"手还没松又被改回去"）
    // ★ 2026-10-13：现在**手指一按下就置位**（见手势层的 awaitFirstDown），堵住"按下→超过 touch slop"那段窗口。
    val aiBusy = region.status == "crop.aiBusy"
    val feedback = LocalAppFeedback.current
    DisposableEffect(region, feedback) {
        onDispose { feedback.dismiss("cropAi") }
    }
    LaunchedEffect(region, region.status) {
        val result = region.status ?: return@LaunchedEffect
        if (result != "crop.aiBusy") {
            feedback.show(s[result], isError = result != "crop.aiApplied", source = "cropAi")
            region.status = null
        }
    }
    val scope = rememberCoroutineScope()
    var submitting by remember { mutableStateOf(false) }
    var imageFailed by remember(path) { mutableStateOf(false) }

    /** 提交一张之后：还差一张 → 回拍摄界面；队列还有下一张 → 留在本页继续框；都完成 → 进入解题/批改 */
    fun afterSubmit() {
        when {
            flow.cropNeedsMore -> AppNav.backFromCropToCamera(nav)
            flow.cropHasNext -> Unit   // ★ 留在框选页继续框下一张（界面会自动换图）
            // 框选完成 → 进解题页；来路 = 相机（返回箭头因此回拍题页，方便再拍一张）
            attachment -> AppNav.backFromAttachmentCrop(nav)
            else -> AppNav.openSolve(nav, AppNav.Origin.CAMERA)
        }
    }

    // Decode/compress away from Main; an old result cannot advance a replaced/cancelled crop flow.
    fun submitImages(all: Boolean, encode: () -> List<ByteArray>) {
        if (submitting || flow.rotating || flow.currentCropPath != path || flow.preview?.bitmap !== bitmap) return
        val revision = flow.cropRevision
        val remaining = flow.cropPaths.drop(flow.cropIndex)
        submitting = true
        imageFailed = false
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { runCatching(encode).getOrNull() }
                if (revision != flow.cropRevision) return@launch
                if (bytes == null || bytes.isEmpty()) { imageFailed = true; return@launch }
                if (all) {
                    val byPath = remaining.zip(bytes).toMap()
                    if (!flow.skipRemainingCrop { byPath[it] }) { imageFailed = true; return@launch }
                } else flow.submitCropResult(bytes.single())
                afterSubmit()
            } finally { submitting = false }
        }
    }

    // 缩放 / 平移（双指捏合调整图片大小；1x~4x）
    var zoom by remember(path, turns) { mutableFloatStateOf(1f) }
    var panX by remember(path, turns) { mutableFloatStateOf(0f) }
    // zoom=1 时的图片显示矩形（按钮里复位缩放用）
    var dispBase by remember(path, turns) { mutableStateOf(Rect.Zero) }
    var panY by remember(path, turns) { mutableFloatStateOf(0f) }

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
                LaunchedEffect(disp, path, turns) {
                    if (disp == Rect.Zero) return@LaunchedEffect
                    selection.bounds = disp
                    if (selection.rect == null) {
                        // 默认框：居中的 70% 子矩形（不占满屏，方便拖动）
                        // ★ 预框选失败/超时/结果可疑时，就保持这个默认框
                        val w = disp.width * 0.7f
                        val h = disp.height * 0.7f
                        selection.rect = Rect(disp.center.x - w / 2, disp.center.y - h / 2,
                            disp.center.x + w / 2, disp.center.y + h / 2)
                    }
                    path?.let { flow.detectCrop(it, disp, autoAiCrop, aiCropMs) }
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
                    val r = selection.rect
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
                        // ★ 键用 path：换图时手势层与保留的选区一起切换，
                        //   避免旧闭包继续写已废弃的 state（否则新图上的框拖不动）。
                        .pointerInput(path, turns, flow.rotating, submitting) {
                            if (flow.rotating || submitting) return@pointerInput
                            awaitEachGesture {
                                // ★ 2026-10-13：**手指一按下就置 region.touched**。
                                //   原来只在 onDragStart/onDrag 置位，而 `detectDragGestures` 的 onDragStart
                                //   要**超过 touch slop** 才回调 → 按下但还没滑动的那段时间里，异步的自动框选结果
                                //   仍会把用户的框覆盖掉（"上边蹦到最上边"的常见来源）。
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                region.touch()
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
                                    val r0 = selection.rect
                                    val sameSide = if (r0 == null) true
                                    else (r0.contains(p0) && r0.contains(p1)) ||
                                        (!r0.contains(p0) && !r0.contains(p1))
                                    // ★ 两指中点必须落在图片内：落在图片外的黑色区域不响应缩放
                                    val mid = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
                                    val dNow = selection.bounds
                                    val onImage = dNow.width <= 1f || dNow.contains(mid)
                                    if (sameSide && onImage && prevDist > 1f && dist > 1f) {
                                        // 单次事件限幅 ±10%，避免异常比值把图瞬间放大/缩小
                                        val ratio = safeCoerceRange(dist / prevDist, 0.9f, 1.1f)
                                        val newZoom = safeCoerceRange(zoom * ratio, 1f, 4f)
                                        if (newZoom != zoom) {
                                            // ★ 以两指中点为中心缩放：中点下方的图像内容保持不动
                                            val oldDisp = selection.bounds
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
                                            val screenRect = selection.rect
                                            selection.bounds = Rect(Offset(nl, nt), Offset(nl + nw, nt + nh))
                                            selection.rect = screenRect?.let { normalizeSelRect(it, selection.bounds) }
                                            zoomed = true
                                            region.touch()
                                        }
                                    }
                                    prevDist = dist
                                    ev.changes.forEach { it.consume() }
                                }
                                // ★ 一次缩放结束后：把越界的红框边收回图片范围内（与 normalizeSelRect 同一套夹取）
                                if (zoomed) normalizeSel()
                            }
                        }
                        // ★ 键用 path（原来是 selection.bounds）：selection.bounds 在缩放时每帧都变 → 键变 → 手柄拖到一半
                        //   手势会被取消重建（"卡住不跟手"）。改用 path 后手势层只在换图时重建，
                        //   拖动逻辑本来就是每帧现读 `selection.bounds`/`selection.rect`，不依赖这个键。
                        .pointerInput(path, turns, flow.rotating, submitting) {
                            if (flow.rotating || submitting) return@pointerInput
                            detectDragGestures(
                        onDragStart = { pos ->
                            region.touch()
                            val r = selection.rect
                            mode = if (r == null) "NONE" else {
                                val d2 = selection.bounds
                                when {
                                    // 四角优先（★ 2026-10-13 补外侧边界：原来只有"内侧"判定，
                                    //   在图片上方黑区甚至屏幕顶端按下也会被判成 TL/TR → 上边被拖到图片顶）
                                    d2 != Rect.Zero && pos.x in (r.left - CORNER)..(r.left + CORNER) && pos.y in (r.top - CORNER)..(r.top + CORNER) -> "TL"
                                    d2 != Rect.Zero && pos.x in (r.right - CORNER)..(r.right + CORNER) && pos.y in (r.top - CORNER)..(r.top + CORNER) -> "TR"
                                    d2 != Rect.Zero && pos.x in (r.left - CORNER)..(r.left + CORNER) && pos.y in (r.bottom - CORNER)..(r.bottom + CORNER) -> "BL"
                                    d2 != Rect.Zero && pos.x in (r.right - CORNER)..(r.right + CORNER) && pos.y in (r.bottom - CORNER)..(r.bottom + CORNER) -> "BR"
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
                            region.touch()
                            val r = selection.rect
                            val d2 = selection.bounds
                            if (r != null && d2 != Rect.Zero) {
                                val n = when (mode) {
                                    // ★ 2026-10-13：每个夹取区间的**两个界都先夹成合法**（coerceAtLeast/coerceAtMost），
                                    //   配合 safeCoerceRange 的"非法返回原值"双保险 —— 再不会把边"跳"到图片外。
                                    "TOP" -> Rect(r.left,
                                        safeCoerceRange(r.top + drag.y, d2.top, (r.bottom - MIN_SIZE).coerceAtLeast(d2.top)),
                                        r.right, r.bottom)
                                    "BOTTOM" -> Rect(r.left, r.top, r.right,
                                        safeCoerceRange(r.bottom + drag.y, (r.top + MIN_SIZE).coerceAtMost(d2.bottom), d2.bottom))
                                    "LEFT" -> Rect(
                                        safeCoerceRange(r.left + drag.x, d2.left, (r.right - MIN_SIZE).coerceAtLeast(d2.left)),
                                        r.top, r.right, r.bottom)
                                    "RIGHT" -> Rect(r.left, r.top,
                                        safeCoerceRange(r.right + drag.x, (r.left + MIN_SIZE).coerceAtMost(d2.right), d2.right),
                                        r.bottom)
                                    // 四角：同时改长宽
                                    "TL" -> Rect(
                                        safeCoerceRange(r.left + drag.x, d2.left, (r.right - MIN_SIZE).coerceAtLeast(d2.left)),
                                        safeCoerceRange(r.top + drag.y, d2.top, (r.bottom - MIN_SIZE).coerceAtLeast(d2.top)),
                                        r.right, r.bottom)
                                    "TR" -> Rect(r.left,
                                        safeCoerceRange(r.top + drag.y, d2.top, (r.bottom - MIN_SIZE).coerceAtLeast(d2.top)),
                                        safeCoerceRange(r.right + drag.x, (r.left + MIN_SIZE).coerceAtMost(d2.right), d2.right),
                                        r.bottom)
                                    "BL" -> Rect(
                                        safeCoerceRange(r.left + drag.x, d2.left, (r.right - MIN_SIZE).coerceAtLeast(d2.left)),
                                        r.top, r.right,
                                        safeCoerceRange(r.bottom + drag.y, (r.top + MIN_SIZE).coerceAtMost(d2.bottom), d2.bottom))
                                    "BR" -> Rect(r.left, r.top,
                                        safeCoerceRange(r.right + drag.x, (r.left + MIN_SIZE).coerceAtMost(d2.right), d2.right),
                                        safeCoerceRange(r.bottom + drag.y, (r.top + MIN_SIZE).coerceAtMost(d2.bottom), d2.bottom))
                                    "MOVE" -> {
                                        val dt = safeCoerceRange(r.left + drag.x, d2.left, (d2.right - r.width).coerceAtLeast(d2.left))
                                        val dtp = safeCoerceRange(r.top + drag.y, d2.top, (d2.bottom - r.height).coerceAtLeast(d2.top))
                                        Rect(dt, dtp, dt + r.width, dtp + r.height)
                                    }
                                    else -> null
                                }
                                if (n != null) selection.rect = n
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
            if (attachment || flow.cropExpect > 1) {
                Text(
                    s.format(if (attachment) "crop.batchPos" else "crop.multiPos", "i" to "${flow.cropPos}", "n" to "${flow.cropExpect}"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    textAlign = TextAlign.Center
                )
            }
            Button(
                enabled = !submitting && !flow.rotating,
                onClick = {
                    val r = selection.rect ?: selection.bounds
                    if (selection.bounds != Rect.Zero) {
                        val d2 = selection.bounds
                        // 裁剪区域：全部用 safeCoerceRange（原来 1..bitmap.width-x 在贴边时会出现 1 > max → 崩溃）
                        val x = safeCoerceRange((r.left - d2.left) / d2.width * bitmap.width, 0f, (bitmap.width - 1).toFloat()).toInt()
                        val y = safeCoerceRange((r.top - d2.top) / d2.height * bitmap.height, 0f, (bitmap.height - 1).toFloat()).toInt()
                        val w = safeCoerceRange((r.width / d2.width) * bitmap.width, 1f, (bitmap.width - x).toFloat()).toInt()
                        val h = safeCoerceRange((r.height / d2.height) * bitmap.height, 1f, (bitmap.height - y).toFloat()).toInt()
                        submitImages(false) {
                            val cropped = Bitmap.createBitmap(bitmap, x, y, w, h)
                            try { listOf(compressBitmap(cropped)) }
                            finally { if (cropped !== bitmap) cropped.recycle() }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(s[when {
                    flow.cropIndex + 1 < flow.cropExpect -> "crop.confirmMore"
                    attachment -> "crop.confirmAdd"
                    flow.cropGrade -> "crop.confirmGrade"
                    else -> "crop.confirm"
                }])
            }
            Spacer(Modifier.height(8.dp))
            // 「整张图片」：单击 = 跳过这一张（多张时进入下一张）；**按住 1 秒填满 → 松开 = 剩下全部跳过**
            // 没填满松手 = 取消；填满后上滑（填充变浅红）= 取消
            HoldToSkipButton(
                enabled = !submitting && !flow.rotating,
                text = s["crop.whole"],
                holdHint = if (flow.cropExpect > 1) s["crop.wholeHoldHint"] else "",
                holdEnabled = flow.cropExpect > 1,
                onTap = {
                    submitImages(false) { listOf(compressBitmap(bitmap)) }
                },
                onSkip = {
                    val remaining = flow.cropPaths.drop(flow.cropIndex).map { it to flow.cropRotation(it) }
                    submitImages(true) { remaining.map { (source, rotation) ->
                        if (source == path) compressBitmap(bitmap) else CaptureImage.wholeJpeg(source, rotation)
                    } }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            )
            if (attachment) Text(s["crop.attachmentHint"], style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            if (imageFailed) Text(s["crop.noImage"], color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // Manual detection uses the same deadline and retains the current selection on failure.
                TextButton(
                    enabled = !aiBusy && !submitting && !flow.rotating,
                    onClick = {
                        val p = path ?: return@TextButton
                        feedback.dismiss("cropAi")
                        zoom = 1f
                        panX = 0f
                        panY = 0f
                        if (dispBase.width > 1f) selection.bounds = dispBase
                        flow.detectCrop(p, selection.bounds, true, aiCropMs, manual = true)
                    }
                ) { AppIconLabel(if (aiBusy) s["crop.aiBusy"] else s["crop.aiAutoCrop"], iconSize = 16.dp, iconSpacing = 4.dp, maxLines = 1, softWrap = false) }
                TextButton(
                    enabled = !submitting && !flow.rotating,
                    onClick = {
                        scope.launch {
                            try { flow.rotateCurrent(requireNotNull(path), bitmap) }
                            catch (error: kotlinx.coroutines.CancellationException) { throw error }
                            catch (_: Exception) { feedback.show(s["crop.rotateFailed"], isError = true) }
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = s["crop.rotate"] }
                ) { Text("↻ 90°", maxLines = 1, softWrap = false) }
                // Returning from an attachment batch preserves the original input.
                TextButton(onClick = { handleBack() }) {
                    Text(if (flow.cropIndex > 0) s["crop.prevImage"] else s[if (attachment) "crop.backToInput" else "crop.backToCamera"], maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

private fun compressBitmap(bmp: Bitmap, quality: Int = 85): ByteArray {
    val out = ByteArrayOutputStream()
    check(bmp.compress(Bitmap.CompressFormat.JPEG, quality, out))
    return out.toByteArray()
}

/**
 * 「整张图片」按钮（跳过框选）：
 *  · **按住 1 秒**：紫色进度从左往右填满 → **松开才执行跳过**
 *  · 没填满就松手 → 取消（不跳过）
 *  · 填满后**上滑** → 取消跳过
 */
@Composable
internal fun HoldToSkipButton(
    text: String,
    holdHint: String,
    onTap: () -> Unit,
    onSkip: () -> Unit,
    holdEnabled: Boolean = true,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    holdMs: Long = 1000L
) {
    val haptics = LocalHapticFeedback.current
    val latestEnabled by rememberUpdatedState(enabled)
    val latestHaptics by rememberUpdatedState(haptics)
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnSkip by rememberUpdatedState(onSkip)
    val latestHoldEnabled by rememberUpdatedState(holdEnabled)
    val latestHoldMs by rememberUpdatedState(holdMs)
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
                    if (!latestEnabled) return@awaitEachGesture
                    armed = false
                    val startY = down.position.y
                    val slideUpPx = 36.dp.toPx()
                    // 单张模式不需要长按：holdEnabled=false 时完全不填充，只保留单击
                    val job = scope.launch {
                        if (!latestHoldEnabled) return@launch
                        val start = android.os.SystemClock.uptimeMillis()
                        while (true) {
                            val p = ((android.os.SystemClock.uptimeMillis() - start).toFloat() / latestHoldMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
                            progress = p
                            if (p >= 1f) {
                                armed = true
                                latestHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                break // 填满后振动一次，仍等待松手，并保留上滑取消。
                            }
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
                    if (latestEnabled) { if (doSkip) latestOnSkip() else if (doTap) latestOnTap() }
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
