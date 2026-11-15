package com.zsz.studyassistant.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zsz.studyassistant.data.ConversationDocument

internal val SOLVE_DOCUMENT_MIME_TYPES = arrayOf(
    "application/pdf", "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "text/plain", "text/markdown", "text/csv", "application/csv",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
)

@Composable
internal fun DocumentAttachments(
    documents: List<ConversationDocument>,
    enabled: Boolean,
    onRemove: (ConversationDocument) -> Unit,
    modifier: Modifier = Modifier,
    onEdit: ((ConversationDocument) -> Unit)? = null
) {
    if (documents.isEmpty()) return
    val s = LocalStrings.current
    val context = LocalContext.current
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        if (documents.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(documents, key = { it.sha256 }) { document ->
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.widthIn(max = 260.dp).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📎", fontSize = 18.sp)
                        Column(Modifier.weight(1f).clickable(enabled = enabled && onEdit != null) {
                            onEdit?.invoke(document)
                        }.padding(start = 6.dp, top = 6.dp, bottom = 6.dp)) {
                            Text(document.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium)
                            Text(Formatter.formatShortFileSize(context, document.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                            val selection = when {
                                document.pages != null -> s.format("documentSelection.pagesSelected", "n" to document.pages.size.toString())
                                document.sheets != null -> s.format("documentSelection.sheetsSelected", "n" to document.sheets.size.toString())
                                else -> s["documentSelection.allContent"]
                            }
                            Text(selection, style = MaterialTheme.typography.labelSmall)
                            if (onEdit != null) Text(s["documentSelection.tapToEdit"], style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { onRemove(document) }, enabled = enabled,
                            modifier = Modifier.semantics { contentDescription = s["document.remove"] + ": " + document.name }) {
                            Text("×", fontSize = 20.sp)
                        }
                    }
                }
            }
        }
        if (documents.isNotEmpty()) Text(s["document.sendHint"], style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}
