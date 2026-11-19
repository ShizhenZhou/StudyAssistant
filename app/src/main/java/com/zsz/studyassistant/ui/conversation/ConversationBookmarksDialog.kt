package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zsz.studyassistant.ConversationBookmark

@Composable
internal fun ConversationBookmarksDialog(
    bookmarks: List<ConversationBookmark>, enabled: Boolean, onDismiss: () -> Unit,
    onRemove: (String) -> Unit, onJump: (ConversationBookmark) -> Unit
) {
    val s = LocalStrings.current
    var previewId by remember { mutableStateOf<String?>(null) }
    val preview = bookmarks.firstOrNull { it.id == previewId }
    fun closePreviewOrDialog() { if (preview != null) previewId = null else onDismiss() }
    AlertDialog(onDismissRequest = ::closePreviewOrDialog,
        title = { Text(s[if (preview != null) "bookmark.preview" else "bookmark.title"]) },
        text = {
            if (preview != null) Column {
                // One renderer for the selected item; the potentially long list stays lightweight.
                ConversationWebView(
                    messages = listOf(ChatMsg("assistant", preview.text)),
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                )
                Row {
                    TextButton(onClick = { onJump(preview) }, enabled = enabled) { Text(s["bookmark.jump"]) }
                    TextButton(onClick = { onRemove(preview.id); previewId = null }, enabled = enabled) { Text(s["bookmark.remove"]) }
                }
            }
            else if (bookmarks.isEmpty()) Text(s["bookmark.empty"])
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(bookmarks, key = { it.id }) { bookmark ->
                    Column(Modifier.fillMaxWidth().clickable(onClickLabel = s["bookmark.preview"]) { previewId = bookmark.id }) {
                        Text(bookmark.text, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(s["bookmark.preview"], style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
                        HorizontalDivider()
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = ::closePreviewOrDialog) { Text(s["common.ok"]) } })
}
