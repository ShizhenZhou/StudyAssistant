package com.zsz.studyassistant.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class UpdateDownloadsIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>().also {
        check(it.packageName == "com.zsz.studyassistant.integration")
    }
    private val backend = FakeTransfers()
    private val rel = UpdateChecker.ReleaseInfo("v9.0", "9.0", "synthetic", "fixture.apk",
        "https://github.com/ShizhenZhou/StudyAssistant/releases/download/v9.0/fixture.apk", "a".repeat(64), 7, 1234, minSdk = 28)
    private fun repo(valid: Boolean = true) = UpdateDownloads(context, backend) { file, _ ->
        UpdateChecker.VerifyResult(valid && file.readText() == "fixture", valid, valid, null)
    }
    @Before fun prepare() = runBlocking { repo().cancel() }
    @After fun cleanup() = runBlocking { repo().cancel() }

    @Test fun betaChannelSurvivesRecreationAndSkippingItDoesNotSkipStable() = runBlocking {
        val beta = rel.copy(channel=UpdateChecker.Channel.BETA)
        try {
            repo().start(beta)
            assertEquals(UpdateChecker.Channel.BETA,repo().snapshot()!!.release.channel)
            UpdateChecker.skipVersion(context,beta.version,UpdateChecker.Channel.BETA)
            assertTrue(UpdateChecker.isSkipped(context,beta))
            assertFalse(UpdateChecker.isSkipped(context,rel))
            val prefs=context.getSharedPreferences("update_download",Context.MODE_PRIVATE)
            val old=org.json.JSONObject(prefs.getString("session",null)!!);old.remove("channel")
            prefs.edit().putString("session",old.toString()).commit()
            assertEquals(UpdateChecker.Channel.STABLE,repo().snapshot()!!.release.channel)
        } finally { UpdateChecker.clearSkippedVersion(context,UpdateChecker.Channel.BETA) }
    }
    @Test fun incompatibleOrUnknownReleaseNeverEnqueuesBytes() = runBlocking {
        assertEquals("update.incompatible", repo().start(rel.copy(minSdk = android.os.Build.VERSION.SDK_INT + 1)).errorKey)
        assertEquals("update.compatibilityUnknown", repo().start(rel.copy(minSdk = null)).errorKey)
        assertEquals(0, backend.enqueues)
        assertNull(repo().snapshot())
    }
    @Test fun restoredTransferWithoutCompatibilityIsStopped() = runBlocking {
        repo().start(rel)
        val preferences = context.getSharedPreferences("update_download", Context.MODE_PRIVATE)
        val obj = org.json.JSONObject(preferences.getString("session", null)!!)
        obj.remove("minSdk") // A task saved by an older app did not establish compatibility.
        preferences.edit().putString("session", obj.toString()).commit()
        val result = repo().snapshot()!!
        assertEquals(UpdateDownloads.Phase.FAILED, result.phase)
        assertEquals("update.compatibilityUnknown", result.errorKey)
        assertNull(backend.current)
        assertEquals(1, backend.enqueues)
    }
    @Test fun directInstallOfRestoredReadyFileRechecksCompatibility() = runBlocking {
        repo().start(rel); backend.file!!.writeText("fixture")
        backend.current = backend.current!!.copy(status = UpdateDownloads.TransferPhase.SUCCESS, bytes = 7)
        val ready = repo().snapshot()!!
        val preferences = context.getSharedPreferences("update_download", Context.MODE_PRIVATE)
        val obj = org.json.JSONObject(preferences.getString("session", null)!!)
            .put("minSdk", android.os.Build.VERSION.SDK_INT + 1)
        preferences.edit().putString("session", obj.toString()).commit()
        val result = repo().forInstall()!!
        assertEquals(UpdateDownloads.Phase.FAILED, result.phase)
        assertEquals("update.incompatible", result.errorKey)
        assertNull(result.file)
        assertFalse(ready.file!!.exists())
    }

    @Test fun oneFailedObservationDoesNotTerminateOrDuplicateTheDownload() = runBlocking {
        val old = repo().start(rel, appNotification = true)
        backend.throwQueries = 1
        val transient = repo().snapshot()!!
        assertEquals(UpdateDownloads.Phase.WAITING, transient.phase)
        assertTrue(transient.appNotification)
        assertEquals("update.downloadUnavailable", transient.errorKey)
        val recovered = repo().snapshot()!!
        assertEquals(UpdateDownloads.Phase.DOWNLOADING, recovered.phase)
        assertEquals(old.token, recovered.token)
        assertEquals(1, backend.enqueues)
    }
    @Test fun notificationHandshakeSelectsExactlyOneNotificationOwner() = runBlocking {
        val rejected = UpdateDownloadMonitor.Request()
        rejected.ready.complete(false)
        assertFalse(repo().enqueue(rel, rejected).await().appNotification)
        assertFalse(backend.appNotification)
        repo().cancel()
        val accepted = UpdateDownloadMonitor.Request()
        accepted.ready.complete(true)
        val task = repo().enqueue(rel, accepted).await()
        assertTrue(task.appNotification)
        assertTrue(backend.appNotification)
        assertTrue(repo().snapshot()!!.appNotification)
    }
    @Test fun rejectedHiddenSystemNotificationFallsBackWithoutDuplicateTransfer() = runBlocking {
        backend.rejectAppNotification = true
        val accepted = UpdateDownloadMonitor.Request().also { it.ready.complete(true) }
        val task = repo().enqueue(rel, accepted).await()
        assertFalse(task.appNotification)
        assertFalse(backend.appNotification)
        assertEquals(1, backend.enqueues)
    }
    @Test fun oldNativeTaskIsReusedWithoutAnExtraAppNotification() = runBlocking {
        val old = repo().start(rel)
        val accepted = UpdateDownloadMonitor.Request().also { it.ready.complete(true) }
        val same = repo().enqueue(rel, accepted).await()
        assertEquals(old.token, same.token)
        assertFalse(same.appNotification)
        assertEquals(1, backend.enqueues)
    }
    @Test fun disappearingUiAwaitDoesNotCancelTheEnqueueHandoff() = runBlocking {
        val request = repo().enqueue(rel)
        val ui = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job() + kotlinx.coroutines.Dispatchers.Default)
        val waiting = ui.launch { request.await() }
        waiting.cancel(); waiting.join()
        val task = kotlinx.coroutines.withTimeout(3000) { request.await() }
        assertEquals(task.token, repo().snapshot()!!.token)
        assertEquals(1, backend.enqueues)
        ui.cancel()
    }
    @Test fun taskSurvivesRepositoryRecreationAndDuplicateStartKeepsProgress() = runBlocking {
        val first = repo().start(rel)
        backend.current = backend.current!!.copy(status = UpdateDownloads.TransferPhase.WAITING, bytes = 3)
        val recreated = repo().snapshot()!!
        assertEquals(first.token, recreated.token)
        assertEquals(42, recreated.progress)
        assertEquals(UpdateDownloads.Phase.WAITING, recreated.phase)
        assertEquals(first.token, repo().start(rel).token)
        assertEquals(1, backend.enqueues)
    }
    @Test fun pendingIdRecoveryWorksBeforeSystemAssignsTheLocalFile() = runBlocking {
        val first = repo().start(rel)
        val preferences = context.getSharedPreferences("update_download", Context.MODE_PRIVATE)
        val obj = org.json.JSONObject(preferences.getString("session", null)!!).put("id", -1)
        preferences.edit().putString("session", obj.toString()).commit()
        backend.current = backend.current!!.copy(localUri = null, status = UpdateDownloads.TransferPhase.QUEUED)
        val restored = repo().snapshot()!!
        assertEquals(first.token, restored.token)
        assertEquals(UpdateDownloads.Phase.DOWNLOADING, restored.phase)
        assertEquals(first.token, repo().start(rel).token)
        assertEquals(1, backend.enqueues)
    }
    @Test fun oldCompletionCleanupCannotCancelANewerTransfer() = runBlocking {
        val old = repo().start(rel)
        val newer = repo().start(rel.copy(assetUploadedAt = 4321))
        repo().cancel(old.token)
        assertEquals(newer.token, repo().snapshot()!!.token)
        assertEquals(2, backend.enqueues)
    }
    @Test fun realPackageVerifierRejectsUnsignedSyntheticBytes() = runBlocking {
        val productionVerifier = UpdateDownloads(context, backend)
        productionVerifier.start(rel)
        backend.file!!.writeText("fixture")
        backend.current = backend.current!!.copy(status = UpdateDownloads.TransferPhase.SUCCESS, bytes = 7)
        val result = productionVerifier.snapshot()!!
        assertEquals(UpdateDownloads.Phase.FAILED, result.phase)
        assertEquals("update.verifyFailed", result.errorKey)
        assertNull(result.file)
    }
    @Test fun completionIsVerifiedAndReadySurvivesRecreation() = runBlocking {
        repo().start(rel); backend.file!!.writeText("fixture")
        backend.current = backend.current!!.copy(status = UpdateDownloads.TransferPhase.SUCCESS, bytes = 7)
        val done = repo().snapshot()!!
        assertEquals(UpdateDownloads.Phase.READY, done.phase)
        assertEquals("fixture", done.file!!.readText())
        assertEquals(done.token, repo().snapshot()!!.token)
        assertEquals(UpdateDownloads.Phase.READY, repo().forInstall()!!.phase)
    }
    @Test fun unverifiedOrChangedCacheCannotBeInstalled() = runBlocking {
        repo().start(rel); backend.file!!.writeText("fixture")
        backend.current = backend.current!!.copy(status = UpdateDownloads.TransferPhase.SUCCESS, bytes = 7)
        assertEquals(UpdateDownloads.Phase.FAILED, repo(false).snapshot()!!.phase)
        assertNull(repo().forInstall()!!.file)
        repo().start(rel); backend.file!!.writeText("fixture")
        backend.current = backend.current!!.copy(status = UpdateDownloads.TransferPhase.SUCCESS, bytes = 7)
        val ready = repo().snapshot()!!; ready.file!!.writeText("changed")
        assertEquals(UpdateDownloads.Phase.FAILED, repo().forInstall()!!.phase)
    }
    @Test fun sameVersionReplacementGetsANewTaskAndCancelDoesNotDeleteOtherFiles() = runBlocking {
        val first = repo().start(rel)
        val other = File(context.cacheDir, "update-test-unrelated.txt").apply { writeText("keep") }
        try {
            val next = repo().start(rel.copy(assetSha256 = "b".repeat(64), assetUploadedAt = 2345))
            assertNotEquals(first.token, next.token)
            assertEquals(2, backend.enqueues)
            repo().cancel(); assertNull(repo().snapshot()); assertEquals("keep", other.readText())
        } finally { other.delete() }
    }
    @Test fun nativeFailureOrMissingCacheProvidesRetryWithoutPretendingToResume() = runBlocking {
        repo().start(rel); backend.current = backend.current!!.copy(status = UpdateDownloads.TransferPhase.FAILED)
        assertEquals(UpdateDownloads.Phase.FAILED, repo().snapshot()!!.phase)
        assertNotNull(repo().start(rel))
        assertEquals(2, backend.enqueues)
        backend.file!!.writeText("fixture");backend.current = backend.current!!.copy(status = UpdateDownloads.TransferPhase.SUCCESS, bytes = 7)
        val ready=repo().snapshot()!!;ready.file!!.delete()
        assertEquals(UpdateDownloads.Phase.FAILED, repo().snapshot()!!.phase)
    }
    internal class FakeTransfers : UpdateDownloads.Backend {
        var current: UpdateDownloads.Transfer? = null
        var file: File? = null
        var enqueues = 0
        var appNotification = false
        var rejectAppNotification = false
        var throwQueries = 0
        var queryEntered: java.util.concurrent.CountDownLatch? = null
        var queryRelease: java.util.concurrent.CountDownLatch? = null
        override fun enqueue(release: UpdateChecker.ReleaseInfo, destination: File, appNotification: Boolean): Long {
            if (appNotification && rejectAppNotification) throw SecurityException("synthetic native visibility rejection")
            this.appNotification = appNotification
            enqueues++;file=destination;destination.writeText("fix")
            current=UpdateDownloads.Transfer(enqueues.toLong(), release.assetUrl, destination.toURI().toString(),
                UpdateDownloads.TransferPhase.RUNNING, 3, 7, destination.nameWithoutExtension)
            return enqueues.toLong()
        }
        override fun query(id: Long): UpdateDownloads.Transfer? {
            if (throwQueries > 0) { throwQueries--; throw java.io.IOException("synthetic temporary query failure") }
            queryEntered?.countDown()
            queryRelease?.await(8, java.util.concurrent.TimeUnit.SECONDS)
            return current?.takeIf { it.id==id }
        }
        override fun find(url: String, destination: File) = current?.takeIf { it.url==url && it.token==destination.nameWithoutExtension }
        override fun remove(id: Long) { if(current?.id==id) { current=null;file?.delete() } }
    }
}
