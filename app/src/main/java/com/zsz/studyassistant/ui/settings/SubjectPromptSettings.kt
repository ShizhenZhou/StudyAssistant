package com.zsz.studyassistant.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.Category
import com.zsz.studyassistant.data.CustomPromptRule
import com.zsz.studyassistant.data.PromptRulesStore
import com.zsz.studyassistant.data.MAX_PROMPT_RULES
import com.zsz.studyassistant.data.MAX_PROMPT_RULE_TEXT
import com.zsz.studyassistant.data.PromptRuleValidationException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Activity recreation retains drafts and in-flight writes without placing prompt text in a Bundle. */
class SubjectPromptEditor(context: Context, private val scope: CoroutineScope) {
    private val store = PromptRulesStore(context.applicationContext)
    val backup = PromptRuleBackupController(context.applicationContext, scope)
    var rules by mutableStateOf<List<CustomPromptRule>>(emptyList())
        private set
    var draft by mutableStateOf<CustomPromptRule?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var errorKey by mutableStateOf<String?>(null)
        private set
    var legacyReviewPending by mutableStateOf(false)
        private set
    var legacyReviewChecked by mutableStateOf(false)
        private set
    var openRequested by mutableStateOf(false)
        private set

    init { load() }

    fun load() {
        if (busy) return
        busy = true
        errorKey = null
        scope.launch {
            try {
                val (saved, reviewNeeded) = withContext(Dispatchers.IO) { store.read() to store.needsLegacyReview() }
                rules = saved
                legacyReviewPending = reviewNeeded
                loaded = true
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { errorKey = "prompt.readFailed" }
            finally { busy = false; legacyReviewChecked = true }
        }
    }

    fun acknowledgeLegacyReview(openSettings: Boolean) {
        if (busy) return
        busy = true
        errorKey = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.acknowledgeLegacyReview() }
                legacyReviewPending = false
                openRequested = openSettings
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { errorKey = "promptRules.migration.ackFailed" }
            finally { busy = false }
        }
    }

    fun consumeOpenRequest() { openRequested = false }

    fun edit(rule: CustomPromptRule) {
        if (busy || !loaded) return
        errorKey = null
        draft = rule
    }

    fun add(text: String = "") {
        if (busy || !loaded) return
        if (rules.size >= MAX_PROMPT_RULES) { errorKey = "promptRules.limit.count"; return }
        edit(CustomPromptRule(id = UUID.randomUUID().toString(), text = text))
    }

    fun changeDraft(rule: CustomPromptRule) { if (!busy) { draft = rule; errorKey = null } }
    fun cancelEdit() { if (!busy) { draft = null; errorKey = null } }

    fun saveDraft() {
        val edited = draft ?: return
        if (edited.text.isBlank() || (!edited.allSubjects && edited.categoryIds.isEmpty())) return
        val normalized = if (edited.allSubjects) edited.copy(categoryIds = emptyList()) else edited
        val next = if (rules.any { it.id == edited.id }) rules.map { if (it.id == edited.id) normalized else it }
            else rules + normalized
        persist(next, closeEditor = true)
    }

    fun setEnabled(id: String, enabled: Boolean) = persist(rules.map { if (it.id == id) it.copy(enabled = enabled) else it })
    fun delete(id: String) = persist(rules.filterNot { it.id == id })

    fun appendImported(rules: List<CustomPromptRule>, onSaved: () -> Unit) =
        persist(this.rules + rules, onSaved = onSaved)

    private fun persist(next: List<CustomPromptRule>, closeEditor: Boolean = false, onSaved: () -> Unit = {}) {
        // Set synchronously before launching: another click cannot race this write or use a stale list.
        if (busy || !loaded) return
        busy = true
        errorKey = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(next) }
                rules = next
                legacyReviewPending = false
                if (closeEditor) draft = null
                onSaved()
            } catch (error: CancellationException) { throw error }
            catch (error: PromptRuleValidationException) { errorKey = error.reason.key }
            catch (_: Exception) { errorKey = "promptRules.saveFailed" }
            finally { busy = false }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubjectPromptSettings(vm: MainViewModel) {
    val s = LocalStrings.current
    val editor = vm.customPromptEditor
    val categories by vm.categories.collectAsStateWithLifecycle()
    var showTemplates by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(s["settings.customPrompt.desc"], style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(s["promptRules.limits"], style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { editor.add() }, enabled = editor.loaded && !editor.busy) { Text(s["promptRules.add"]) }
            OutlinedButton(onClick = { showTemplates = true }, enabled = editor.loaded && !editor.busy) {
                Text(s["promptRules.templates"])
            }
        }
        PromptRuleBackupActions(editor, categories)
        if (editor.busy) Text(s["promptRules.working"], style = MaterialTheme.typography.bodySmall)
        editor.errorKey?.let { key ->
            Text(s[key], color = MaterialTheme.colorScheme.error)
            if (!editor.loaded) TextButton(onClick = editor::load, enabled = !editor.busy) { Text(s["promptRules.retry"]) }
        }
        if (editor.loaded && editor.rules.isEmpty()) Text(s["promptRules.empty"])
        editor.rules.forEach { rule ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(rule.text, style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (rule.allSubjects) PromptScopeLabel(s["promptRules.all"])
                        else rule.categoryIds.forEach { id ->
                            val category = categories.firstOrNull { it.id == id }
                            PromptScopeLabel(category?.name ?: s["promptRules.deletedSubject"], unavailable = category == null)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.Start) {
                            TextButton(onClick = { editor.edit(rule) }, enabled = !editor.busy) { Text(s["promptRules.edit"]) }
                            TextButton(onClick = { deleting = rule.id }, enabled = !editor.busy,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                Text(s["common.delete"])
                            }
                        }
                        Switch(rule.enabled, onCheckedChange = { editor.setEnabled(rule.id, it) }, enabled = !editor.busy,
                            modifier = Modifier.semantics { contentDescription = s["promptRules.enabled"] + ": " + rule.text })
                    }
                }
            }
        }
    }
    editor.draft?.let { draft -> PromptRuleEditDialog(editor, draft, categories) }
    if (showTemplates) AlertDialog(
        onDismissRequest = { showTemplates = false },
        title = { Text(s["promptRules.templates"]) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s["promptRules.templatesHint"], style = MaterialTheme.typography.bodySmall)
                listOf("steps", "conditions", "hints", "mistakes", "methods").forEach { template ->
                    OutlinedButton(onClick = { showTemplates = false; editor.add(s["promptRules.template.$template.text"]) },
                        modifier = Modifier.fillMaxWidth()) { Text(s["promptRules.template.$template.title"]) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showTemplates = false }) { Text(s["common.cancel"]) } }
    )
    deleting?.let { id -> AlertDialog(
        onDismissRequest = { deleting = null },
        title = { Text(s["promptRules.deleteTitle"]) },
        text = { Text(s["promptRules.deleteMessage"]) },
        confirmButton = { TextButton(onClick = { deleting = null; editor.delete(id) }, enabled = !editor.busy,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(s["common.delete"]) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(s["common.cancel"]) } }
    ) }
}

@Composable
private fun PromptScopeLabel(text: String, unavailable: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.small,
        color = if (unavailable) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (unavailable) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromptRuleEditDialog(editor: SubjectPromptEditor, draft: CustomPromptRule, categories: List<Category>) {
    val s = LocalStrings.current
    val textTooLong = draft.text.length > MAX_PROMPT_RULE_TEXT
    val canSave = !editor.busy && !textTooLong && draft.text.isNotBlank() && (draft.allSubjects || draft.categoryIds.isNotEmpty())
    Dialog(onDismissRequest = editor::cancelEdit, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = !editor.busy, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(12.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s["promptRules.edit"], style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(draft.text, onValueChange = { editor.changeDraft(draft.copy(text = it)) },
                        enabled = !editor.busy, label = { Text(s["promptRules.content"]) },
                        isError = textTooLong,
                        supportingText = { Text(if (textTooLong) s["promptRules.limit.text"] else "${draft.text.length} / $MAX_PROMPT_RULE_TEXT") },
                        minLines = 5, maxLines = 12, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(s["promptRules.enabled"], Modifier.weight(1f))
                        Switch(draft.enabled, onCheckedChange = { editor.changeDraft(draft.copy(enabled = it)) }, enabled = !editor.busy,
                            modifier = Modifier.semantics { contentDescription = s["promptRules.enabled"] })
                    }
                    Text(s["promptRules.scope"], style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = draft.allSubjects, onClick = { editor.changeDraft(draft.copy(allSubjects = true, categoryIds = emptyList())) },
                            enabled = !editor.busy, label = { Text(s["promptRules.all"]) })
                        FilterChip(selected = !draft.allSubjects, onClick = { editor.changeDraft(draft.copy(allSubjects = false)) },
                            enabled = !editor.busy, label = { Text(s["promptRules.selectedSubjects"]) })
                    }
                    if (!draft.allSubjects) {
                        Text(s["promptRules.multiSelect"], style = MaterialTheme.typography.bodySmall)
                        if (categories.isEmpty()) Text(s["promptRules.noSubjects"], style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            categories.forEach { category ->
                                val selected = category.id in draft.categoryIds
                                FilterChip(selected = selected, onClick = {
                                    editor.changeDraft(draft.copy(categoryIds = if (selected) draft.categoryIds - category.id else draft.categoryIds + category.id))
                                }, enabled = !editor.busy, label = { Text(category.name) })
                            }
                            draft.categoryIds.filter { id -> categories.none { it.id == id } }.forEach { id ->
                                FilterChip(selected = true, onClick = { editor.changeDraft(draft.copy(categoryIds = draft.categoryIds - id)) },
                                    enabled = !editor.busy, label = { Text(s["promptRules.deletedSubject"]) })
                            }
                        }
                        if (draft.categoryIds.isEmpty()) Text(s["promptRules.selectRequired"], color = MaterialTheme.colorScheme.error)
                        if (draft.categoryIds.any { id -> categories.none { it.id == id } }) {
                            Text(s["promptRules.deletedHint"], color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(s["promptRules.scopeHint"], style = MaterialTheme.typography.bodySmall)
                    editor.errorKey?.let { Text(s[it], color = MaterialTheme.colorScheme.error) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = editor::cancelEdit, enabled = !editor.busy) { Text(s["common.cancel"]) }
                    Button(onClick = editor::saveDraft, enabled = canSave) { Text(s[if (editor.busy) "promptRules.working" else "common.save"]) }
                }
            }
        }
    }
}
