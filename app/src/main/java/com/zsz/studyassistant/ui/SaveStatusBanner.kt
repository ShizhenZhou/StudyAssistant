package com.zsz.studyassistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zsz.studyassistant.MainViewModel

@Composable
fun SaveStatusBanner(vm: MainViewModel) {
    val s = LocalStrings.current
    val busy = vm.saveOperations > 0 || vm.retryingHistoryWrites
    val pending = vm.pendingSaveCount > 0 || vm.saveJournalIssue
    var showDiscard by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportPendingSaves(uri)
    }
    if (showDiscard) AlertDialog(
        onDismissRequest = { showDiscard = false },
        title = { Text(s["save.discard"]) },
        text = { Text(s["save.discardConfirm"]) },
        confirmButton = { TextButton(enabled = !busy, onClick = { showDiscard = false; vm.discardPendingSaves() }) { Text(s["save.discard"]) } },
        dismissButton = { TextButton(onClick = { showDiscard = false }) { Text(s["common.cancel"]) } }
    )
    if (!pending && !busy) return
    Surface(color = if (pending) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(if (busy) s["save.busy"] else s.format("save.pending", "n" to "${vm.pendingSaveCount}"),
                style = MaterialTheme.typography.bodySmall)
            if (pending) {
                Text(s[if (vm.saveRecoveryReady) "save.recoverable" else "save.keepOpen"], style = MaterialTheme.typography.bodySmall)
                if (vm.saveJournalIssue) Text(s["save.issue"], style = MaterialTheme.typography.bodySmall)
                vm.saveFeedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row {
                    TextButton(enabled = !busy, onClick = { vm.retryFailedHistoryWrites() }) { Text(s["history.saveRetry"]) }
                    TextButton(enabled = !busy, onClick = { export.launch("study-assistant-unsaved-${System.currentTimeMillis()}.json") }) { Text(s["save.export"]) }
                }
                Text(s["save.exportNotice"], style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !busy, onClick = { showDiscard = true }) { Text(s["save.discard"]) }
            }
        }
    }
}
