package com.zsz.studyassistant.ui

import androidx.compose.material3.SnackbarDuration
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real SnackbarHostState behavior, with no Activity, phone, filesystem or production data. */
class AppFeedbackTest {
    @Test fun `undo stays visible when a backup finishes and a failure waits behind it`() = checkFeedback { state ->
        var undone=false
        state.showDeletion("Removed", "Undo") { undone=true }
        state.show("Backup done")
        state.show("Backup failed",isError=true)
        assertEquals("Removed",state.hostState.currentSnackbarData!!.visuals.message)
        state.hostState.currentSnackbarData!!.performAction()
        yield()
        awaitMessage(state,"Backup failed")
        assertTrue(undone)
    }

    private fun checkFeedback(block: suspend CoroutineScope.(AppFeedbackState) -> Unit) = runBlocking {
        val job = Job()
        try { block(AppFeedbackState(CoroutineScope(coroutineContext + job))) }
        finally { job.cancel() }
    }

    private suspend fun awaitMessage(state: AppFeedbackState, message: String) = withTimeout(1000) {
        while (state.hostState.currentSnackbarData?.visuals?.message != message) yield()
    }

    @Test fun `automatic backup failure is concise with a working details action`() = checkFeedback { state ->
        var opened = false
        val strings = stringsFor(UiLang.ZH_CN)
        state.showBackupFailure(strings, automatic = true) { opened = true }
        val data = state.hostState.currentSnackbarData!!
        assertEquals("自动备份失败", data.visuals.message)
        assertEquals("查看详情", data.visuals.actionLabel)
        assertEquals(SnackbarDuration.Long, data.visuals.duration)
        data.performAction()
        yield()
        assertTrue(opened)
    }

    @Test fun `ordinary feedback is short and failures last longer`() = checkFeedback { state ->
        state.show("Backup completed")
        assertEquals(SnackbarDuration.Short, state.hostState.currentSnackbarData!!.visuals.duration)
        state.show("Backup failed", isError = true)
        awaitMessage(state, "Backup failed")
        assertEquals(SnackbarDuration.Long, state.hostState.currentSnackbarData!!.visuals.duration)
        assertTrue((state.hostState.currentSnackbarData!!.visuals as FeedbackVisuals).isError)
    }

    @Test fun `failure replaces success and is not buried by later successes`() = checkFeedback { state ->
        state.show("Backup completed")
        state.show("Write failed", isError = true)
        // Both events arrive before the old snackbar cancellation finishes.
        state.show("Review complete")
        awaitMessage(state, "Write failed")
        val failure = state.hostState.currentSnackbarData!!
        assertEquals("Write failed", failure.visuals.message)
        failure.dismiss()
        yield()
        assertNull(state.hostState.currentSnackbarData)
    }

    @Test fun `same failure is not replayed on recomposition`() = checkFeedback { state ->
        state.show("Write failed", isError = true, source = "save")
        val first = state.hostState.currentSnackbarData
        state.show("Write failed", isError = true, source = "save")
        assertSame(first, state.hostState.currentSnackbarData)
    }

    @Test fun `new failure supersedes old feedback without leaving a queue`() = checkFeedback { state ->
        state.show("First failure", isError = true)
        state.show("Second failure", isError = true)
        awaitMessage(state, "Second failure")
        assertEquals("Second failure", state.hostState.currentSnackbarData!!.visuals.message)
        state.hostState.currentSnackbarData!!.dismiss()
        yield()
        assertNull(state.hostState.currentSnackbarData)
    }

    @Test fun `resolved saves cannot dismiss an unrelated backup failure`() = checkFeedback { state ->
        state.show("Backup failed", isError = true, source = "backup")
        val failure = state.hostState.currentSnackbarData
        state.dismiss("save")
        assertSame(failure, state.hostState.currentSnackbarData)
        state.dismiss("backup")
        yield()
        assertNull(state.hostState.currentSnackbarData)
    }

    @Test fun `recovery action runs once and does not run on ordinary dismissal`() = checkFeedback { state ->
        var actions = 0
        state.show("Write failed", isError = true, actionLabel = "Resolve", onAction = { actions++ })
        val data = state.hostState.currentSnackbarData!!
        data.performAction()
        data.performAction()
        yield()
        assertEquals(1, actions)
        state.show("Write failed again", isError = true, actionLabel = "Resolve", onAction = { actions++ })
        state.hostState.currentSnackbarData!!.dismiss()
        yield()
        assertEquals(1, actions)
    }
}
