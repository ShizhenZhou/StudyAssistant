package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.TagMergeViewModel

/** Lives inside the settings scroll container; nested lists always have bounded height. */
@Composable
fun TagMergePage(main: MainViewModel, model: TagMergeViewModel = viewModel()) {
    val s = LocalStrings.current
    val language = LocalUiLang.current.name
    val liveTags by main.tags.collectAsState()
    val base = model.inventory
    val counts = remember(base) { base?.links.orEmpty().groupingBy { it.tagId }.eachCount() }
    val names = remember(base) { base?.tags.orEmpty().associateBy { it.id } }
    var showInventory by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var editingKey by remember { mutableStateOf<Long?>(null) }
    var replaceConfirm by remember { mutableStateOf(false) }
    var undoConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(model) { model.load() }
    DisposableEffect(model) { onDispose { model.cancelAnalysis() } }
    LaunchedEffect(model.changeRevision) { if (model.changeRevision > 0) main.onNotebookTagsChanged() }

    Text(s["tagMerge.description"], style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    Text(s["tagMerge.privacy"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(10.dp))
    Text(s.format("tagMerge.summary", "n" to "${base?.tags?.size ?: liveTags.size}"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            if (model.drafts.isNotEmpty()) replaceConfirm = true else model.analyze(language)
        }, enabled = !model.busy && (base?.tags?.size ?: 0) >= 2, modifier = Modifier.weight(1f)) { Text(s["tagMerge.analyze"]) }
        OutlinedButton(onClick = { model.load() }, enabled = !model.busy, modifier = Modifier.weight(1f)) { Text(s["tagMerge.reload"]) }
    }
    if (model.busy) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(s[if (model.analysisBusy) "tagMerge.analyzing" else "tagMerge.working"], style = MaterialTheme.typography.bodySmall)
        if (model.analysisBusy) TextButton(onClick = { model.cancelAnalysis() }) { Text(s["common.cancel"]) }
    }
    model.noticeKey?.let {
        Text(s.format(it, "n" to "${model.noticeCount}", "q" to "${model.noticeQuestions}", "skipped" to "${model.noticeSkipped}"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    TextButton(onClick = { showInventory = !showInventory }) { Text(s["tagMerge.browse"]) }
    if (showInventory) {
        OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text(s["tagMerge.search"]) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxWidth().height(180.dp)) {
            items(base?.tags.orEmpty().filter { it.name.contains(search, ignoreCase = true) }, key = { it.id }) { tag ->
                Text(s.format("tagMerge.tagCount", "name" to tag.name, "n" to "${counts[tag.id] ?: 0}"),
                    modifier = Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    HorizontalDivider()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { model.addGroup() }, enabled = !model.busy && (base?.tags?.size ?: 0) >= 2, modifier = Modifier.weight(1f)) { Text(s["tagMerge.addGroup"]) }
        TextButton(onClick = { model.selectAll() }, enabled = !model.busy && model.drafts.isNotEmpty(), modifier = Modifier.weight(1f)) { Text(s["tagMerge.selectAll"]) }
    }
    if (model.drafts.isEmpty()) Text(s["tagMerge.empty"], style = MaterialTheme.typography.bodySmall)
    model.drafts.forEach { draft ->
        key(draft.key) {
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = draft.selected, onCheckedChange = { selected -> model.edit(draft.key) { it.copy(selected = selected) } }, enabled = !model.busy)
                        Text(s["tagMerge.include"], modifier = Modifier.weight(1f))
                        TextButton(onClick = { model.remove(draft.key) }, enabled = !model.busy) { Text(s["tagMerge.removeGroup"]) }
                    }
                    Text(draft.group.sourceIds.mapNotNull { id -> names[id]?.let {
                        s.format("tagMerge.tagCount", "name" to it.name, "n" to "${counts[id] ?: 0}")
                    } }.joinToString("；").ifEmpty { s["tagMerge.chooseSources"] },
                        style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { editingKey = draft.key }, enabled = !model.busy) { Text(s["tagMerge.chooseSources"]) }
                    OutlinedTextField(value = draft.group.targetName,
                        onValueChange = { name -> model.edit(draft.key) { it.copy(group = it.group.copy(targetName = name)) } },
                        label = { Text(s["tagMerge.targetName"]) }, singleLine = true, enabled = !model.busy,
                        modifier = Modifier.fillMaxWidth())
                    if (draft.group.reason.isNotBlank()) Text(draft.group.reason,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Button(onClick = { model.preview() }, enabled = !model.busy && model.drafts.any { it.selected }, modifier = Modifier.fillMaxWidth()) {
        Text(s["tagMerge.preview"])
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = { undoConfirm = true }, enabled = !model.busy && model.undoAllowed, modifier = Modifier.fillMaxWidth()) {
        Text(s["tagMerge.undo"])
    }
    Text(s[if (!model.undoPresent) "tagMerge.undoNone" else if (!model.undoAllowed) "tagMerge.undoUnavailable" else "tagMerge.undoHint"],
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(16.dp))

    if (replaceConfirm) AlertDialog(onDismissRequest = { replaceConfirm = false },
        title = { Text(s["tagMerge.analyze"]) }, text = { Text(s["tagMerge.replaceDrafts"]) },
        confirmButton = { TextButton(onClick = { replaceConfirm = false; model.analyze(language) }) { Text(s["common.ok"]) } },
        dismissButton = { TextButton(onClick = { replaceConfirm = false }) { Text(s["common.cancel"]) } })

    val editing = model.drafts.firstOrNull { it.key == editingKey }
    if (editing != null) {
        var selected by remember(editingKey) { mutableStateOf(editing.group.sourceIds.toSet()) }
        var query by remember(editingKey) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { editingKey = null }, title = { Text(s["tagMerge.chooseSources"]) },
            text = {
                Column {
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text(s["tagMerge.search"]) })
                    LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                        items(base?.tags.orEmpty().filter { it.name.contains(query, true) }, key = { it.id }) { tag ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = tag.id in selected, onCheckedChange = { checked ->
                                    selected = if (checked) selected + tag.id else selected - tag.id
                                })
                                Text(s.format("tagMerge.tagCount", "name" to tag.name, "n" to "${counts[tag.id] ?: 0}"),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = {
                model.edit(editing.key) { it.copy(group = it.group.copy(sourceIds = selected.sorted(), reason = "")) }
                editingKey = null
            }) { Text(s["common.ok"]) } },
            dismissButton = { TextButton(onClick = { editingKey = null }) { Text(s["common.cancel"]) } })
    }
    model.prepared?.let { preview ->
        AlertDialog(onDismissRequest = { model.dismissPreview() }, title = { Text(s["tagMerge.previewTitle"]) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(s.format("tagMerge.previewSummary", "n" to "${preview.groups.size}", "q" to "${preview.affectedQuestions}"))
                    preview.groups.forEach { group ->
                        Spacer(Modifier.height(10.dp))
                        Text(group.sourceIds.mapNotNull { names[it]?.name }.joinToString("、") + " → " + group.targetName)
                    }
                }
            }, confirmButton = { TextButton(onClick = { model.apply() }) { Text(s["tagMerge.apply"]) } },
            dismissButton = { TextButton(onClick = { model.dismissPreview() }) { Text(s["common.cancel"]) } })
    }
    if (undoConfirm) AlertDialog(onDismissRequest = { undoConfirm = false }, title = { Text(s["tagMerge.undo"]) },
        text = { Text(s["tagMerge.undoConfirm"]) },
        confirmButton = { TextButton(onClick = { undoConfirm = false; model.undo() }) { Text(s["common.ok"]) } },
        dismissButton = { TextButton(onClick = { undoConfirm = false }) { Text(s["common.cancel"]) } })
    model.errorKey?.let { error ->
        AlertDialog(onDismissRequest = { model.dismissError() }, title = { Text(s["tagMerge.title"]) },
            text = { Text(s.format(error, "code" to model.errorHttpCode?.toString().orEmpty())) },
            confirmButton = { TextButton(onClick = { model.dismissError() }) { Text(s["common.ok"]) } })
    }
}
