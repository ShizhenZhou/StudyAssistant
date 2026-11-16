package com.zsz.studyassistant.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zsz.studyassistant.data.CrashLogger
import com.zsz.studyassistant.data.CrashReports
import com.zsz.studyassistant.data.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CrashReportPage() {
    val context = LocalContext.current
    val s = crashReportStrings(LocalUiLang.current)
    val feedback = LocalAppFeedback.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var hasCrash by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var showLogs by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(revision) {
        report = null
        failed = false
        try {
            val result = withContext(Dispatchers.IO) {
                (CrashLogger.count(context) > 0) to CrashReports.preview(context)
            }
            hasCrash = result.first
            report = result.second
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    var exporting by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val snapshot = report
        if (uri != null) scope.launch {
            exporting = true
            try {
                withContext(Dispatchers.IO) {
                    // The activity may have been recreated while the document picker was open.
                    val text = snapshot ?: CrashReports.preview(context)
                    checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    }
                }
                feedback.show(s["exported"])
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { feedback.show(s["exportFailed"], isError = true) }
            finally { exporting = false }
        }
    }
    Text(s["privacy"], style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(12.dp))
    if (report != null && !hasCrash) Text(s["empty"], style = MaterialTheme.typography.bodySmall)
    Text(s["preview"], style = MaterialTheme.typography.titleMedium)
    Card(Modifier.fillMaxWidth(), shape = smoothShape(14.dp)) {
        SelectionContainer {
            Text(report ?: s[if (failed) "readFailed" else "loading"],
                Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = report != null, onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("StudyAssistant crash report", report))
            feedback.show(s["copied"])
        }) { Text(s["copy"]) }
        TextButton(enabled = report != null && !exporting, onClick = {
            export.launch(CrashLogger.exportFileName().replace("-crash-", "-report-"))
        }) { Text(s["export"]) }
    }
    Button(onClick = { openUrl(context, UpdateChecker.REPO_URL + "/issues/new/choose") }) {
        Text(s["github"])
    }
    Spacer(Modifier.height(16.dp))
    TextButton(onClick = { showLogs = !showLogs }) {
        Text(settingsStructureStrings(LocalUiLang.current)[if (showLogs) "hideLogs" else "logs"])
    }
    if (showLogs) CrashLogSettings(onCleared = { report = null; revision++ })
}

@Composable
fun CrashReminder(enabled: Boolean, onOpenReport: () -> Unit) {
    val context = LocalContext.current
    val s = crashReportStrings(LocalUiLang.current)
    var pending by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        pending = withContext(Dispatchers.IO) { CrashReports.pending(context) }
    }
    val id = pending
    if (enabled && id != null) {
        // Persist once it is displayed, so returning from background or rotating never repeats it.
        LaunchedEffect(id) {
            withFrameNanos { }
            CrashReports.acknowledge(context, id)
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(s["reminderTitle"]) }, text = { Text(s["reminderBody"]) },
            confirmButton = { TextButton(onClick = { pending = null; onOpenReport() }) { Text(s["review"]) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(s["later"]) } }
        )
    }
}
