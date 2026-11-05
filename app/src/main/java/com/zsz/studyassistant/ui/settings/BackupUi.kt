package com.zsz.studyassistant.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import com.zsz.studyassistant.data.AutoBackup

/** Logical SAF path, not a guessed filesystem location for cloud providers. */
internal fun backupFolderLabel(documentId: String?, displayName: String?, internalStorage: String): String? {
    val volume = documentId?.substringBefore(':', "")
    return if (!volume.isNullOrEmpty() && documentId.contains(':') &&
        (volume == "primary" || volume.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")))) {
        (if (volume == "primary") "$internalStorage/" else "$volume:/") + documentId.substringAfter(':').trimStart('/', ' ')
    } else displayName ?: documentId
}

internal fun shortenBackupPath(path: String, fits: (String) -> Boolean): String {
    if (fits(path)) return path
    val prefix = ".../"
    if (!fits(prefix)) return ""
    // Prefer complete trailing path components; use a character suffix for one oversized folder.
    path.indices.filter { path[it] == '/' }.forEach { index ->
        val tail = path.substring(index + 1)
        if (tail.isNotEmpty() && fits(prefix + tail)) return prefix + tail
    }
    var low = 0
    var high = path.length
    while (low < high) {
        val count = (low + high + 1) / 2
        if (fits(prefix + path.takeLast(count))) low = count else high = count - 1
    }
    return prefix + path.takeLast(low)
}

@Composable
internal fun BackupFolderPath(path: String, label: String, onClick: () -> Unit) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodySmall
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = with(density) { maxWidth.roundToPx() }
        val shortened = shortenBackupPath(path) {
            measurer.measure(label + it, style = style, softWrap = false).size.width <= available
        }
        Text(label + shortened, maxLines = 1, softWrap = false, style = style,
            color = Color(0xFF2196F3), textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable(role = Role.Button, onClick = onClick))
    }
}

/** Uses only SAF content URIs; browsing never changes the configured backup directory. */
internal fun openBackupFolder(context: Context, tree: Uri?): Boolean {
    if (tree == null) return false
    val document = runCatching {
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }.getOrNull() ?: return false
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(document, DocumentsContract.Document.MIME_TYPE_DIR)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(view) }.isSuccess) return true
    // Some file managers do not handle directory VIEW. The official picker can still browse it.
    val browse = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        .putExtra(DocumentsContract.EXTRA_INITIAL_URI, document)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(browse) }.isSuccess
}

/** The system picker may ignore the hint or fall back if the provider is unavailable. */
internal class BackupExportDocument : ActivityResultContracts.CreateDocument("application/zip") {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).apply {
            AutoBackup.treeUri(context)?.let { tree ->
                runCatching { DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)) }
                    .getOrNull()?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            }
        }
}
