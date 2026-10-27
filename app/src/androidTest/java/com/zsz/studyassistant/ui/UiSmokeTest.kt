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
    @Test fun updatePageRestoresWaitingAndCompletedTasksAfterLeaving() = kotlinx.coroutines.runBlocking {
        val backend = com.zsz.studyassistant.data.UpdateDownloadsIntegrationTest.FakeTransfers()
        val release = com.zsz.studyassistant.data.UpdateChecker.ReleaseInfo("v9.0", "9.0", "synthetic", "fixture.apk",
            "https://github.com/ShizhenZhou/StudyAssistant/releases/download/v9.0/fixture.apk", "a".repeat(64), 7, 1234)
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
            launch().use { scenario ->
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
