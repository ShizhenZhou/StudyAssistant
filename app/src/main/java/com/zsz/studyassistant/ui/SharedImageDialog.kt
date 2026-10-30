package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel

@Composable
internal fun SharedImageDialog(vm: MainViewModel, nav: NavHostController) {
    val s = LocalStrings.current
    val inbox = vm.sharedImages
    val blocked = vm.busy || vm.gradeBusy || vm.similarBusy || vm.reviewBusy || vm.dataBusy || vm.pendingSaveCount > 0 || vm.saveJournalIssue
    fun begin(grade: Boolean) {
        if (blocked || inbox.loading || inbox.files.isEmpty()) return
        vm.startNewQuestion() // Captures existing conversation before replacing its UI state.
        vm.clearCaptureCaches()
        val files = inbox.take()
        vm.startCropFlow(files.map { it.absolutePath }, grade, files.size)
        AppNav.openSharedCrop(nav)
    }
    if (inbox.loading || inbox.files.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { inbox.discard() },
            title = { Text(s["share.title"]) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (inbox.loading) CircularProgressIndicator()
                    else Text(s.format("share.ready", "n" to inbox.files.size.toString()))
                    if (blocked) Text(s["share.wait"], color = MaterialTheme.colorScheme.error)
                    inbox.errorKey?.let { Text(s[it], color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { begin(false) }, enabled = !inbox.loading && !blocked) { Text(s["share.solve"]) }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = { begin(true) }, enabled = !inbox.loading && !blocked) { Text(s["share.grade"]) }
                    TextButton(onClick = { inbox.discard() }) { Text(s["common.cancel"]) }
                }
            }
        )
    } else inbox.errorKey?.let { key ->
        AlertDialog(onDismissRequest = { inbox.dismissError() }, title = { Text(s["share.title"]) },
            text = { Text(s[key]) }, confirmButton = {
                TextButton(onClick = { inbox.dismissError() }) { Text(s["common.ok"]) }
            })
    }
}
