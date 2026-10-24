package com.zsz.studyassistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zsz.studyassistant.MainViewModel

/** Failed saves remain actionable after the temporary snackbar disappears. No normal-save UI. */
@Composable
fun SaveRecoveryDialogs(vm: MainViewModel) {
    val s = LocalStrings.current
    val feedback = LocalAppFeedback.current
    val busy = vm.saveOperations > 0 || vm.retryingHistoryWrites
    val pending = vm.pendingSaveCount > 0 || vm.saveJournalIssue
    var showDiscard by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportPendingSaves(uri)
    }
    LaunchedEffect(pending, busy, vm.pendingSaveCount, vm.saveJournalIssue) {
        if (!pending || busy) feedback.dismiss("save")
        else feedback.show(
            if (vm.saveJournalIssue) s["save.issue"] else s.format("save.pending", "n" to "${vm.pendingSaveCount}"),
            isError = true, source = "save", actionLabel = s["feedback.handle"],
            onAction = { feedback.saveRecoveryRequested = true }
        )
    }
    LaunchedEffect(vm.saveFeedback) {
        vm.saveFeedback?.let {
            feedback.saveRecoveryRequested = false
            feedback.show(it, isError = vm.saveFeedbackIsError, source = "saveExport")
            vm.clearSaveFeedback()
        }
    }
    if (feedback.saveRecoveryRequested) AlertDialog(
        onDismissRequest = { feedback.saveRecoveryRequested = false },
        title = { Text(s["feedback.unsaved"]) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!pending) Text(s["feedback.noPending"])
                else {
                    Text(s.format("save.pending", "n" to "${vm.pendingSaveCount}"))
                    Text(s[if (vm.saveRecoveryReady) "save.recoverable" else "save.keepOpen"])
                    if (vm.saveJournalIssue) Text(s["save.issue"], color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    TextButton(enabled = !busy, onClick = { vm.retryFailedHistoryWrites() }) {
                        Text(s[if (busy) "history.saveRetrying" else "history.saveRetry"])
                    }
                    TextButton(enabled = !busy, onClick = {
                        export.launch("study-assistant-unsaved-${System.currentTimeMillis()}.json")
                    }) { Text(s["save.export"]) }
                    Text(s["save.exportNotice"], style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy, onClick = { showDiscard = true }) { Text(s["save.discard"]) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { feedback.saveRecoveryRequested = false }) { Text(s["common.ok"]) } }
    )
    if (showDiscard) AlertDialog(
        onDismissRequest = { showDiscard = false },
        title = { Text(s["save.discard"]) },
        text = { Text(s["save.discardConfirm"]) },
        confirmButton = { TextButton(enabled = !busy, onClick = { showDiscard = false; vm.discardPendingSaves() }) { Text(s["save.discard"]) } },
        dismissButton = { TextButton(onClick = { showDiscard = false }) { Text(s["common.cancel"]) } }
    )
}
