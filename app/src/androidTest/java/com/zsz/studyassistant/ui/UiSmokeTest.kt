package com.zsz.studyassistant.ui

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
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Compose navigation and WebView rendering, in the no-network synthetic package only. */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val s get()=stringsFor(UiLang.EN)
    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if(node==null) return null
        if(node.text?.toString()==text || node.contentDescription?.toString()==text) return node
        for(i in 0 until node.childCount) find(node.getChild(i),text)?.let { return it }
        return null
    }
    private fun waitFor(text: String): AccessibilityNodeInfo {
        val deadline=android.os.SystemClock.uptimeMillis()+8000
        while(android.os.SystemClock.uptimeMillis()<deadline) {
            find(instrumentation.uiAutomation.rootInActiveWindow,text)?.let { return it }
            Thread.sleep(80)
        }
        error("Missing UI text: $text")
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
                return
            }
            val offscreen = clickable(root, text, visibleOnly = false)
            if (offscreen != null && !offscreen.isVisibleToUser && !revealRequested) {
                revealRequested = true
                offscreen.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            } else if (offscreen == null || !offscreen.isVisibleToUser) {
                val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                if (scrollable(root)?.performAction(action) != true) forward = !forward
            }
            Thread.sleep(120)
        }
        error("Not clickable: $text")
    }
    private fun launch(openBackup: Boolean = false): ActivityScenario<MainActivity> {
        check(context.packageName=="com.zsz.studyassistant.integration")
        UiLangStore.save(context,UiLang.EN)
        // These journeys cover navigation, independently of the upgrade-prompt policy tests.
        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit()
            .putInt("data_upgrade_seen_schema", com.zsz.studyassistant.data.AppDatabase.SCHEMA_VERSION)
            .putInt("data_upgrade_prompted_schema", com.zsz.studyassistant.data.AppDatabase.SCHEMA_VERSION).commit()
        val scenario=ActivityScenario.launch<MainActivity>(android.content.Intent(context, MainActivity::class.java)
            .putExtra(AppNav.OPEN_BACKUP_EXTRA, openBackup))
        val deadline=android.os.SystemClock.uptimeMillis()+8000
        while(android.os.SystemClock.uptimeMillis()<deadline) {
            val root=instrumentation.uiAutomation.rootInActiveWindow
            if(find(root,"Later")!=null) { tap("Later");break }
            if(find(root,"Settings")!=null) break
            Thread.sleep(80)
        }
        return scenario
    }
    @Test fun settingsChildrenReturnToTheirOwnParents() {
        launch().use { scenario ->
            tap("Settings")
            tap(s["settings.aiManage"])
            tap(s["settings.customPrompt"])
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor(s["settings.aiManage"])
            waitFor(s["settings.aiCropPage"])
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            tap(s["settings.data"])
            tap(s["data.backup"])
            waitFor(s["data.manualBackup"])
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor(s["data.crash"])
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor(s["settings.aiManage"])
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
