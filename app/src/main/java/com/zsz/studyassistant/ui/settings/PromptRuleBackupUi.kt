package com.zsz.studyassistant.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zsz.studyassistant.data.Category
import com.zsz.studyassistant.data.CustomPromptRule
import com.zsz.studyassistant.data.PromptBackupException
import com.zsz.studyassistant.data.PromptRuleBackup
import com.zsz.studyassistant.data.PromptRuleBackupCodec
import com.zsz.studyassistant.data.PromptRuleValidationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owned by the ViewModel's editor: recreation keeps imported drafts, picker payloads and I/O results. */
class PromptRuleBackupController(context: Context, private val scope: CoroutineScope) {
    private val resolver = context.applicationContext.contentResolver
    private var exportBytes: ByteArray? = null
    var busy by mutableStateOf(false)
        private set
    var exportRequested by mutableStateOf(false)
        private set
    var awaitingExportDestination by mutableStateOf(false)
        private set
    var errorKey by mutableStateOf<String?>(null)
        private set
    var messageKey by mutableStateOf<String?>(null)
        private set
    internal var preview by mutableStateOf<PromptRuleBackup?>(null)
        private set
    internal var mapping by mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    fun prepareExport(rules: List<CustomPromptRule>, categories: List<Category>) {
        if (busy || awaitingExportDestination) return
        busy = true
        errorKey = null
        messageKey = null
        scope.launch {
            try {
                exportBytes = withContext(Dispatchers.IO) { PromptRuleBackupCodec.export(rules, categories) }
                awaitingExportDestination = true
                exportRequested = true
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { errorKey = error.keyOr("promptBackup.exportFailed") }
            finally { busy = false }
        }
    }

    fun consumeExportRequest() { exportRequested = false }

    fun pickerFailed() {
        exportBytes = null
        awaitingExportDestination = false
        exportRequested = false
        errorKey = "promptBackup.pickerFailed"
    }

    fun exportTo(uri: Uri?) {
        val bytes = exportBytes
        exportBytes = null
        awaitingExportDestination = false
        if (uri == null) return
        if (bytes == null) { errorKey = "promptBackup.exportFailed"; return }
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    require(uri.scheme == "content")
                    checkNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
                    val written = checkNotNull(resolver.openInputStream(uri)).use(PromptRuleBackupCodec::readBounded)
                    check(written.contentEquals(bytes))
                }
                messageKey = "promptBackup.exported"
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { errorKey = error.keyOr("promptBackup.exportFailed") }
            finally { busy = false }
        }
    }

    fun importFrom(uri: Uri?, categories: List<Category>) {
        if (uri == null || busy) return
        busy = true
        errorKey = null
        messageKey = null
        scope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    require(uri.scheme == "content")
                    val bytes = checkNotNull(resolver.openInputStream(uri)).use(PromptRuleBackupCodec::readBounded)
                    PromptRuleBackupCodec.decode(bytes)
                }
                mapping = PromptRuleBackupCodec.suggestMapping(imported, categories)
                preview = imported
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { errorKey = error.keyOr("promptBackup.importFailed") }
            finally { busy = false }
        }
    }

    fun selectSubject(name: String, id: Long) {
        if (busy) return
        mapping = mapping + (name to id)
        errorKey = null
    }

    fun cancelPreview() { if (!busy) { preview = null; mapping = emptyMap(); errorKey = null } }

    fun confirm(editor: SubjectPromptEditor, categories: List<Category>) {
        val imported = preview ?: return
        if (busy || editor.busy || !editor.loaded) return
        errorKey = null
        try {
            // Fresh local IDs are checked at confirmation. A missing subject never broadens the scope.
            val rules = PromptRuleBackupCodec.resolve(imported, mapping, categories)
            editor.appendImported(rules) {
                preview = null
                mapping = emptyMap()
                messageKey = "promptBackup.imported"
            }
        } catch (error: Exception) { errorKey = error.keyOr("promptBackup.importFailed") }
    }

    private fun Exception.keyOr(fallback: String): String = when (this) {
        is PromptBackupException -> key
        is PromptRuleValidationException -> reason.key
        else -> fallback
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PromptRuleBackupActions(editor: SubjectPromptEditor, categories: List<Category>) {
    val s = LocalStrings.current
    val backup = editor.backup
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), backup::exportTo)
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { backup.importFrom(it, categories) }
    LaunchedEffect(backup.exportRequested) {
        if (backup.exportRequested) {
            backup.consumeExportRequest()
            try { exporter.launch("study-assistant-instructions-${System.currentTimeMillis()}.json") }
            catch (_: Exception) { backup.pickerFailed() }
        }
    }
    val available = editor.loaded && !editor.busy && !backup.busy && !backup.awaitingExportDestination
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { backup.prepareExport(editor.rules, categories) }, enabled = available && editor.rules.isNotEmpty()) {
                Text(s["promptBackup.export"])
            }
            OutlinedButton(onClick = {
                try { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                catch (_: Exception) { backup.pickerFailed() }
            }, enabled = available) { Text(s["promptBackup.import"]) }
        }
        Text(s["promptBackup.description"], style = MaterialTheme.typography.bodySmall)
        if (backup.busy) Text(s["promptRules.working"], style = MaterialTheme.typography.bodySmall)
        backup.messageKey?.let { Text(s[it], style = MaterialTheme.typography.bodySmall) }
        backup.errorKey?.let { Text(s[it], color = MaterialTheme.colorScheme.error) }
    }
    backup.preview?.let { preview -> PromptImportPreview(editor, preview, categories) }
}

@Composable
private fun PromptImportPreview(editor: SubjectPromptEditor, preview: PromptRuleBackup, categories: List<Category>) {
    val s = LocalStrings.current
    val backup = editor.backup
    val names = remember(preview) { PromptRuleBackupCodec.sourceNames(preview) }
    val currentIds = categories.map { it.id }.filter { it > 0 }.toSet()
    val mapped = names.all { backup.mapping[it] in currentIds }
    val working = editor.busy || backup.busy
    Dialog(onDismissRequest = { if (!working) backup.cancelPreview() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnBackPress = !working, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(12.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s["promptBackup.preview"], style = MaterialTheme.typography.titleLarge)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Text(s.format("promptBackup.mergeHint", "n" to preview.rules.size.toString()))
                    }
                    if (names.isNotEmpty()) item {
                        Text(s["promptBackup.mappingHint"], style = MaterialTheme.typography.bodySmall)
                    }
                    items(names, key = { "subject:$it" }) { name ->
                        var expanded by remember { mutableStateOf(false) }
                        val target = categories.firstOrNull { it.id == backup.mapping[name] }
                        Column {
                            Text(name, style = MaterialTheme.typography.labelLarge)
                            Box {
                                OutlinedButton(onClick = { expanded = true }, enabled = !working) {
                                    Text("→ " + (target?.let { "${it.name} (#${it.id})" } ?: s["promptBackup.chooseSubject"]))
                                }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                                    modifier = Modifier.heightIn(max = 320.dp)) {
                                    categories.filter { it.id > 0 }.forEach { category ->
                                        DropdownMenuItem(text = { Text("${category.name} (#${category.id})") }, onClick = {
                                            backup.selectSubject(name, category.id)
                                            expanded = false
                                        })
                                    }
                                }
                            }
                        }
                    }
                    if (!mapped) item { Text(s["promptBackup.mapRequired"], color = MaterialTheme.colorScheme.error) }
                    itemsIndexed(preview.rules) { index, rule ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${index + 1}. " + s[if (rule.enabled) "promptBackup.enabled" else "promptBackup.disabled"],
                                    style = MaterialTheme.typography.labelLarge)
                                Text(rule.text)
                                Text(if (rule.allSubjects) s["promptRules.all"] else rule.subjectNames.joinToString("\n") { name ->
                                    val target = categories.firstOrNull { it.id == backup.mapping[name] }
                                    "$name → " + (target?.name ?: s["promptBackup.chooseSubject"])
                                }, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (preview.rules.isEmpty()) item { Text(s["promptRules.empty"]) }
                }
                (backup.errorKey ?: editor.errorKey)?.let { Text(s[it], color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = backup::cancelPreview, enabled = !working) { Text(s["common.cancel"]) }
                    Button(onClick = { backup.confirm(editor, categories) },
                        enabled = !working && editor.loaded && mapped && preview.rules.isNotEmpty()) {
                        Text(s[if (working) "promptRules.working" else "promptBackup.confirm"])
                    }
                }
            }
        }
    }
}
