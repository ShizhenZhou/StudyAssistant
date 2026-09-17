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
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.StudyAssistant
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
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

        LaunchedEffect(lifecycleOwner) {
            val provider = awaitCameraProvider(context)
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) },
                imageCapture
            )
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

        fun processImageFile(file: File) {
            val bytes = com.zsz.studyassistant.data.StudyAssistant.compressImage(file)
            if (gradeMode) {
                // 批改模式：单张=只拍题目；两张=第 1 张题目、第 2 张我的作答 → 进入全屏批改页
                val first = firstBytes
                if (doubleMode && first == null) {
                    firstBytes = bytes
                    awaitingSecond = true
                } else {
                    val q = if (doubleMode) first ?: bytes else bytes
                    val ans = if (doubleMode) bytes else null
                    vm.startGrade(q, ans)
                    nav.navigate("solve") { popUpTo("camera") { inclusive = true } }
                }
                return
            }
            if (!doubleMode) {
                // 单张：进入框选页挑选题目区域
                vm.updatePendingImagePath(file.absolutePath)
                nav.navigate("crop") { popUpTo("camera") { inclusive = true } }
            } else {
                val first = firstBytes
                if (first == null) {
                    firstBytes = bytes
                    awaitingSecond = true
                } else {
                    vm.solveWithImages(listOf(first, bytes))
                    nav.navigate("solve") { popUpTo("camera") { inclusive = true } }
                }
            }
        }

        AndroidView(
            { previewView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val pt = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                        camera?.cameraControl?.startFocusAndMetering(
                            FocusMeteringAction.Builder(pt).build()
                        )
                        focusPoint = offset
                        focusDone = false
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
            files.take(2).forEach { processImageFile(it) }   // 依次处理：第 1 张先存为题目，第 2 张触发解答
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
                if (awaitingSecond) {
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

        TextButton(
            onClick = { nav.popBackStack() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        ) { Text(s["common.backArrow"]) }

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
                fontSize = 13.sp,
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

/** 点击对焦框：出现 → 转绿(对焦完成) → 淡出 */
@Composable
internal fun FocusRing(center: Offset, focused: Boolean) {
    val scale by animateFloatAsState(targetValue = if (focused) 1f else 0.7f, label = "focusScale")
    val alpha by animateFloatAsState(targetValue = if (focused) 1f else 0.55f, label = "focusAlpha")
    Canvas(Modifier.fillMaxSize()) {
        val c = center
        val half = 42f * scale
        val le = 22f * scale   // 角长
        val th = 5f            // 线宽
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
        drawCircle(color.copy(alpha = alpha * 0.8f), radius = 3f, center = c)
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
