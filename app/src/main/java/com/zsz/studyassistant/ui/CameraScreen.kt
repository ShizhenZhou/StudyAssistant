package com.zsz.studyassistant.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.CaptureImage
import com.zsz.studyassistant.data.CapturePrefs
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Owns only the temporary output; capture UI belongs to the installed system camera. */
@Composable
fun CameraScreen(nav: NavHostController, vm: MainViewModel) {
    val context = LocalContext.current
    val s = LocalStrings.current
    val ownerEntry = remember(nav) { nav.currentBackStackEntry }
    // The nav entry's saved state and ActivityResult registry survive camera-driven recreation.
    var started by rememberSaveable { mutableStateOf(false) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var readyPath by rememberSaveable { mutableStateOf<String?>(null) }
    var waitingPermission by rememberSaveable { mutableStateOf(false) }
    var pendingFlow by rememberSaveable { mutableStateOf("") }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var restart by remember { mutableStateOf(false) }
    val grade = if (vm.cropPaths.isNotEmpty()) vm.cropGrade else CapturePrefs.gradeToggle(context)
    val expect = if (vm.cropPaths.isNotEmpty()) vm.cropExpect else if (CapturePrefs.solveDouble(context)) 2 else 1
    val inFlight = pendingPath != null || waitingPermission || readyPath != null

    fun flowKey() = vm.cameraRequestToken
    fun releaseOutput(path: String, delete: Boolean) {
        val file = systemCameraFile(context, path) ?: return
        runCatching { context.revokeUriPermission(FileProvider.getUriForFile(context,
            "${context.packageName}.fileprovider", file), Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        if (delete) file.delete()
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingPath
        if (path != null) {
            if (ok) readyPath = path
            else {
                releaseOutput(path, delete = true)
                pendingPath = null
                notice = "camera.system.cancelled"
            }
        }
    }
    fun launchCamera() {
        if (pendingPath != null || readyPath != null) return
        notice = null
        var output: File? = null
        try {
            val dir = File(context.cacheDir, "system-camera").apply { check(isDirectory || mkdirs()) }
            output = File.createTempFile("capture-", ".jpg", dir)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", output)
            pendingFlow = flowKey()
            pendingPath = output.absolutePath
            camera.launch(uri)
        } catch (_: Exception) {
            output?.let { releaseOutput(it.absolutePath, delete = true) }
            pendingPath = null
            notice = "camera.system.unavailable"
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        waitingPermission = false
        if (granted) launchCamera() else notice = "camera.permNeeded"
    }
    fun takePicture() {
        if (inFlight) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
        else {
            waitingPermission = true
            try { permission.launch(Manifest.permission.CAMERA) }
            catch (_: Exception) { waitingPermission = false; notice = "camera.system.unavailable" }
        }
    }
    LaunchedEffect(Unit) {
        if (!started) { started = true; takePicture() }
    }
    LaunchedEffect(readyPath) {
        val path = readyPath ?: return@LaunchedEffect
        val valid = withContext(Dispatchers.IO) {
            val file = systemCameraFile(context, path)
            file != null && file.length() in 1..100_000_000L && runCatching {
                CaptureImage.decode(file.absolutePath, maxDim = 64).recycle()
            }.isSuccess
        }
        if (pendingPath != path) return@LaunchedEffect
        readyPath = null
        pendingPath = null
        if (!valid || pendingFlow != flowKey() || ownerEntry == null ||
            nav.currentBackStackEntry !== ownerEntry || nav.currentDestination?.route != AppNav.CAMERA) {
            releaseOutput(path, delete = true)
            notice = "camera.errRead"
            return@LaunchedEffect
        }
        releaseOutput(path, delete = false)
        if (vm.cropNeedsMore) vm.appendCropPath(path)
        else vm.startCropFlow(listOf(path), grade = grade, expect = expect)
        AppNav.openCrop(nav)
    }
    fun back() {
        if (inFlight) return
        if (vm.cropNeedsMore) {
            vm.cropGoBackOne() // revisit the previous crop without discarding its source image
            AppNav.openCrop(nav)
        } else {
            if (!nav.popBackStack()) AppNav.switchTab(nav, AppNav.HOME)
        }
    }
    BackHandler { back() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(s[if (grade) "home.camera.grade" else "home.camera"], style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))
        if (expect > 1) {
            CaptureProgress(step = if (vm.cropNeedsMore) 2 else 1,
                hint = s[if (vm.cropNeedsMore) { if (grade) "grade.hint.answer" else "camera.hint.twoSecond" }
                    else { if (grade) "grade.hint.twoFirst" else "camera.hint.twoFirst" }],
                firstPath = vm.cropPaths.firstOrNull().takeIf { vm.cropNeedsMore },
                enabled = !inFlight, onRestart = { restart = true })
            Spacer(Modifier.height(20.dp))
        }
        Text(s[notice ?: "camera.system.hint"])
        Spacer(Modifier.height(16.dp))
        Button(onClick = { takePicture() }, enabled = !inFlight) { Text(s["camera.system.retry"]) }
        TextButton(onClick = { back() }, enabled = !inFlight) { Text(s["common.back"]) }
    }
    if (restart) AlertDialog(onDismissRequest = { restart = false },
        title = { Text(s["camera.restartTitle"]) }, text = { Text(s["camera.restartMessage"]) },
        confirmButton = { TextButton(onClick = {
            restart = false
            vm.cancelCropFlow()
            // Keep the mode for this explicit retry, even if preferences have since changed.
            CapturePrefs.setGradeToggle(context, grade); CapturePrefs.setSolveDouble(context, expect > 1)
            takePicture()
        }) { Text(s["camera.restart"]) } },
        dismissButton = { TextButton(onClick = { restart = false }) { Text(s["common.cancel"]) } })
}

/** Never accept or delete a path outside this request's narrow FileProvider directory. */
internal fun systemCameraFile(context: Context, path: String): File? = runCatching {
    val directory = File(context.cacheDir, "system-camera").canonicalFile
    File(path).canonicalFile.takeIf { it.parentFile == directory && it.name.startsWith("capture-") }
}.getOrNull()
