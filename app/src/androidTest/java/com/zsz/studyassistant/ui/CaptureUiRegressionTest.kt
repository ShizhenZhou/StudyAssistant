package com.zsz.studyassistant.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zsz.studyassistant.MainActivity
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.ApiKeyStore
import com.zsz.studyassistant.data.AppDatabase
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only in the isolated integration package, with artificial images and no network. */
@RunWith(AndroidJUnit4::class)
class CaptureUiRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val s get() = stringsFor(UiLang.EN)

    private var previousAccessibilityFlags: Int? = null

    @Before fun syntheticOnly() {
        check(context.packageName == "com.zsz.studyassistant.integration")
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        previousAccessibilityFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        ApiKeyStore.saveKey(context, "")
        UiLangStore.save(context, UiLang.EN)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putBoolean("notify_enabled", false)
            .putBoolean("backup_failure_permission_asked", true)
            .putInt("data_upgrade_seen_schema", AppDatabase.SCHEMA_VERSION)
            .putInt("data_upgrade_prompted_schema", AppDatabase.SCHEMA_VERSION).commit()
    }

    @After fun restoreAccessibilityFlags() {
        previousAccessibilityFlags?.let { flags ->
            val info = instrumentation.uiAutomation.serviceInfo
            info.flags = flags
            instrumentation.uiAutomation.serviceInfo = info
        }
    }

    // Dialog dismissal and Compose semantics updates can lag the app's main-loop idle.
    // Query all of our application windows, rather than trusting a cached active-dialog root.
    private fun roots(): List<AccessibilityNodeInfo> {
        val automation = instrumentation.uiAutomation
        val roots = automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }.filter { it.packageName?.toString() == context.packageName }
        val active = automation.rootInActiveWindow
        return if (active?.packageName?.toString() == context.packageName) roots + active else roots
    }

    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser &&
            (node.text?.toString() == text || node.contentDescription?.toString() == text)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
        return null
    }

    private fun diagnostics(): String = buildString {
        append("Active root: ")
        append(instrumentation.uiAutomation.rootInActiveWindow?.packageName)
        append('\n')
        fun describe(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 25 || length > 16000) return
            append("  ".repeat(depth))
            append("class=${node.className} text=${node.text} desc=${node.contentDescription} ")
            append("visible=${node.isVisibleToUser} enabled=${node.isEnabled} ")
            append("clickable=${node.isClickable} checkable=${node.isCheckable} checked=${node.isChecked}\n")
            for (i in 0 until node.childCount) describe(node.getChild(i), depth + 1)
        }
        for (window in instrumentation.uiAutomation.windows) {
            append("window id=${window.id} type=${window.type} active=${window.isActive} focused=${window.isFocused}\n")
            describe(window.root, 0)
        }
        append("active-root tree:\n")
        describe(instrumentation.uiAutomation.rootInActiveWindow, 0)
    }

    private fun waitFor(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            roots().forEach { root -> find(root, text)?.let { return it } }
            Thread.sleep(60)
        }
        error("Missing capture UI: $text\n${diagnostics()}")
    }

    private fun waitUntilAbsent(text: String) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            if (roots().none { find(it, text) != null }) {
                instrumentation.waitForIdleSync()
                return
            }
            Thread.sleep(60)
        }
        error("Capture UI did not dismiss: $text\n${diagnostics()}")
    }

    private fun tap(text: String) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            for (root in roots()) {
                var node: AccessibilityNodeInfo? = find(root, text)
                while (node != null && !node.isClickable) node = node.parent
                if (node?.isEnabled == true && node.isVisibleToUser &&
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    instrumentation.waitForIdleSync()
                    return
                }
            }
            Thread.sleep(60)
        }
        error("Capture action was not clickable: $text\n${diagnostics()}")
    }

    private fun dismissModeChange() {
        tap(s["common.cancel"])
        waitUntilAbsent(s["camera.discardTitle"])
        // Verify the camera page's controls have returned before querying the grade switch.
        waitFor(s["camera.gallery"])
    }

    private fun tapGradeSwitch() {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            for (root in roots()) {
                val node = switch(root)
                if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    instrumentation.waitForIdleSync()
                    return
                }
            }
            Thread.sleep(60)
        }
        error("Grade switch was not clickable after dialog dismissal\n${diagnostics()}")
    }

    private fun touch(text: String, holdMs: Long = 0) {
        val bounds = Rect()
        waitFor(text).getBoundsInScreen(bounds)
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN)
        if (holdMs > 0) Thread.sleep(holdMs)
        inject(MotionEvent.ACTION_UP)
        instrumentation.waitForIdleSync()
    }

    @Test fun wholeImageTapAndHoldUseTheCurrentImageAfterRecomposition() {
        val image = mutableStateOf(1)
        val taps = mutableListOf<Int>()
        val skips = mutableListOf<Int>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme {
                    val capturedImage = image.value
                    HoldToSkipButton(
                        text = "Synthetic whole image $capturedImage", holdHint = "",
                        onTap = { taps += capturedImage; image.value++ },
                        onSkip = { skips += capturedImage; image.value++ },
                        holdEnabled = capturedImage > 1,
                        holdMs = 1000L,
                        modifier = Modifier.fillMaxWidth().height(64.dp)
                    )
                }
            } }
            touch("Synthetic whole image 1")
            touch("Synthetic whole image 2")
            assertEquals("The second tap must submit the second image", listOf(1, 2), taps)
            touch("Synthetic whole image 3", 1200)
            assertEquals("Hold enabled and callback must both refresh", listOf(3), skips)
        }
    }

    @Test fun unavailableCropPreviewUsesTheSameBackFlowAsALoadedImage() {
        val fixture = File.createTempFile("unreadable-preview", ".jpg", context.cacheDir)
        var model: MainViewModel? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val vm = ViewModelProvider(activity)[MainViewModel::class.java]
                    model = vm
                    vm.startCropFlow(listOf(fixture.absolutePath, fixture.absolutePath), grade = true, expect = 2)
                    vm.submitCropResult(byteArrayOf(1))
                    activity.setContent {
                        CompositionLocalProvider(LocalStrings provides s) {
                            MaterialTheme {
                                val nav = rememberNavController()
                                NavHost(nav, startDestination = AppNav.CROP) {
                                    composable(AppNav.CROP) { CropScreen(nav, vm) }
                                    composable(AppNav.CAMERA) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            androidx.compose.material3.Text("Synthetic camera destination")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                waitFor(s["crop.noImage"])
                tap(s["common.back"])
                scenario.onActivity { assertEquals(0, model!!.cropIndex) }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                waitFor("Synthetic camera destination")
                scenario.onActivity { assertTrue(model!!.cropPaths.isEmpty()) }
            }
        } finally { fixture.delete() }
    }

    private fun switch(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isCheckable && node.isEnabled && node.isClickable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) switch(node.getChild(i))?.let { return it }
        return null
    }

    @Test fun deniedCameraKeepsGalleryAndBothModeChangesRequireExplicitAbandonment() {
        val fixture = File.createTempFile("synthetic-ui-fixture", ".jpg", context.cacheDir)
        // A real valid image allows the durable capture journal to validate its synthetic input.
        val bitmap = android.graphics.Bitmap.createBitmap(12, 12, android.graphics.Bitmap.Config.ARGB_8888)
        fixture.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        var model: MainViewModel? = null
        val previousDouble = com.zsz.studyassistant.data.CapturePrefs.solveDouble(context)
        val previousGrade = com.zsz.studyassistant.data.CapturePrefs.gradeToggle(context)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val vm = ViewModelProvider(activity)[MainViewModel::class.java]
                    model = vm
                    vm.cancelCropFlow()
                    vm.startCropFlow(listOf(fixture.absolutePath), grade = true, expect = 2)
                    vm.submitCropResult(fixture.readBytes())
                    assertTrue(vm.cropNeedsMore)
                    // Model a denial without revoking permission (which would kill the test app).
                    val deniedContext = object : ContextWrapper(activity) {
                        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                            if (permission == Manifest.permission.CAMERA) PackageManager.PERMISSION_DENIED
                            else super.checkPermission(permission, pid, uid)
                    }
                    activity.setContent {
                        CompositionLocalProvider(LocalContext provides deniedContext,
                            LocalStrings provides s, LocalUiLang provides UiLang.EN) {
                            MaterialTheme { CameraScreen(rememberNavController(), vm) }
                        }
                    }
                }
                waitFor(s["camera.permNeeded"])
                val gallery = waitFor(s["camera.gallery"])
                assertTrue("Gallery remains enabled after camera permission denial", gallery.isEnabled)
                tap(s["camera.mode.two"])
                waitFor(s["camera.discardTitle"])
                scenario.onActivity { assertEquals(listOf(fixture.absolutePath), model!!.cropPaths); assertTrue(model!!.cropGrade) }
                dismissModeChange()
                scenario.onActivity { assertTrue(model!!.cropNeedsMore); assertEquals(1, model!!.cropPaths.size) }
                // Grade switch must use the same confirmation and preserve the first result on cancel.
                tapGradeSwitch()
                waitFor(s["camera.discardTitle"])
                scenario.onActivity { assertTrue(model!!.cropGrade); assertTrue(model!!.cropNeedsMore) }
                dismissModeChange()
                tap(s["camera.mode.two"])
                tap(s["camera.discardConfirm"])
                waitFor(s["camera.mode.one"])
                scenario.onActivity { assertTrue(model!!.cropPaths.isEmpty()); assertFalse(model!!.cropNeedsMore) }
                // Start another grade pair; confirming the grade change must also clear the queue.
                scenario.onActivity {
                    model!!.startCropFlow(listOf(fixture.absolutePath), grade = true, expect = 2)
                    model!!.submitCropResult(fixture.readBytes())
                }
                instrumentation.waitForIdleSync()
                tapGradeSwitch()
                tap(s["camera.discardConfirm"])
                scenario.onActivity { assertTrue(model!!.cropPaths.isEmpty()); assertFalse(model!!.cropNeedsMore) }
                scenario.onActivity { model!!.cancelCropFlow() }
            }
        } finally {
            instrumentation.runOnMainSync { model?.cancelCropFlow() }
            com.zsz.studyassistant.data.CapturePrefs.setSolveDouble(context, previousDouble)
            com.zsz.studyassistant.data.CapturePrefs.setGradeToggle(context, previousGrade)
            fixture.delete()
        }
    }
}
