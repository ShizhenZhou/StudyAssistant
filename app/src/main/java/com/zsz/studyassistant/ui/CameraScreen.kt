package com.zsz.studyassistant.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.FrameLuma
import com.zsz.studyassistant.data.StudyAssistant
import com.zsz.studyassistant.data.autoExposureDeltaEv
import com.zsz.studyassistant.data.clampAutoEv
import com.zsz.studyassistant.data.lumaStats
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

@Composable
fun CameraScreen(nav: NavHostController, vm: MainViewModel) {
    val context = LocalContext.current
    val s = LocalStrings.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    if (!hasPermission) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(s["camera.permNeeded"])
            Spacer(Modifier.height(16.dp))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text(s["camera.grantPerm"]) }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { nav.popBackStack() }) { Text(s["common.back"]) }
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        val previewView = remember { PreviewView(context) }
        val imageCapture = remember { ImageCapture.Builder().build() }
        val lifecycleOwner = LocalLifecycleOwner.current
        var camera by remember { mutableStateOf<Camera?>(null) }
        val scope = rememberCoroutineScope()
        // 预览帧亮度统计（单击对焦后用它自动调曝光）：分析在单线程 executor 上跑，避免占用主线程
        val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
        // 最新一帧的亮度（分析线程写、主线程读）
        val latestLuma = remember { AtomicReference(FrameLuma(128.0, 0.0)) }
        var autoEvJob by remember { mutableStateOf<Job?>(null) }
        DisposableEffect(Unit) {
            onDispose {
                autoEvJob?.cancel()
                analysisExecutor.shutdown()
            }
        }

        LaunchedEffect(lifecycleOwner) {
            val provider = awaitCameraProvider(context)
            provider.unbindAll()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            // ★ 亮度分析用例：只为「单击后自动调曝光」服务（每帧只扫 Y 平面的稀疏采样，开销很小）
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor) { proxy -> analyzeLuma(proxy, latestLuma) } }
            camera = runCatching {
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
            }.getOrElse {
                // 某些设备不支持"预览 + 拍照 + 分析"三路同时 → 回退到原来的两路（只是没有自动曝光）
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            }
        }

        // 点击对焦：tap 位置显示对焦框并触发 AF
        var focusPoint by remember { mutableStateOf<Offset?>(null) }
        var focusDone by remember { mutableStateOf(false) }

        // 单张 / 两张拍摄模式（两张：拍两张作为同一道题一起解答）
        // 记住上次的选择：下次进本页直接沿用（存 settings SharedPreferences）
        var doubleMode by remember { mutableStateOf(com.zsz.studyassistant.data.CapturePrefs.solveDouble(context)) }
        // 右上角开关：开 = 批改模式（拍题目 [+ 我的作答] → 批改结果），关 = 正常拍照搜题；状态同样记忆
        var gradeMode by remember { mutableStateOf(com.zsz.studyassistant.data.CapturePrefs.gradeToggle(context)) }
        var firstBytes by remember { mutableStateOf<ByteArray?>(null) }
        var awaitingSecond by remember { mutableStateOf(false) }

        // 拍照 / 选图统一进**框选流程**：
        //  · 单张模式 → 1 张进框选
        //  · 两张模式 → 第 1 张进框选；框完回本页收第 2 张（同样进框选）
        //  · 图库一次选两张 → 两张都进框选（见 galleryMulti）
        fun processImageFile(file: File) {
            val path = file.absolutePath
            if (vm.cropNeedsMore) {
                vm.appendCropPath(path)
                AppNav.openCrop(nav)
                return
            }
            vm.startCropFlow(
                paths = listOf(path),
                grade = gradeMode,
                expect = if (doubleMode) 2 else 1
            )
            AppNav.openCrop(nav)
        }

        AndroidView(
            { previewView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val cam = camera ?: return@detectTapGestures
                        val pt = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                        // ★ 单击 = 对焦 + **曝光**（用户 2026-10-11 要求）。
                        //   显式带上 AE（测光/曝光）与 AWB（白平衡），测光区域就是点击点 ——
                        //   拍电脑屏幕时点一下亮屏区域，相机就按那里测光，不容易把白底拍成一片死白。
                        //   （默认 Builder(point) 虽然也含 3A，但显式写出来更明确，也不受 CameraX 版本默认值影响）
                        runCatching {
                            cam.cameraControl.startFocusAndMetering(
                                FocusMeteringAction.Builder(
                                    pt,
                                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
                                ).build()
                            )
                        }
                        focusPoint = offset
                        focusDone = false
                        // ★ 再按预览帧的实际亮度微调**曝光补偿**（过曝往下压、太暗往上抬），约 1.2 秒收敛
                        autoEvJob?.cancel()
                        autoEvJob = scope.launch { autoTuneExposure(cam, latestLuma) }
                    }
                }
        )

        LaunchedEffect(focusPoint) {
            if (focusPoint != null) {
                delay(700)
                focusDone = true
                delay(900)
                focusPoint = null
                focusDone = false
            }
        }
        focusPoint?.let { fp ->
            FocusRing(center = fp, focused = focusDone)
        }

        // 系统相册（开启**有序选择**）：单张 → 进框选页；两张模式 → 第 1 张=题目、第 2 张=作答
        val gallerySingle = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                uriToTempFile(context, uri, "gallery")?.let { processImageFile(it) }
                    ?: vm.showError(s["camera.errRead"])
            }
        }
        val galleryMulti = rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(2)
        ) { uris ->
            val files = uris.take(2).mapNotNull { uriToTempFile(context, it, "gallery") }
            if (files.isEmpty()) return@rememberLauncherForActivityResult
            // 已经在等第二张 → 只补一张（保留第一张的框选结果，别重置队列）
            if (vm.cropNeedsMore) {
                vm.appendCropPath(files.first().absolutePath)
            } else {
                // 一次选两张 → 两张都进同一个框选队列（先框题目，再框作答）
                // 只选一张 → 也进框选；若还差一张（两张模式）框完会回到拍摄界面继续选
                vm.startCropFlow(
                    paths = files.map { it.absolutePath },
                    grade = gradeMode,
                    expect = if (doubleMode) 2 else files.size
                )
            }
            AppNav.openCrop(nav)
        }

        // 从图库选图（左下角，圆角正方形 + 花瓣图标）
        Surface(
            onClick = {
                if (doubleMode) galleryMulti.launch(imagePickRequest(maxItems = 2))
                else gallerySingle.launch(imagePickRequest())
            },
            enabled = !vm.busy,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 20.dp, bottom = 28.dp)
                .size(60.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                FlowerIcon(size = 34.dp)
            }
        }

        // 拍照解题（底部居中，圆形快门按钮）
        Surface(
            onClick = {
                val file = File.createTempFile("capture", ".jpg", context.cacheDir)
                val options = ImageCapture.OutputFileOptions.Builder(file).build()
                imageCapture.takePicture(
                    options,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            processImageFile(file)
                        }

                        override fun onError(e: ImageCaptureException) {
                            vm.showError(s.format("camera.errShot", "msg" to (e.message ?: "")))
                        }
                    }
                )
            },
            enabled = !vm.busy,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(20.dp)
                .size(76.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ShutterIcon(size = 40.dp, color = Color.White)
            }
        }

        // 右下角：单张/两张 切换（与图库、快门在同一水平线）
        Surface(
            onClick = {
                doubleMode = !doubleMode
                com.zsz.studyassistant.data.CapturePrefs.setSolveDouble(context, doubleMode)
                // 切换模式时清掉未完成的框选队列，避免"模式与队列不一致"
                vm.cancelCropFlow()
                firstBytes = null; awaitingSecond = false
            },
            enabled = !vm.busy,
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 36.dp)
                .height(44.dp)
        ) {
            Box(Modifier.fillMaxHeight().padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                Text(if (doubleMode) s["camera.mode.two"] else s["camera.mode.one"], style = MaterialTheme.typography.labelLarge)
            }
        }

        // 两张模式提示；批改模式下改用批改文案（第 1 张题目、第 2 张我的作答）
        if (doubleMode) {
            Text(
                if (vm.cropNeedsMore || awaitingSecond) {
                    if (gradeMode) s["grade.hint.answer"] else s["camera.hint.twoSecond"]
                } else {
                    if (gradeMode) s["grade.hint.twoFirst"] else s["camera.hint.twoFirst"]
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
        }

        // 返回箭头：全应用统一样式（见 SubPageBackArrow）
        SubPageBackArrow(
            onClick = { nav.popBackStack() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        )

        // 右上角：批改开关（开 = 批改模式，关 = 正常拍照搜题）；状态记忆，下次进页面沿用
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(8.dp)
        ) {
            Text(
                s["camera.gradeToggle"],
                fontSize = BTN_LABEL,
                color = if (gradeMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Switch(
                checked = gradeMode,
                onCheckedChange = {
                    gradeMode = it
                    com.zsz.studyassistant.data.CapturePrefs.setGradeToggle(context, it)
                    firstBytes = null
                    awaitingSecond = false
                }
            )
        }
    }
}

private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            { cont.resume(future.get()) },
            ContextCompat.getMainExecutor(context)
        )
    }

/**
 * 一帧 YUV 图 → 亮度统计（只扫 Y 平面，稀疏采样；算完**立刻 close**，不能堵住相机管线）。
 * 统计失败就保持上一帧的值 —— 自动曝光只是锦上添花，绝不能影响拍照。
 */
private fun analyzeLuma(proxy: ImageProxy, out: AtomicReference<FrameLuma>) {
    try {
        val plane = proxy.planes.firstOrNull() ?: return
        val buf = plane.buffer
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        out.set(
            lumaStats(
                y = bytes,
                width = proxy.width,
                height = proxy.height,
                rowStride = plane.rowStride,
                step = 8
            )
        )
    } catch (_: Exception) {
        // 忽略：保留上一帧统计值
    } finally {
        proxy.close()
    }
}

/**
 * 单击对焦之后，用预览帧亮度把**曝光补偿**收敛到合适区间（最多 ~1.2 秒）。
 *
 * 规则见 `data/AutoExposure.kt`：近白像素多/整体偏亮 → 往下压 EV；太暗 → 往上抬；已在合适区间就不动。
 * 每一步都夹在设备支持范围与自动调整范围（-2EV ~ +2EV）内，避免越调越偏。
 */
private suspend fun autoTuneExposure(camera: Camera, latest: AtomicReference<FrameLuma>) {
    val state = camera.cameraInfo.exposureState
    if (!state.isExposureCompensationSupported) return
    val stepEv = state.exposureCompensationStep.toDouble()
    if (stepEv <= 0.0) return
    val minEv = state.exposureCompensationRange.lower * stepEv
    val maxEv = state.exposureCompensationRange.upper * stepEv
    var currentEv = state.exposureCompensationIndex * stepEv
    repeat(6) {
        delay(200)   // 给 AE 一点收敛时间，同时等一帧新画面
        val luma = latest.get()
        val delta = autoExposureDeltaEv(luma.mean, luma.clipRatio)
        if (delta == 0.0) return           // 已经在合适区间 → 收工，避免来回抖
        val nextEv = clampAutoEv(currentEv, delta, minEv, maxEv)
        if (nextEv == currentEv) return    // 已经到限位（设备范围或 ±2EV）→ 收工
        currentEv = nextEv
        val idx = (currentEv / stepEv).roundToInt()
            .coerceIn(state.exposureCompensationRange.lower, state.exposureCompensationRange.upper)
        runCatching { camera.cameraControl.setExposureCompensationIndex(idx) }
    }
}

/** 点击对焦框：出现 → 转绿(对焦完成) → 淡出
 *  ★ 尺寸用 **dp** 换算（2026-10-11 用户反馈"对焦框太小"）：原来直接写 42f 像素，
 *    在 3x 屏上只有 14dp，显得很小；现在整框 ~64dp、角长 16dp、线宽 3dp。 */
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
