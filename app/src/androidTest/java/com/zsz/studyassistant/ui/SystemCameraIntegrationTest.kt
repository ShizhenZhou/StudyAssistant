package com.zsz.studyassistant.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zsz.studyassistant.MainActivity
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.ApiKeyStore
import com.zsz.studyassistant.data.AppDatabase
import com.zsz.studyassistant.data.CapturePrefs
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Only the external camera is faked; real contracts, output URIs, navigation and crop queue run. */
@RunWith(AndroidJUnit4::class)
class SystemCameraIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val s get() = stringsFor(UiLang.EN)

    @Before fun syntheticOnly() {
        check(context.packageName == "com.zsz.studyassistant.integration")
        ApiKeyStore.saveKey(context, "")
        UiLangStore.save(context, UiLang.EN)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putBoolean("notify_enabled", false).putBoolean("backup_failure_permission_asked", true)
            .putInt("data_upgrade_seen_schema", AppDatabase.SCHEMA_VERSION)
            .putInt("data_upgrade_prompted_schema", AppDatabase.SCHEMA_VERSION).commit()
        CapturePrefs.setGradeToggle(context, false)
        CapturePrefs.setSolveDouble(context, true)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.CAMERA)
    }

    private class CameraRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry get() = this
        val captures = CopyOnWriteArrayList<Pair<Int, Uri>>()
        val calls = CopyOnWriteArrayList<String>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            calls.add(contract.javaClass.name)
            check(contract is ActivityResultContracts.TakePicture) { "Unexpected external request: $contract" }
            captures.add(requestCode to (input as Uri))
        }
        fun result(index: Int, ok: Boolean) {
            dispatchResult(captures[index].first, if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, Intent())
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (!condition() && SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
        fun tree(node: AccessibilityNodeInfo?): String = if (node == null) "" else
            "${node.text} ${node.contentDescription}\n" + (0 until node.childCount).joinToString("") { tree(node.getChild(it)) }
        assertTrue("Expected state was never reached\n" + tree(instrumentation.uiAutomation.rootInActiveWindow), condition())
    }
    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
        return null
    }
    private fun waitFor(text: String): AccessibilityNodeInfo {
        await { find(instrumentation.uiAutomation.rootInActiveWindow, text) != null }
        return requireNotNull(find(instrumentation.uiAutomation.rootInActiveWindow, text))
    }
    private fun tap(text: String) {
        var node: AccessibilityNodeInfo? = waitFor(text)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("Clickable action: $text", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        instrumentation.waitForIdleSync()
    }
    private fun touch(text: String, holdMs: Long, slideUp: Boolean = false) {
        val bounds = Rect(); waitFor(text).getBoundsInScreen(bounds)
        val start = SystemClock.uptimeMillis()
        fun event(action: Int, y: Float) {
            MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), y, 0).let {
                it.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(instrumentation.uiAutomation.injectInputEvent(it, true)); it.recycle()
            }
        }
        event(MotionEvent.ACTION_DOWN, bounds.exactCenterY())
        Thread.sleep(holdMs)
        val y = if (slideUp) bounds.top - 180f else bounds.exactCenterY()
        if (slideUp) event(MotionEvent.ACTION_MOVE, y)
        event(MotionEvent.ACTION_UP, y)
        instrumentation.waitForIdleSync()
    }
    private fun writePhoto(uri: Uri) {
        val bitmap = Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888)
        try { context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun externalCaptureCropsEachPhotoAndSecondCancellationKeepsFirst() {
        val camera = CameraRegistry()
        var model: MainViewModel? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[MainViewModel::class.java]; model = vm
                vm.cancelCropFlow()
                activity.setContent {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides camera, LocalStrings provides s) {
                        MaterialTheme {
                            val nav = rememberNavController()
                            NavHost(nav, startDestination = AppNav.CAMERA) {
                                composable(AppNav.CAMERA) { CameraScreen(nav, vm) }
                                composable(AppNav.CROP) { Text("Synthetic crop destination") }
                                composable(AppNav.HOME) { Text("Synthetic home destination") }
                            }
                        }
                    }
                }
            }
            await { camera.captures.size == 1 }
            val first = camera.captures[0].second
            assertEquals("content", first.scheme)
            assertTrue(first.path!!.contains("/system-camera/"))
            writePhoto(first)
            scenario.onActivity { camera.result(0, true) }
            waitFor("Synthetic crop destination")
            scenario.onActivity {
                assertEquals(1, model!!.cropPaths.size)
                assertEquals(2, model!!.cropExpect)
                assertFalse(model!!.cropGrade)
                model!!.submitCropResult(byteArrayOf(1, 2, 3))
                // Return to the same production camera component as CropScreen does.
                it.setContent {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides camera, LocalStrings provides s) {
                        MaterialTheme { CameraScreen(rememberNavController(), model!!) }
                    }
                }
            }
            await { camera.captures.size == 2 }
            scenario.onActivity { camera.result(1, false) }
            waitFor(s["camera.system.retry"])
            scenario.onActivity { assertTrue(model!!.cropNeedsMore); assertEquals(1, model!!.cropPaths.size) }
            Thread.sleep(300)
            assertEquals("Cancellation must not relaunch the camera", 2, camera.captures.size)
            tap(s["camera.system.retry"])
            await { camera.captures.size == 3 }
            scenario.onActivity { camera.result(2, false); model!!.cancelCropFlow() }
        }
    }

    @Test fun nativeCameraRejectsAnEmptySuccessfulOutput() {
        val camera = CameraRegistry()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var vm: MainViewModel? = null
            scenario.onActivity { activity ->
                vm = ViewModelProvider(activity)[MainViewModel::class.java]; vm!!.cancelCropFlow()
                activity.setContent {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides camera, LocalStrings provides s) {
                        MaterialTheme { CameraScreen(rememberNavController(), vm!!) }
                    }
                }
            }
            await { camera.captures.size == 1 }
            scenario.onActivity { camera.result(0, true) }
            waitFor(s["camera.errRead"])
            scenario.onActivity { assertTrue(vm!!.cropPaths.isEmpty()) }
            assertEquals(1, camera.captures.size)
        }
    }

    @Test fun homeLongHoldSelectsGradingAndSlideUpCancels() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var vm: MainViewModel? = null
            scenario.onActivity { activity ->
                vm = ViewModelProvider(activity)[MainViewModel::class.java]; vm!!.cancelCropFlow()
                activity.setContent {
                    CompositionLocalProvider(LocalStrings provides s) {
                        MaterialTheme {
                            val nav = rememberNavController()
                            NavHost(nav, startDestination = AppNav.HOME) {
                                composable(AppNav.HOME) { HomeScreen(nav, vm!!) }
                                composable(AppNav.CAMERA) { Text("Synthetic camera destination") }
                            }
                        }
                    }
                }
            }
            tap(s["home.key.later"])
            waitFor(s["home.camera.multiple"])
            tap(s["home.camera.multiple"])
            waitFor(s["camera.mode.one"])
            touch(s["home.camera"], 650)
            waitFor(s["home.camera"])
            touch(s["home.camera"], 1150, slideUp = true)
            waitFor(s["home.camera"])
            scenario.onActivity { assertFalse(CapturePrefs.gradeToggle(context)) }
            touch(s["home.camera"], 1150)
            waitFor("Synthetic camera destination")
            scenario.onActivity { assertTrue(CapturePrefs.gradeToggle(context)); assertFalse(CapturePrefs.solveDouble(context)) }
        }
    }

    @Test fun staleCameraOutputCannotReplaceANewerCaptureFlow() {
        val camera = CameraRegistry()
        val fixture = File.createTempFile("synthetic-new-flow", ".jpg", context.cacheDir)
        var model: MainViewModel? = null
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[MainViewModel::class.java]; model = vm; vm.cancelCropFlow()
                activity.setContent {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides camera, LocalStrings provides s) {
                        MaterialTheme { CameraScreen(rememberNavController(), vm) }
                    }
                }
            }
            await { camera.captures.size == 1 }
            writePhoto(camera.captures[0].second)
            scenario.onActivity { model!!.startCropFlow(listOf(fixture.absolutePath), true, 2); camera.result(0, true) }
            waitFor(s["camera.errRead"])
            scenario.onActivity { assertEquals(listOf(fixture.absolutePath), model!!.cropPaths); assertTrue(model!!.cropGrade) }
        } } finally { instrumentation.runOnMainSync { model?.cancelCropFlow() }; fixture.delete() }
    }

    @Test fun pendingExternalCameraSurvivesActivityRecreationWithoutRelaunch() {
        val camera = CameraRegistry()
        val application = context.applicationContext as android.app.Application
        val callback = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity !is MainActivity) return
                val vm = ViewModelProvider(activity)[MainViewModel::class.java]
                activity.setContent {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides camera, LocalStrings provides s) {
                        MaterialTheme {
                            val nav = rememberNavController()
                            NavHost(nav, startDestination = AppNav.CAMERA) {
                                composable(AppNav.CAMERA) { CameraScreen(nav, vm) }
                                composable(AppNav.CROP) { Text("Synthetic restored crop") }
                            }
                        }
                    }
                }
            }
            override fun onActivityCreated(a: Activity, state: android.os.Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, state: android.os.Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        application.registerActivityLifecycleCallbacks(callback)
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { ViewModelProvider(it)[MainViewModel::class.java].cancelCropFlow() }
            await { camera.captures.size == 1 }
            val output = camera.captures.single().second
            scenario.recreate()
            waitFor(s["home.camera"])
            assertEquals("A pending camera request must not be launched twice", 1, camera.captures.size)
            writePhoto(output)
            scenario.onActivity { camera.result(0, true) }
            waitFor("Synthetic restored crop")
            scenario.onActivity {
                val vm = ViewModelProvider(it)[MainViewModel::class.java]
                assertEquals(1, vm.cropPaths.size); vm.cancelCropFlow()
            }
        } } finally { application.unregisterActivityLifecycleCallbacks(callback) }
    }

    @Test fun leavingTheCameraCannotReviveAnEmptyCancelledFlow() {
        val camera = CameraRegistry()
        var model: MainViewModel? = null
        var navigation: androidx.navigation.NavHostController? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[MainViewModel::class.java]; model = vm; vm.cancelCropFlow()
                activity.setContent {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides camera, LocalStrings provides s) {
                        MaterialTheme {
                            val nav = rememberNavController(); navigation = nav
                            NavHost(nav, startDestination = AppNav.CAMERA) {
                                composable(AppNav.CAMERA) { CameraScreen(nav, vm) }
                                composable(AppNav.HOME) { Text("Synthetic home after cancellation") }
                                composable(AppNav.CROP) { Text("Unexpected stale crop") }
                            }
                        }
                    }
                }
            }
            await { camera.captures.size == 1 }
            writePhoto(camera.captures.single().second)
            scenario.onActivity {
                model!!.clearCaptureCaches()
                AppNav.switchTab(navigation!!, AppNav.HOME)
                camera.result(0, true)
            }
            waitFor("Synthetic home after cancellation")
            Thread.sleep(400)
            scenario.onActivity {
                assertEquals(AppNav.HOME, navigation!!.currentDestination?.route)
                assertTrue(model!!.cropPaths.isEmpty())
            }
        }
    }
}
