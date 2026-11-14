package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zsz.studyassistant.ChatItem

/** Changes request attachments only. Message history and local originals remain intact. */
@Composable
internal fun ConversationContextControls(items: List<ChatItem>, draft: SolveInputDraft, enabled: Boolean) {
    val s = LocalStrings.current
    val historical = remember(items) {
        items.firstOrNull { it.role == "question" }?.documents.orEmpty() +
            items.filter { it.role == "user" }.flatMap { it.documents }
    }
    val choices = remember(historical) { historical.distinctBy { it.sha256 } }
    val selected = historical.filterNot { it.sha256 in draft.excludedDocumentHashes } + draft.documents
    val duplicate = selected.groupingBy { it.sha256 }.eachCount().values.any { it > 1 }
    // Characters are an advisory estimate, never a token estimate or a reason to truncate content.
    val historyCharacters = remember(items) {
        items.sumOf { it.content.length.toLong() + it.thinking.orEmpty().length }
    }
    val characters = historyCharacters + draft.text.length + draft.quote?.text.orEmpty().length
    var showFiles by remember(draft) { mutableStateOf(false) }

    if (historical.isNotEmpty() || draft.documents.isNotEmpty() || characters >= 20_000) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            if (historical.isNotEmpty() || draft.documents.isNotEmpty()) {
                TextButton(onClick = { showFiles = true }, enabled = enabled && choices.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) {
                    Text(s.format("context.files", "n" to selected.size.toString()))
                }
            }
            if (duplicate) Text(s["context.duplicate"], style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary)
            if (characters >= 20_000) Text(s.format("context.long", "n" to characters.toString()),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
    if (showFiles) AlertDialog(
        onDismissRequest = { showFiles = false },
        title = { Text(s["context.title"]) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(s["context.hint"], style = MaterialTheme.typography.bodySmall)
                choices.forEach { document ->
                    val checked = document.sha256 !in draft.excludedDocumentHashes
                    val copies = historical.count { it.sha256 == document.sha256 }
                    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled,
                        role = Role.Checkbox, onValueChange = { include ->
                            draft.excludedDocumentHashes = if (include)
                                draft.excludedDocumentHashes - document.sha256
                            else draft.excludedDocumentHashes + document.sha256
                        }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                        Text(document.name + if (copies > 1) " × $copies" else "",
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showFiles = false }) { Text(s["common.back"]) } }
    )
}
