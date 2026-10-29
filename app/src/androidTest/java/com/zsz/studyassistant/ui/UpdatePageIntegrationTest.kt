package com.zsz.studyassistant.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Injected synthetic transfers; no home startup, API-key dialog or system download backend. */
@RunWith(AndroidJUnit4::class)
class UpdatePageIntegrationTest {
    @Test fun installerPermissionQueryFailureDoesNotAuthorizeInstallation() {
        val unavailable = object : android.content.ContextWrapper(context) {
            override fun getPackageManager(): android.content.pm.PackageManager =
                throw IllegalStateException("synthetic package-manager failure")
        }
        assertFalse(canInstallApk(unavailable))
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val s get() = stringsFor(UiLang.EN)
    @Before fun syntheticOnly() {
        check(context.packageName == "com.zsz.studyassistant.integration")
    }
    private fun launchPage() = ActivityScenario.launch<UpdatePageTestActivity>(
        android.content.Intent(context, UpdatePageTestActivity::class.java))
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
    @Test fun updatePageRestoresWaitingAndCompletedTasksAfterLeaving() = kotlinx.coroutines.runBlocking {
        val backend = com.zsz.studyassistant.data.UpdateDownloadsIntegrationTest.FakeTransfers()
        val release = com.zsz.studyassistant.data.UpdateChecker.ReleaseInfo("v9.0", "9.0", "synthetic", "fixture.apk",
            "https://github.com/ShizhenZhou/StudyAssistant/releases/download/v9.0/fixture.apk", "a".repeat(64), 7, 1234, minSdk = 28)
        fun repository() = com.zsz.studyassistant.data.UpdateDownloads(context, backend) { file, _ ->
            val ok = file.readText() == "fixture"
            com.zsz.studyassistant.data.UpdateChecker.VerifyResult(ok, ok, ok, null)
        }
        val store = repository()
        val entered = java.util.concurrent.CountDownLatch(1)
        val releaseQuery = java.util.concurrent.CountDownLatch(1)
        try {
            store.cancel(); store.start(release)
            backend.queryEntered = entered; backend.queryRelease = releaseQuery
            launchPage().use { scenario ->
                fun showPage(repo: com.zsz.studyassistant.data.UpdateDownloads) {
                    scenario.onActivity { activity -> activity.setContent {
                        androidx.compose.runtime.CompositionLocalProvider(LocalStrings provides s, LocalUiLang provides UiLang.EN) {
                            androidx.compose.material3.MaterialTheme {
                                androidx.compose.foundation.layout.Column { UpdatePage(repo) }
                            }
                        }
                    } }
                }
                showPage(store)
                assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
                waitFor(s["update.restoring"])
                assertNull(clickable(instrumentation.uiAutomation.rootInActiveWindow, s["update.check"]))
                scenario.onActivity { it.setContent { androidx.compose.material3.Text("Away from update page") } }
                backend.current = backend.current!!.copy(status = com.zsz.studyassistant.data.UpdateDownloads.TransferPhase.WAITING)
                releaseQuery.countDown()
                assertEquals(1, backend.enqueues)
                showPage(repository())
                waitFor(s["update.waiting"])
                backend.file!!.writeText("fixture")
                backend.current = backend.current!!.copy(status = com.zsz.studyassistant.data.UpdateDownloads.TransferPhase.SUCCESS, bytes = 7)
                waitFor(s["update.verified"])
                assertEquals(1, backend.enqueues)
                assertNotNull(store.forInstall()!!.file)
            }
        } finally { releaseQuery.countDown(); backend.queryRelease=null; store.cancel() }
    }

    @Test fun optionalBetaAndStableHaveIndependentNotesAndDownloadTargets()=kotlinx.coroutines.runBlocking {
        val backend=com.zsz.studyassistant.data.UpdateDownloadsIntegrationTest.FakeTransfers()
        val stable=com.zsz.studyassistant.data.UpdateChecker.ReleaseInfo("v99.1.0","99.1.0","Stable fixture notes","stable.apk",
            "https://github.com/ShizhenZhou/StudyAssistant/releases/download/v99.1.0/stable.apk","a".repeat(64),7,minSdk=36,versionCode=990)
        val beta=stable.copy(tagName="v99.2.0",version="99.2.0",notes="Beta fixture notes",assetName="beta.apk",
            assetUrl="https://github.com/ShizhenZhou/StudyAssistant/releases/download/v99.2.0/beta.apk",versionCode=991,
            channel=com.zsz.studyassistant.data.UpdateChecker.Channel.BETA)
        val store=com.zsz.studyassistant.data.UpdateDownloads(context,backend) { _,_ ->
            com.zsz.studyassistant.data.UpdateChecker.VerifyResult(true,true,true,null) }
        val previousFactory=com.zsz.studyassistant.data.UpdateDownloadMonitor.downloadsFactory
        com.zsz.studyassistant.data.UpdateDownloadMonitor.downloadsFactory={ store }
        try {
            store.cancel()
            launchPage().use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    androidx.compose.runtime.CompositionLocalProvider(LocalStrings provides s,LocalUiLang provides UiLang.EN) {
                        androidx.compose.material3.MaterialTheme {
                            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                                UpdatePage(store) { com.zsz.studyassistant.data.UpdateChecker.AvailableReleases(stable,beta) }
                            }
                        }
                    }
                } }
                waitFor(s["update.check"])
                clickable(instrumentation.uiAutomation.rootInActiveWindow,s["update.check"])!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                waitFor("Beta fixture notes");waitFor("Stable fixture notes");waitFor(s["update.betaWarning"])
                assertEquals("Checking must not start an optional download",0,backend.enqueues)
                val downloadBounds = android.graphics.Rect()
                val skipBounds = android.graphics.Rect()
                clickable(instrumentation.uiAutomation.rootInActiveWindow,s["update.downloadBeta"],visibleOnly=false)!!
                    .getBoundsInScreen(downloadBounds)
                clickable(instrumentation.uiAutomation.rootInActiveWindow,s["update.skip"],visibleOnly=false)!!
                    .getBoundsInScreen(skipBounds)
                assertTrue("Update actions need separate vertical touch areas: $downloadBounds / $skipBounds",
                    skipBounds.top > downloadBounds.bottom)
                val screenshot=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { java.io.File(context.filesDir,"ui-update-channels.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                } } finally { screenshot.recycle() }
                val texts=mutableListOf<String>()
                fun visit(node: AccessibilityNodeInfo?) {
                    if (node==null) return
                    node.text?.toString()?.let { texts+=it }
                    for(i in 0 until node.childCount) visit(node.getChild(i))
                }
                visit(instrumentation.uiAutomation.rootInActiveWindow)
                assertTrue(texts.indexOf("Beta fixture notes") < texts.indexOf("Stable fixture notes"))
                val button=clickable(instrumentation.uiAutomation.rootInActiveWindow,s["update.downloadStable"],visibleOnly=false)!!
                button.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                instrumentation.waitForIdleSync()
                clickable(instrumentation.uiAutomation.rootInActiveWindow,s["update.downloadStable"],visibleOnly=false)!!
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val deadline=android.os.SystemClock.uptimeMillis()+8000
                while(backend.enqueues==0 && android.os.SystemClock.uptimeMillis()<deadline) Thread.sleep(40)
                assertEquals(1,backend.enqueues)
                assertEquals(stable.assetUrl,store.snapshot()!!.release.assetUrl)
                assertEquals(com.zsz.studyassistant.data.UpdateChecker.Channel.STABLE,store.snapshot()!!.release.channel)
            }
        } finally {
            context.stopService(android.content.Intent(context,com.zsz.studyassistant.data.UpdateDownloadMonitor::class.java))
            store.cancel()
            com.zsz.studyassistant.data.UpdateDownloadMonitor.downloadsFactory=previousFactory
        }
    }

    @Test fun installedLegacyDownloadIsClearedWithoutCheckingOnline() = restoredUnknownCompatibility(true)

    @Test fun futureLegacyDownloadStillRequiresCompatibilityCheck() = restoredUnknownCompatibility(false)

    private fun restoredUnknownCompatibility(installed: Boolean) = kotlinx.coroutines.runBlocking {
        val backend = com.zsz.studyassistant.data.UpdateDownloadsIntegrationTest.FakeTransfers()
        val checker = com.zsz.studyassistant.data.UpdateChecker
        val version = if (installed) checker.installedVersion(context) else "99.0.0"
        val release = com.zsz.studyassistant.data.UpdateChecker.ReleaseInfo("v$version", version, "synthetic", "fixture.apk",
            "https://github.com/ShizhenZhou/StudyAssistant/releases/download/v$version/fixture.apk",
            checker.installedApkSha256(context), 7, minSdk = 28)
        val store = com.zsz.studyassistant.data.UpdateDownloads(context, backend)
        val prefs = context.getSharedPreferences("update_download", android.content.Context.MODE_PRIVATE)
        try {
            store.cancel(); store.start(release)
            // Old clients retain a download record with no compatibility metadata after installation.
            val legacy = org.json.JSONObject(prefs.getString("session", null)!!).put("ready", true)
            legacy.remove("minSdk")
            prefs.edit().putString("session", legacy.toString()).commit()
            assertEquals("update.compatibilityUnknown", store.snapshot()!!.errorKey)
            // Mount the injected page directly: the normal update route uses the OS backend,
            // which is deliberately unavailable to this no-network synthetic package.
            launchPage().use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    androidx.compose.runtime.CompositionLocalProvider(LocalStrings provides s, LocalUiLang provides UiLang.EN) {
                        androidx.compose.material3.MaterialTheme {
                            androidx.compose.foundation.layout.Column { UpdatePage(store) }
                        }
                    }
                } }
                if (installed) {
                    val deadline = android.os.SystemClock.uptimeMillis() + 8000
                    while (prefs.contains("session") && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(80)
                    assertFalse("Installed legacy task must be forgotten without a network check", prefs.contains("session"))
                    waitUntilAbsent(s["update.compatibilityUnknown"])
                    assertNotNull(clickable(instrumentation.uiAutomation.rootInActiveWindow, s["update.check"]))
                } else {
                    waitFor(s["update.compatibilityUnknown"])
                    assertTrue(prefs.contains("session"))
                    assertNull(backend.current)
                }
                assertEquals(1, backend.enqueues)
            }
        } finally { store.cancel() }
    }

}
