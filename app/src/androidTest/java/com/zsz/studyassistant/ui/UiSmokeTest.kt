package com.zsz.studyassistant.ui

import androidx.activity.compose.setContent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zsz.studyassistant.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Compose navigation and WebView rendering, in the no-network synthetic package only. */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val s get()=stringsFor(UiLang.EN)
    @Before fun initializeSyntheticUiState() {
        check(context.packageName == "com.zsz.studyassistant.integration")
        // adb install -r retains this isolated package's synthetic setup from earlier runs.
        // Never read credentials or rely on another test to leave the no-key startup state.
        com.zsz.studyassistant.data.ApiKeyStore.saveKey(context, "")
        UiLangStore.save(context, UiLang.EN)
        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("notify_enabled", false)
            .putBoolean("backup_failure_permission_asked", true).commit()
    }
    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if(node==null) return null
        if(node.isVisibleToUser && (node.text?.toString()==text || node.contentDescription?.toString()==text)) return node
        for(i in 0 until node.childCount) find(node.getChild(i),text)?.let { return it }
        return null
    }
    private fun waitFor(text: String): AccessibilityNodeInfo {
        val deadline=android.os.SystemClock.uptimeMillis()+8000
        while(android.os.SystemClock.uptimeMillis()<deadline) {
            find(instrumentation.uiAutomation.rootInActiveWindow,text)?.let { return it }
            Thread.sleep(80)
        }
        error("Missing UI text: $text\n${uiDiagnostics()}")
    }
    private fun waitUntilAbsent(text: String) {
        val deadline = android.os.SystemClock.uptimeMillis() + 8000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (find(instrumentation.uiAutomation.rootInActiveWindow, text) == null) return
            Thread.sleep(80)
        }
        error("UI text did not disappear: $text")
    }
    private fun clickable(node: AccessibilityNodeInfo?, text: String, visibleOnly: Boolean = true): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) {
            var candidate: AccessibilityNodeInfo? = node
            while (candidate != null) {
                if (candidate.isClickable && candidate.isEnabled && (!visibleOnly || candidate.isVisibleToUser)) return candidate
                candidate = candidate.parent
            }
        }
        for (i in 0 until node.childCount) clickable(node.getChild(i), text, visibleOnly)?.let { return it }
        return null
    }
    private fun scrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) scrollable(node.getChild(i))?.let { return it }
        return null
    }
    private fun tap(text: String) {
        val deadline = android.os.SystemClock.uptimeMillis() + 8000
        var revealRequested = false
        var forward = true
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            // Ignore an exiting page's same-name title and scroll offscreen controls into view.
            val target = clickable(root, text)
            if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                instrumentation.waitForIdleSync()
                // Main-loop idle does not mean navigation or a platform Dialog has finished animating.
                instrumentation.uiAutomation.waitForIdle(300, 3000)
                return
            }
            val offscreen = clickable(root, text, visibleOnly = false)
            if (offscreen != null && !offscreen.isVisibleToUser && !revealRequested) {
                revealRequested = true
                offscreen.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                instrumentation.waitForIdleSync()
            } else if (offscreen == null || !offscreen.isVisibleToUser) {
                val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                if (scrollable(root)?.performAction(action) != true) forward = !forward
                instrumentation.waitForIdleSync()
            }
            Thread.sleep(120)
        }
        error("Not clickable: $text\n${uiDiagnostics()}")
    }
    private fun uiDiagnostics(): String {
        val out = StringBuilder()
        fun describe(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 20) return
            val text = node.text ?: node.contentDescription
            if (!text.isNullOrEmpty()) out.append("  ".repeat(depth)).append(text).append(" clickable=")
                .append(node.isClickable).append(" visible=").append(node.isVisibleToUser).append('\n')
            for (i in 0 until node.childCount) describe(node.getChild(i), depth + 1)
        }
        describe(instrumentation.uiAutomation.rootInActiveWindow, 0)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        if (bitmap != null) try { java.io.File(context.filesDir, "ui-failure-${android.os.SystemClock.uptimeMillis()}.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
        return out.toString()
    }
    private fun dismissHomeKeyDialog() {
        if (find(instrumentation.uiAutomation.rootInActiveWindow, s["home.key.later"]) != null) {
            tap(s["home.key.later"])
            waitUntilAbsent(s["home.key.title"])
        }
    }
    private fun tapPhysical(text: String) {
        instrumentation.uiAutomation.waitForIdle(300, 3000)
        val deadline = android.os.SystemClock.uptimeMillis() + 8000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val label = find(root, text)
            var target = clickable(root, text, visibleOnly = false) ?: label
            if (target != null && !target.isVisibleToUser) {
                target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                instrumentation.uiAutomation.waitForIdle(300, 3000)
                continue
            }
            if (target != null && !target.isClickable && !target.isCheckable) {
                var parent = target.parent
                while (parent != null) {
                    if (parent.isSelected || parent.isClickable || parent.isCheckable) { target = parent; break }
                    parent = parent.parent
                }
            }
            if (target != null && target.isVisibleToUser) {
                val bounds = android.graphics.Rect()
                target.getBoundsInScreen(bounds)
                val downAt = android.os.SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(downAt, android.os.SystemClock.uptimeMillis(), action,
                        bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                    finally { event.recycle() }
                    Thread.sleep(40)
                }
                instrumentation.uiAutomation.waitForIdle(300, 3000)
                return
            }
            Thread.sleep(80)
        }
        error("Cannot physically tap: $text\n${uiDiagnostics()}")
    }
    private fun launch(openBackup: Boolean = false, openUpdate: Boolean = false): ActivityScenario<MainActivity> {
        check(context.packageName=="com.zsz.studyassistant.integration")
        UiLangStore.save(context,UiLang.EN)
        // These journeys cover navigation, independently of the upgrade-prompt policy tests.
        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit()
            .putInt("data_upgrade_seen_schema", com.zsz.studyassistant.data.AppDatabase.SCHEMA_VERSION)
            .putInt("data_upgrade_prompted_schema", com.zsz.studyassistant.data.AppDatabase.SCHEMA_VERSION)
            // Individual backup tests may reset preferences after @Before; suppress unrelated
            // system permission prompts again without changing their folder/job fixtures.
            .putBoolean("notify_enabled", false)
            .putBoolean("backup_failure_permission_asked", true).commit()
        val scenario=ActivityScenario.launch<MainActivity>(android.content.Intent(context, MainActivity::class.java)
            .putExtra(AppNav.OPEN_BACKUP_EXTRA, openBackup)
            .putExtra(AppNav.OPEN_UPDATE_EXTRA, openUpdate))
        try {
            when {
                openUpdate -> waitFor(s["update.current"])
                openBackup -> waitFor(s["data.manualBackup"])
                else -> {
                    // The bottom bar can appear before Home's asynchronous key dialog.
                    waitFor(s["home.key.later"])
                    tap(s["home.key.later"])
                    waitUntilAbsent(s["home.key.title"])
                    waitFor(s["home.camera"])
                }
            }
            return scenario
        } catch (failure: Throwable) {
            scenario.close()
            throw failure
        }
    }
    @Test fun wallpaperSettingSurvivesRecreationAndRestoresOpaqueWindow() {
        val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val previousWallpaper = prefs.getBoolean("wallpaper_background", false)
        val previousTheme = prefs.getString("theme", "system")
        val automation = instrumentation.uiAutomation
        val wallpaper = android.app.WallpaperManager.getInstance(context)
        automation.adoptShellPermissionIdentity(android.Manifest.permission.SET_WALLPAPER)
        val fixture = android.graphics.Bitmap.createBitmap(256, 1024, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(fixture)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLUE }
        for (y in 0 until 1024 step 64) canvas.drawRect(0f, y.toFloat(), 256f, y + 32f, paint)
        try { wallpaper.setBitmap(fixture) }
        catch (error: Throwable) { automation.dropShellPermissionIdentity(); throw error }
        finally { fixture.recycle() }
        instrumentation.uiAutomation.waitForIdle(300, 3000)
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            try { java.io.File(context.filesDir, "ui-wallpaper-source.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            } } finally { image.recycle() }
        }
        prefs.edit().putBoolean("wallpaper_background", false).putString("theme", "light").commit()
        var nativeBackgrounds = ""
        fun screenshot(name: String, darkLabel: String? = null, checkWallpaper: Boolean = false) {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(300, 3000)
            val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                java.io.File(context.filesDir, name).outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                if (checkWallpaper) {
                    val samples = (100 until minOf(500, image.height - 120)).map {
                        android.graphics.Color.red(image.getPixel(8, it))
                    }
                    assertTrue("The wallpaper must actually show through the page; range=${samples.min()}..${samples.max()}; $nativeBackgrounds", samples.max() - samples.min() >= 10)
                }
                if (darkLabel != null) {
                    val bounds = android.graphics.Rect()
                    waitFor(darkLabel).getBoundsInScreen(bounds)
                    var readable = 0
                    for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(image.height)) {
                        for (x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(image.width)) {
                            val pixel = image.getPixel(x, y)
                            if (android.graphics.Color.red(pixel) > 160 && android.graphics.Color.green(pixel) > 160 && android.graphics.Color.blue(pixel) > 160) readable++
                        }
                    }
                    assertTrue("Dark wallpaper mode must keep ordinary text readable", readable > 10)
                }
            } finally { image.recycle() }
        }
        try {
            launch().use { scenario ->
                screenshot("ui-home-light.png")
                tap(s["nav.settings"])
                tap(s["settings.theme"])
                tapPhysical(s["settings.theme.wallpaper"])
                var enabled = false
                var wallpaperFlags = 0
                scenario.onActivity { activity ->
                    enabled = androidx.lifecycle.ViewModelProvider(activity)[com.zsz.studyassistant.MainViewModel::class.java].wallpaperBackground
                    wallpaperFlags = activity.window.attributes.flags
                }
                if (!enabled || wallpaperFlags and android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER == 0) {
                    error("Wallpaper toggle enabled=$enabled flags=$wallpaperFlags\n${uiDiagnostics()}")
                }
                scenario.onActivity { activity ->
                    assertFalse(activity.window.attributes.areWallpaperTouchEventsEnabled())
                    assertEquals(android.graphics.PixelFormat.TRANSLUCENT, activity.window.attributes.format)
                    fun describe(view: android.view.View) {
                        val bg = view.background
                        nativeBackgrounds += "${view.javaClass.simpleName}:opaque=${view.isOpaque},background=${(bg as? android.graphics.drawable.ColorDrawable)?.color},alpha=${bg?.alpha}; "
                        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) describe(view.getChildAt(i))
                    }
                    describe(activity.window.decorView)
                }
                screenshot("ui-wallpaper-light.png", checkWallpaper = true)
                scenario.recreate()
                waitFor(s["settings.theme.wallpaper"])
                scenario.onActivity { activity ->
                    val vm = androidx.lifecycle.ViewModelProvider(activity)[com.zsz.studyassistant.MainViewModel::class.java]
                    assertTrue(vm.wallpaperBackground)
                    assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER != 0)
                    vm.updateTheme("dark")
                }
                instrumentation.waitForIdleSync()
                screenshot("ui-wallpaper-dark.png", darkLabel = s["settings.theme.mode"], checkWallpaper = true)
                tapPhysical(s["settings.theme.wallpaper"])
                scenario.onActivity { activity ->
                    assertEquals(0, activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                    assertEquals(android.graphics.PixelFormat.OPAQUE, activity.window.attributes.format)
                    assertFalse(androidx.lifecycle.ViewModelProvider(activity)[com.zsz.studyassistant.MainViewModel::class.java].wallpaperBackground)
                }
                screenshot("ui-theme-dark.png")
            }
        } finally {
            prefs.edit().putBoolean("wallpaper_background", previousWallpaper).putString("theme", previousTheme).commit()
            try { wallpaper.clear() } finally { automation.dropShellPermissionIdentity() }
        }
    }

    @Test fun wideHomeKeepsActionsCenteredAndAtReadableWidth() {
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        val previousSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val previousDensity = Regex("Override density: (\\d+)").find(shell("wm density"))?.groupValues?.get(1)
        try {
            shell("wm size 1600x1000")
            shell("wm density 160")
            launch().use { scenario ->
                val bounds = android.graphics.Rect()
                waitFor(s["home.camera"])
                requireNotNull(clickable(instrumentation.uiAutomation.rootInActiveWindow, s["home.camera"])).getBoundsInScreen(bounds)
                scenario.onActivity { activity ->
                    val width = activity.window.decorView.width
                    val density = activity.resources.displayMetrics.density
                    assertTrue("Exercise a genuinely wide window: $width", width >= 1000)
                    assertTrue("Home actions must not stretch across the window: $bounds", bounds.width() <= 560 * density + 2)
                    assertTrue("Home actions stay centered: $bounds / $width", kotlin.math.abs(bounds.centerX() - width / 2) <= 2 * density)
                }
                val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { java.io.File(context.filesDir, "ui-home-wide.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                } } finally { image.recycle() }
            }
        } finally {
            shell("wm size ${previousSize ?: "reset"}")
            shell("wm density ${previousDensity ?: "reset"}")
        }
    }

    private fun waitForBackground(scenario: ActivityScenario<MainActivity>) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (scenario.state == androidx.lifecycle.Lifecycle.State.RESUMED && android.os.SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(80)
        }
        assertNotEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
        assertNotEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
    }

    @Test fun rootBackDistinguishesCommittedGesturesAndIgnoresCancelledGestures() {
        launch().use { scenario ->
            tap(s["nav.stats"])
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor(s["back.exit.pressAgain"])
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
            waitUntilAbsent(s["back.exit.pressAgain"])
            scenario.onActivity {
                val dispatcher = it.onBackPressedDispatcher
                dispatcher.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 200f, 0f, androidx.activity.BackEventCompat.EDGE_LEFT))
                dispatcher.dispatchOnBackProgressed(androidx.activity.BackEventCompat(80f, 200f, .4f, androidx.activity.BackEventCompat.EDGE_LEFT))
                dispatcher.dispatchOnBackCancelled()
            }
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, s["back.exit.swipeAgain"]))
            repeat(2) { index ->
                scenario.onActivity {
                    val dispatcher = it.onBackPressedDispatcher
                    dispatcher.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 200f, 0f, androidx.activity.BackEventCompat.EDGE_LEFT))
                    dispatcher.dispatchOnBackProgressed(androidx.activity.BackEventCompat(120f, 200f, .8f, androidx.activity.BackEventCompat.EDGE_LEFT))
                    dispatcher.onBackPressed()
                }
                if (index == 0) waitFor(s["back.exit.swipeAgain"])
            }
            waitForBackground(scenario)
        }
    }

    @Test fun rootBackPressConfirmationResetsWhenSwitchingTabs() {
        launch().use { scenario ->
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor(s["back.exit.pressAgain"])
            tap(s["nav.stats"])
            waitUntilAbsent(s["back.exit.pressAgain"])
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor(s["back.exit.pressAgain"])
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitForBackground(scenario)
        }
    }

    @Test fun notebookBottomBarSwitchesTabsAndDoesNotRestoreTheOldChild() {
        launch().use {
            tap(s["home.notebook"])
            waitFor(s["nav.stats"])
            tap(s["nav.stats"])
            waitFor(s["stats.title"])
            tap(s["nav.learn"])
            dismissHomeKeyDialog()
            waitFor(s["home.camera"])
            tap(s["nav.settings"])
            tap(s["settings.about"])
            waitFor(s["about.author"] + "\n" + s["about.desc"])
            tap("←")
            waitFor(s["settings.aiManage"])
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, s["back.exit.pressAgain"]))
        }
    }

    @Test fun categoryOrderDragsPersistsAndReviewSubjectFilterShowsADropdown() {
        val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val oldEnabled = prefs.getBoolean("category_order_custom", false)
        val oldOrder = prefs.getString("category_order_ids", null)
        val dao = com.zsz.studyassistant.data.AppDatabase.get(context).questionDao()
        val suffix = java.util.UUID.randomUUID().toString()
        val firstName = "Subject A ${suffix.take(4)}"
        val secondName = "Subject B ${suffix.take(4)}"
        val questionId = "category-ui-$suffix"
        var firstId = 0L
        var secondId = 0L
        val extraIds = mutableListOf<Long>()
        fun saveScreenshot() {
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { java.io.File(context.filesDir, "ui-category-order.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            } } finally { bitmap.recycle() }
        }
        try {
            kotlinx.coroutines.runBlocking {
                firstId = dao.insertCategory(com.zsz.studyassistant.data.Category(name = firstName))
                secondId = dao.insertCategory(com.zsz.studyassistant.data.Category(name = secondName))
                repeat(10) { extraIds += dao.insertCategory(com.zsz.studyassistant.data.Category(name = "Subject ${it + 1} ${suffix.take(4)}")) }
                dao.insert(com.zsz.studyassistant.data.Question(questionId, "synthetic category question", "answer", categoryId = firstId))
                dao.insertReview(com.zsz.studyassistant.data.Review(questionId, nextReviewAt = 0))
            }
            prefs.edit().putBoolean("category_order_custom", false)
                .putString("category_order_ids", (listOf(secondId, firstId) + extraIds + -1L).joinToString(",")).commit()
            launch().use { scenario ->
                tap(s["home.notebook"])
                tap(s["notebook.manage"])
                tap(s["catManage.customOrder"])
                val moving = android.graphics.Rect()
                val target = android.graphics.Rect()
                waitFor(s.format("catManage.drag", "name" to secondName)).getBoundsInScreen(moving)
                waitFor(s.format("catManage.drag", "name" to firstName)).getBoundsInScreen(target)
                val downAt = android.os.SystemClock.uptimeMillis()
                fun touch(action: Int, y: Float) {
                    val event = android.view.MotionEvent.obtain(downAt, android.os.SystemClock.uptimeMillis(), action,
                        moving.centerX().toFloat(), y, 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                    finally { event.recycle() }
                }
                touch(android.view.MotionEvent.ACTION_DOWN, moving.centerY().toFloat())
                Thread.sleep(650)
                val end = target.centerY() + 6f
                for (step in 1..12) {
                    touch(android.view.MotionEvent.ACTION_MOVE, moving.centerY() + (end - moving.centerY()) * step / 12)
                    Thread.sleep(20)
                }
                // Holding the first visible row here must not make it cascade down a long list.
                Thread.sleep(700)
                touch(android.view.MotionEvent.ACTION_UP, end)
                instrumentation.waitForIdleSync()
                val deadline = android.os.SystemClock.uptimeMillis() + 4000
                while (prefs.getString("category_order_ids", "")?.startsWith("$firstId,$secondId") != true && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
                assertTrue("Long-press drag must persist the new order", prefs.getString("category_order_ids", "")!!.startsWith("$firstId,$secondId"))
                assertTrue(prefs.getBoolean("category_order_custom", false))
                saveScreenshot()
                scenario.recreate()
                waitFor(s["notebook.manage"])
                tap(s["notebook.manage"])
                val a = android.graphics.Rect()
                val b = android.graphics.Rect()
                waitFor(s.format("catManage.drag", "name" to firstName)).getBoundsInScreen(a)
                waitFor(s.format("catManage.drag", "name" to secondName)).getBoundsInScreen(b)
                assertTrue(a.centerY() < b.centerY())
                tap(s["catManage.customOrder"])
                assertFalse(prefs.getBoolean("category_order_custom", true))
                tapPhysical(s["nav.learn"])
                dismissHomeKeyDialog()
                tap(s["home.review"])
                waitFor(s["review.filter.chooseSubject"])
                tap(s["review.filter.allSubjects"])
                tap(firstName)
            }
        } finally {
            prefs.edit().putBoolean("category_order_custom", oldEnabled).putString("category_order_ids", oldOrder).commit()
            kotlinx.coroutines.runBlocking {
                dao.deleteReviewsForQuestions(listOf(questionId))
                dao.deleteById(questionId)
                if (firstId != 0L) dao.deleteCategory(firstId)
                if (secondId != 0L) dao.deleteCategory(secondId)
                extraIds.forEach { dao.deleteCategory(it) }
            }
        }
    }

    @Test fun notificationPendingIntentsStayIndependentInBothCreationOrders() {
        check(context.packageName == "com.zsz.studyassistant.integration")
        UiLangStore.save(context, UiLang.EN)
        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit()
            .putInt("data_upgrade_seen_schema", com.zsz.studyassistant.data.AppDatabase.SCHEMA_VERSION)
            .putInt("data_upgrade_prompted_schema", com.zsz.studyassistant.data.AppDatabase.SCHEMA_VERSION).commit()
        val entries = com.zsz.studyassistant.data.NotificationIntents
        for (reviewFirst in listOf(false, true)) {
            var answer: android.app.PendingIntent? = null
            var review: android.app.PendingIntent? = null
            var alarm: android.app.PendingIntent? = null
            try {
                // These are real system PendingIntent tokens, using the production factories.
                if (reviewFirst) {
                    review = entries.reviewReminder(context)
                    alarm = entries.alarmClock(context)
                    answer = entries.answerRunning(context)
                } else {
                    answer = entries.answerRunning(context)
                    alarm = entries.alarmClock(context)
                    review = entries.reviewReminder(context)
                }
                val answerIntent = requireNotNull(answer)
                val reviewIntent = requireNotNull(review)
                val alarmIntent = requireNotNull(alarm)
                assertNotEquals(answer, review)
                assertNotEquals(answer, alarm)
                assertNotEquals(review, alarm)
                assertEquals(answer, entries.answerRunning(context))
                assertEquals(review, entries.reviewReminder(context))
                assertEquals(alarm, entries.alarmClock(context))
                // ActivityScenario filters lifecycle events by its original intent action.
                // MainActivity replaces its intent on delivery, so match the final real entry
                // before recreate/close; intermediate sends still assert their delivered actions.
                val launchIntent = android.content.Intent(context, MainActivity::class.java)
                    .setAction("${context.packageName}.notification.ALARM_CLOCK")
                ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
                    fun sendAndWait(pending: android.app.PendingIntent, action: String) {
                        pending.send()
                        var delivered = false
                        val deadline = android.os.SystemClock.uptimeMillis() + 8000
                        while (!delivered && android.os.SystemClock.uptimeMillis() < deadline) {
                            scenario.onActivity { delivered = it.intent.action == "${context.packageName}.notification.$action" }
                            if (!delivered) Thread.sleep(80)
                        }
                        assertTrue("PendingIntent was not delivered: $action", delivered)
                        instrumentation.waitForIdleSync()
                    }
                    waitFor(s["home.key.later"])
                    tap(s["home.key.later"])
                    waitUntilAbsent(s["home.key.title"])
                    waitFor(s["home.camera"])
                    tap(s["home.ask"])
                    waitFor(s["ask.pageTitle"])
                    val original = java.util.concurrent.atomic.AtomicReference<MainActivity>()
                    scenario.onActivity { original.set(it) }
                    // Sending the actual notification contentIntent exercises the same OS delivery
                    // as a tap, without granting notifications or driving the system shade.
                    sendAndWait(answerIntent, "ANSWER_RUNNING")
                    waitFor(s["ask.pageTitle"])
                    waitUntilAbsent(s["review.tab.today"])
                    scenario.onActivity { assertSame(original.get(), it) }
                    sendAndWait(reviewIntent, "REVIEW_REMINDER")
                    waitFor(s["review.tab.today"])
                    scenario.onActivity { activity ->
                        assertSame(original.get(), activity)
                        assertFalse(activity.intent.hasExtra(AppNav.OPEN_REVIEW_EXTRA))
                        activity.onBackPressedDispatcher.onBackPressed()
                    }
                    waitFor(s["home.key.later"])
                    tap(s["home.key.later"])
                    waitFor(s["home.camera"])
                    waitUntilAbsent(s["review.tab.today"])
                    // Recreating the alarm-clock entry must not turn an answer tap into a review.
                    entries.alarmClock(context)
                    sendAndWait(answerIntent, "ANSWER_RUNNING")
                    waitFor(s["home.camera"])
                    waitUntilAbsent(s["review.tab.today"])
                    sendAndWait(alarmIntent, "ALARM_CLOCK")
                    waitFor(s["home.camera"])
                    waitUntilAbsent(s["review.tab.today"])
                    scenario.onActivity { activity ->
                        assertSame(original.get(), activity)
                        assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, activity.lifecycle.currentState)
                    }
                    scenario.recreate()
                    waitFor(s["home.key.later"])
                    tap(s["home.key.later"])
                    waitFor(s["home.camera"])
                    waitUntilAbsent(s["review.tab.today"])
                }
            } finally {
                answer?.cancel(); review?.cancel(); alarm?.cancel()
            }
        }
    }

    @Test fun unsavedCustomPromptSurvivesRecreationUntilExplicitSave() {
        val old = com.zsz.studyassistant.data.CapturePrefs.customPrompt(context)
        val draft = "Synthetic draft preserved across window recreation"
        fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable) return node
            for (i in 0 until node.childCount) editable(node.getChild(i))?.let { return it }
            return null
        }
        try {
            launch().use { scenario ->
                tap(s["nav.settings"]); tap(s["settings.aiManage"]); tap(s["settings.customPrompt"])
                waitFor(s["settings.customPrompt"])
                val deadline = android.os.SystemClock.uptimeMillis() + 8000
                var input = editable(instrumentation.uiAutomation.rootInActiveWindow)
                while (input == null && android.os.SystemClock.uptimeMillis() < deadline) {
                    Thread.sleep(80)
                    input = editable(instrumentation.uiAutomation.rootInActiveWindow)
                }
                checkNotNull(input) { "Missing prompt editor" }
                assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, draft)
                }))
                waitFor(draft)
                assertEquals(old, com.zsz.studyassistant.data.CapturePrefs.customPrompt(context))
                scenario.recreate()
                waitFor(draft)
                assertEquals(old, com.zsz.studyassistant.data.CapturePrefs.customPrompt(context))
                tap(s["common.save"])
                assertEquals(draft, com.zsz.studyassistant.data.CapturePrefs.customPrompt(context))
            }
        } finally { com.zsz.studyassistant.data.CapturePrefs.setCustomPrompt(context, old) }
    }

    @Test fun largePromptDraftStaysInViewModelAcrossRecreation() {
        val draft = "synthetic-large-draft ".repeat(60_000)
        launch().use { scenario ->
            scenario.onActivity {
                androidx.lifecycle.ViewModelProvider(it)[com.zsz.studyassistant.MainViewModel::class.java].customPromptDraft = draft
            }
            scenario.recreate()
            scenario.onActivity {
                assertEquals(draft, androidx.lifecycle.ViewModelProvider(it)[com.zsz.studyassistant.MainViewModel::class.java].customPromptDraft)
            }
        }
    }

    @Test fun statisticsStopCollectingInBackgroundAndRefreshOnReturn() = kotlinx.coroutines.runBlocking {
        val dao = com.zsz.studyassistant.data.AppDatabase.get(context).questionDao()
        val id = com.zsz.studyassistant.data.QuestionIdentity.create(System.currentTimeMillis())
        try {
            launch().use { scenario ->
                tap(s["nav.stats"]); waitFor(s["stats.title"])
                lateinit var model: com.zsz.studyassistant.MainViewModel
                scenario.onActivity { model = androidx.lifecycle.ViewModelProvider(it)[com.zsz.studyassistant.MainViewModel::class.java] }
                kotlinx.coroutines.delay(1000)
                val before = model.stats.value.totalQuestions
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                kotlinx.coroutines.delay(5500) // WhileSubscribed's grace period must expire.
                dao.insert(com.zsz.studyassistant.data.Question(id, "synthetic lifecycle fixture", "answer", System.currentTimeMillis()))
                kotlinx.coroutines.delay(1000)
                assertEquals(before, model.stats.value.totalQuestions)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                kotlinx.coroutines.withTimeout(8000) {
                    while (model.stats.value.totalQuestions != before + 1) kotlinx.coroutines.delay(80)
                }
            }
        } finally { dao.deleteById(id) }
    }

    @Test fun leavingSettingsThroughEitherBottomTabClearsItsInternalPage() {
        launch().use { scenario ->
            tap(s["nav.settings"])
            waitFor(s["settings.aiManage"])
            for (tab in listOf("nav.learn", "nav.stats", "nav.learn")) {
                tap(s["settings.aiManage"])
                tap(s["settings.customPrompt"])
                // Rotation is not a tab switch: retain the user's current child page.
                scenario.recreate()
                waitFor(s["settings.customPrompt"])
                tap(s[tab])
                if (tab == "nav.learn") {
                    // The synthetic package has no API key; each new Home opens its setup dialog.
                    waitFor(s["home.key.later"])
                    tap(s["home.key.later"])
                    waitFor(s["home.camera"])
                } else waitFor(s["stats.title"])
                tap(s["nav.settings"])
                waitFor(s["settings.aiManage"])
                waitUntilAbsent(s["settings.customPrompt"])
            }
        }
    }

    @Test fun settingsChildrenReturnToTheirOwnParents() {
        launch().use { scenario ->
            tap("Settings")
            tap(s["settings.aiManage"])
            tapPhysical(s["settings.customPrompt"])
            waitFor(s["settings.customPrompt.desc"])
            scenario.onActivity {
                it.onBackPressedDispatcher.onBackPressed()
                it.onBackPressedDispatcher.onBackPressed()
            }
            waitFor(s["settings.aiManage"])
            waitFor(s["settings.aiCropPage"])
            instrumentation.uiAutomation.waitForIdle(300, 3000)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            tap(s["settings.data"])
            tap(s["data.backup"])
            waitFor(s["data.manualBackup"])
            tap("←")
            waitFor(s["data.crash"])
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor(s["settings.aiManage"])
        }
    }
    @Test fun lastBackupIsVisibleWithAutomaticBackupOnOrOffAndWithoutRecords() {
        val provider = com.zsz.studyassistant.data.TestBackupDocumentsProvider
        val preferences = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val tree = android.provider.DocumentsContract.buildTreeDocumentUri(context.packageName + ".fixture.backups", "root")
        val at = System.currentTimeMillis()
        try {
            for (hasBackup in listOf(false, true)) {
                provider.reset(context)
                if (hasBackup) {
                    val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, "root")
                    val uri = android.provider.DocumentsContract.createDocument(context.contentResolver, parent,
                        "application/json", "study-assistant-auto-$at.json")!!
                    context.contentResolver.openOutputStream(uri, "wt")!!.use {
                        it.write("""{"format":"study-assistant-backup","version":3,"exportedAt":$at,"questions":[],"categories":[],"tags":[],"questionTags":[],"reviews":[],"solveSessions":[]}""".toByteArray())
                    }
                }
                for (enabled in listOf(false, true)) {
                    preferences.edit().putString("auto_backup_tree", tree.toString()).putBoolean("auto_backup_enabled", enabled).commit()
                    launch(openBackup = true).use {
                        waitFor(if (hasBackup) s.format("data.autoBackup.last", "t" to formatCheckTime(at)) else s["data.autoBackup.never"])
                    }
                }
            }
        } finally {
            com.zsz.studyassistant.data.BackupJobs.cancel(context)
            preferences.edit().remove("auto_backup_tree").putBoolean("auto_backup_enabled", false).commit()
            provider.reset(context)
        }
    }

    @Test fun foregroundBackupFailureDetailsOpensBackupAndExplanationExpands() {
        launch().use { scenario ->
            waitFor("Settings")
            // Wait for the STARTED notice collector, then emit a final synthetic foreground failure.
            Thread.sleep(200)
            scenario.onActivity { com.zsz.studyassistant.data.BackupJobs.notifyFailure(context, automatic = true) }
            waitFor(s["data.backupNotifyFailed"])
            tap(s["feedback.details"])
            waitFor(s["data.manualBackup"])
            val explanation = s.format("data.autoBackup.desc", "n" to "${com.zsz.studyassistant.data.AutoBackup.KEEP}")
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, explanation))
            tap(s["data.autoBackup.info"])
            waitFor(explanation)
            tap(s["data.autoBackup.info"])
            waitUntilAbsent(explanation)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor(s["data.crash"])
            // Opening details from within Settings must not recreate the tab and lose the target.
            scenario.onActivity { com.zsz.studyassistant.data.BackupJobs.notifyFailure(context, automatic = true) }
            waitFor(s["data.backupNotifyFailed"])
            tap(s["feedback.details"])
            waitFor(s["data.manualBackup"])
        }
    }

    @Test fun exportPickerStartsAtTheConfiguredAutomaticFolder() {
        check(context.packageName == "com.zsz.studyassistant.integration")
        val preferences = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val previous = preferences.getString("auto_backup_tree", null)
        val tree = android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FStudy")
        try {
            preferences.edit().putString("auto_backup_tree", tree.toString()).commit()
            val intent = BackupExportDocument().createIntent(context, "backup.zip")
            val expected = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,
                android.provider.DocumentsContract.getTreeDocumentId(tree))
            @Suppress("DEPRECATION")
            val actual = intent.getParcelableExtra<android.net.Uri>(android.provider.DocumentsContract.EXTRA_INITIAL_URI)
            assertEquals(expected, actual)
            assertEquals("backup.zip", intent.getStringExtra(android.content.Intent.EXTRA_TITLE))
            assertEquals("application/zip", intent.type)
            preferences.edit().remove("auto_backup_tree").commit()
            assertFalse(BackupExportDocument().createIntent(context, "backup.zip")
                .hasExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI))
        } finally {
            preferences.edit().apply { if (previous == null) remove("auto_backup_tree") else putString("auto_backup_tree", previous) }.commit()
        }
    }

    @Test fun updateNotificationEntryOpensUpdateAndReturnsToSettings() {
        launch(openUpdate = true).use { scenario ->
            waitFor(s["update.current"])
            scenario.onActivity { activity ->
                assertFalse(activity.intent.hasExtra(AppNav.OPEN_UPDATE_EXTRA))
                activity.onBackPressedDispatcher.onBackPressed()
            }
            waitFor(s["settings.aiManage"])
        }
    }

    @Test fun updateNotificationNewIntentOpensUpdateFromAnotherSettingsChild() {
        launch(openBackup = true).use { scenario ->
            waitFor(s["data.manualBackup"])
            scenario.onActivity { activity ->
                activity.startActivity(android.content.Intent(context, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(AppNav.OPEN_UPDATE_EXTRA, true))
            }
            waitFor(s["update.current"])
            scenario.onActivity { activity ->
                assertFalse(activity.intent.hasExtra(AppNav.OPEN_UPDATE_EXTRA))
                activity.onBackPressedDispatcher.onBackPressed()
            }
            waitFor(s["settings.aiManage"])
            scenario.recreate()
            waitFor(s["settings.aiManage"])
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, s["update.current"]))
        }
    }

    @Test fun backupFailureEntryOpensBackupAndReturnsToDataManagement() {
        launch(openBackup = true).use { scenario ->
            waitFor(s["data.manualBackup"])
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor(s["data.crash"])
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor(s["settings.aiManage"])
        }
    }
    @Test fun preparedExportHasContinueAndCancelAfterReturningToItsPage() {
        launch().use { scenario ->
            tap("Settings")
            waitFor(s["settings.aiManage"])
            tap(s["settings.data"])
            waitFor(s["data.crash"])
            tap(s["data.backup"])
            waitFor(s["data.manualBackup"])
            var viewModel: com.zsz.studyassistant.MainViewModel? = null
            scenario.onActivity { activity ->
                viewModel = androidx.lifecycle.ViewModelProvider(activity)[com.zsz.studyassistant.MainViewModel::class.java]
                // No surviving UI callback: simulate finishing after its original page left composition.
                viewModel!!.prepareBackupExport { }
                activity.onBackPressedDispatcher.onBackPressed()
            }
            waitFor(s["data.crash"])
            var ready = false
            val deadline = android.os.SystemClock.uptimeMillis() + 10000
            while (!ready && android.os.SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync { ready = viewModel!!.preparedExport != null && !viewModel!!.dataBusy }
                if (!ready) Thread.sleep(40)
            }
            assertTrue("Export did not become ready", ready)
            tap(s["data.backup"])
            assertTrue(waitFor(s["data.backupContinue"]).isEnabled)
            tap(s["data.backupCancel"])
            assertTrue(waitFor(s["data.export"]).isEnabled)
        }
    }
    @Test fun queuedAutomaticBackupCanBeTakenOverAndFinishAfterActivityCloses() {
        val jobs = com.zsz.studyassistant.data.BackupJobs
        val provider = com.zsz.studyassistant.data.TestBackupDocumentsProvider
        val preferences = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val manager = androidx.work.WorkManager.getInstance(context)
        val id = com.zsz.studyassistant.data.QuestionIdentity.create(1700000400000L)
        val dao = com.zsz.studyassistant.data.AppDatabase.get(context).questionDao()
        val gate = CountDownLatch(1)
        manager.cancelUniqueWork("backup-v4-on-open").result.get()
        provider.reset(context)
        preferences.edit().clear().putString("auto_backup_tree", "content://${context.packageName}.fixture.backups/tree/root").commit()
        kotlinx.coroutines.runBlocking { dao.insert(com.zsz.studyassistant.data.Question(id, "background fixture", "answer", 1700000400000L)) }
        try {
            jobs.onAppOpened(context)
            val previous = preferences.getString("backup_job_work_id", null)
            provider.writeGate = gate
            launch(openBackup = true).use { tap(s["data.autoBackup.now"]) }
            val immediate = preferences.getString("backup_job_work_id", null)
            assertNotEquals(previous, immediate)
            assertFalse(preferences.getBoolean("backup_job_automatic", true))
            assertFalse("Worker must wait until the activity has closed", jobs.status(context).phaseKey == "data.backupJob.success")
            gate.countDown()
            val deadline = android.os.SystemClock.uptimeMillis() + 20000
            while (jobs.status(context).active && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(80)
            assertEquals("data.backupJob.success", jobs.status(context).phaseKey)
            assertEquals(1, com.zsz.studyassistant.data.BackupRetention.folderSummary(context, com.zsz.studyassistant.data.AutoBackup.treeUri(context)!!).manual)
        } finally {
            gate.countDown(); provider.writeGate = null
            manager.cancelUniqueWork("backup-v4-on-open").result.get()
            kotlinx.coroutines.runBlocking { dao.deleteById(id) }
            preferences.edit().clear().commit()
        }
    }
    @Test fun englishStatisticsUseEnglishMasteryLabels() {
        launch().use {
            tap("Stats")
            waitFor("Not started");waitFor("Learning");waitFor("Mastered")
        }
    }
    @Test fun renderedNumericListsPreserveNonSequentialSubquestionNumbers() {
        val ready=CountDownLatch(1);val evaluated=CountDownLatch(1)
        var web: WebView?=null;var result: String?=null
        instrumentation.runOnMainSync {
            web=WebView(context).apply {
                settings.javaScriptEnabled=true
                webViewClient=object:WebViewClient() {
                    override fun onPageFinished(view:WebView,url:String) { ready.countDown() }
                }
                loadUrl("file:///android_asset/conversation_render.html")
            }
        }
        try {
            assertTrue("Renderer did not load",ready.await(10,TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                web!!.evaluateJavascript("""(function(){const d=document.createElement('div');d.innerHTML=mdToHtml('3. third\n5. fifth');const ol=d.querySelector('ol');let n=ol.start;return Array.from(ol.children).map(li=>{if(li.hasAttribute('value'))n=li.value;return n++;}).join(',');})()""") { result=it;evaluated.countDown() }
            }
            assertTrue(evaluated.await(5,TimeUnit.SECONDS))
            assertEquals("\"3,5\"",result)
        } finally { instrumentation.runOnMainSync { web?.destroy() } }
    }
}
