package com.zsz.studyassistant.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
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

    Box(Modifier.fillMaxSize()) {
        val previewView = remember { PreviewView(context) }
        val imageCapture = remember { ImageCapture.Builder().build() }
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(lifecycleOwner) {
            val p = awaitCameraProvider(context)
            p.unbindAll()
            p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }, imageCapture)
        }
        AndroidView({ previewView }, Modifier.fillMaxSize())

        Text(
            if (awaitingAnswer) "请拍摄手写答案" else (if (doubleMode) "两张模式：先拍题目" else "单张模式：拍题目"),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
            style = MaterialTheme.typography.bodyMedium
        )

        // 底部居中快门
        Surface(
            onClick = {
                val file = File.createTempFile("grade", ".jpg", context.cacheDir)
                imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(o: ImageCapture.OutputFileResults) {
                            val bytes = StudyAssistant.compressImage(file)
                            if (doubleMode && questionBytes == null) {
                                questionBytes = bytes
                                awaitingAnswer = true
                            } else {
                                val ans = if (doubleMode) bytes else null
                                vm.grade(questionBytes ?: bytes, ans)
                                awaitingAnswer = false
                            }
                        }
                        override fun onError(e: ImageCaptureException) { vm.showError("拍照失败：${e.message}") }
                    })
            },
            enabled = !vm.gradeBusy,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp).size(76.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("📷", style = MaterialTheme.typography.headlineMedium) }
        }

        // 右下角单张/两张切换
        TextButton(
            onClick = { doubleMode = !doubleMode; questionBytes = null; awaitingAnswer = false },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)
        ) { Text(if (doubleMode) "两张拍摄" else "单张拍摄") }

        TextButton(onClick = { nav.popBackStack() }, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) { Text("← 返回") }

        // 批改结果
        if (vm.gradeBusy || vm.gradeResult.isNotBlank()) {
            Card(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).fillMaxWidth().size(430.dp)) {
                Column(Modifier.padding(12.dp).fillMaxSize()) {
                    if (vm.gradeBusy) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("批改中……") }
                    } else {
                        ConversationWebView(listOf(ChatMsg("assistant", vm.gradeResult)), Modifier.weight(1f).fillMaxWidth())
                        TextButton(onClick = { vm.clearGradeResult() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("重拍") }
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
