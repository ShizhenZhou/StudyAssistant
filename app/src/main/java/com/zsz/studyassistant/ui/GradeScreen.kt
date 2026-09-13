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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.StudyAssistant
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/** 批改题目：拍照（单张=题目；两张=题目+手写答案）→ AI 批改正误/错误步骤/针对性讲解 */
@Composable
fun GradeScreen(nav: NavHostController, vm: MainViewModel) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }

    if (!hasPermission) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("需要相机权限才能批改")
            Spacer(Modifier.padding(8.dp))
            Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) { Text("授予相机权限") }
            TextButton(onClick = { nav.popBackStack() }) { Text("返回") }
        }
        return
    }

    var doubleMode by remember { mutableStateOf(false) }
    var questionBytes by remember { mutableStateOf<ByteArray?>(null) }
    var awaitingAnswer by remember { mutableStateOf(false) }
    // 最近一次批改实际使用的题目/作答图（用于结果区展示，与批改请求保持一致）
    var shownQuestion by remember { mutableStateOf<ByteArray?>(null) }
    var shownAnswer by remember { mutableStateOf<ByteArray?>(null) }

    Box(Modifier.fillMaxSize()) {
        val previewView = remember { PreviewView(context) }
        val imageCapture = remember { ImageCapture.Builder().build() }
        val lifecycleOwner = LocalLifecycleOwner.current
        var camera by remember { mutableStateOf<Camera?>(null) }
        LaunchedEffect(lifecycleOwner) {
            val p = awaitCameraProvider(context)
            p.unbindAll()
            camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }, imageCapture)
        }

        // 点击对焦：tap 位置显示对焦框并触发 AF
        var focusPoint by remember { mutableStateOf<Offset?>(null) }
        var focusDone by remember { mutableStateOf(false) }

        AndroidView(
            { previewView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val pt = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
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
        focusPoint?.let { fp -> FocusRing(center = fp, focused = focusDone) }

        // 统一处理一张图片：单张→直接批改；两张→先存题目，再拍/选答案后批改
        val processImage: (ByteArray) -> Unit = { bytes ->
            if (doubleMode && questionBytes == null) {
                questionBytes = bytes
                awaitingAnswer = true
            } else {
                val q = questionBytes ?: bytes
                val ans = if (doubleMode) bytes else null
                shownQuestion = q
                shownAnswer = ans
                vm.grade(q, ans)
                awaitingAnswer = false
            }
        }

        // 从相册选图 → 转存临时文件 → 与拍照同流程
        val galleryLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                try {
                    val file = File.createTempFile("gradeGallery", ".jpg", context.cacheDir)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    processImage(StudyAssistant.compressImage(file))
                } catch (e: Exception) {
                    vm.showError("读取图片失败：${e.message}")
                }
            }
        }

        Text(
            if (awaitingAnswer) "请拍摄手写答案" else (if (doubleMode) "两张模式：先拍题目" else "单张模式：拍题目"),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
            style = MaterialTheme.typography.bodyMedium
        )

        // 图库按钮（左下，与拍题模式一致）
        Surface(
            onClick = {
                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            enabled = !vm.gradeBusy,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 20.dp, bottom = 28.dp).size(60.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { FlowerIcon(size = 34.dp) }
        }

        // 快门（严格横向居中，拍题页同款）
        Surface(
            onClick = {
                val file = File.createTempFile("grade", ".jpg", context.cacheDir)
                imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(o: ImageCapture.OutputFileResults) {
                            processImage(StudyAssistant.compressImage(file))
                        }
                        override fun onError(e: ImageCaptureException) { vm.showError("拍照失败：${e.message}") }
                    })
            },
            enabled = !vm.gradeBusy,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp).size(76.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ShutterIcon(size = 40.dp, color = Color.White) }
        }

        // 单张/两张切换（右下，正常大小）
        Surface(
            onClick = { doubleMode = !doubleMode; questionBytes = null; awaitingAnswer = false; vm.clearGradeResult(); shownQuestion = null; shownAnswer = null },
            enabled = !vm.gradeBusy,
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 36.dp).height(44.dp)
        ) {
            // 宽度随文字自适应（fillMaxSize 会撑满整屏，勿用）
            Box(Modifier.fillMaxHeight().padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                Text(if (doubleMode) "两张" else "单张", style = MaterialTheme.typography.labelLarge)
            }
        }

        TextButton(onClick = { nav.popBackStack() }, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) { Text("← 返回") }

        // 批改结果
        if (vm.gradeBusy || vm.gradeResult.isNotBlank()) {
            Card(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).fillMaxWidth().size(430.dp)) {
                Column(Modifier.padding(12.dp).fillMaxSize()) {
                    if (vm.gradeBusy) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("批改中……") }
                    } else {
                        // 我的题目/作答图（浅绿用户气泡）+ 批改结果，保持一致
                        val msgs = buildList<ChatMsg> {
                            shownQuestion?.let { b ->
                                add(ChatMsg("user", "题目", listOf(android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP))))
                            }
                            shownAnswer?.let { b ->
                                add(ChatMsg("user", "我的作答", listOf(android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP))))
                            }
                            add(ChatMsg("assistant", vm.gradeResult))
                        }
                        ConversationWebView(msgs, Modifier.weight(1f).fillMaxWidth())
                        TextButton(
                            onClick = {
                                vm.clearGradeResult()
                                questionBytes = null
                                shownQuestion = null
                                shownAnswer = null
                                awaitingAnswer = false
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) { Text("重拍") }
                    }
                }
            }
        }
    }
}

private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
    }
