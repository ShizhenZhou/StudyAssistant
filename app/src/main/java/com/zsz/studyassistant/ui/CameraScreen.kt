package com.zsz.studyassistant.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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

@Composable
fun CameraScreen(nav: NavHostController, vm: MainViewModel) {
    val context = LocalContext.current

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
            Text("需要相机权限才能拍照搜题")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("授予相机权限") }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { nav.popBackStack() }) { Text("返回") }
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        val previewView = remember { PreviewView(context) }
        val imageCapture = remember { ImageCapture.Builder().build() }
        val lifecycleOwner = LocalLifecycleOwner.current

        LaunchedEffect(lifecycleOwner) {
            val provider = awaitCameraProvider(context)
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) },
                imageCapture
            )
        }

        AndroidView({ previewView }, modifier = Modifier.fillMaxSize())

        // 从相册选图 → 转存临时文件 → 进入框选页
        val galleryLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                try {
                    val file = File.createTempFile("gallery", ".jpg", context.cacheDir)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    vm.updatePendingImagePath(file.absolutePath)
                    nav.navigate("crop") { popUpTo("camera") { inclusive = true } }
                } catch (e: Exception) {
                    vm.showError("读取图片失败：${e.message}")
                }
            }
        }

        // 从图库选图（左下角，圆角正方形 + 花瓣图标）
        Surface(
            onClick = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
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
                            // 先进入框选页挑选题目区域
                            vm.updatePendingImagePath(file.absolutePath)
                            nav.navigate("crop") { popUpTo("camera") { inclusive = true } }
                        }

                        override fun onError(e: ImageCaptureException) {
                            vm.showError("拍照失败：${e.message}")
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

        TextButton(
            onClick = { nav.popBackStack() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        ) { Text("← 返回") }
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

/** 快门图标：外环 + 中心（相机光圈） */
@Composable
private fun ShutterIcon(size: androidx.compose.ui.unit.Dp, color: Color) {
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
