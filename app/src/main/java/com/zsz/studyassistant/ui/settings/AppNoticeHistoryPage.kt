package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zsz.studyassistant.data.AppNoticeHistory
import com.zsz.studyassistant.data.PendingAppNotice
import java.time.Instant
import kotlinx.coroutines.CancellationException

@Composable
internal fun AppNoticeHistoryPage() {
    val context = LocalContext.current
    val labels = aboutHistoryStrings(LocalUiLang.current)
    val s = LocalStrings.current
    val language = LocalUiLang.current.let { if (it == UiLang.SYSTEM) systemUiLang() else it }
    // Deliberately not saveable: revisiting the route must never restore a previous response body.
    var notices by remember { mutableStateOf<List<PendingAppNotice>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        loading = true
        notices = null
        notices = try { AppNoticeHistory.load(context) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        loading = false
    }
    Text(labels["hint"], style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(12.dp))
    when {
        loading -> Text(labels["loading"])
        notices == null -> {
            Text(labels["error"])
            Button(onClick = { attempt++ }) { Text(labels["retry"]) }
        }
        notices!!.isEmpty() -> Text(labels["empty"])
        else -> notices!!.forEach { item ->
            fun localized(values: Map<String, String>): String {
                val value = item.notice.text(values, language.id)
                return if (language == UiLang.ZH_TW && values["zh-TW"] == null) s2t(value) else value
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(s[if (item.unstable) "notices.unstableTitle" else "notices.title"], style = MaterialTheme.typography.labelMedium)
                        Text(localized(item.notice.title), style = MaterialTheme.typography.titleMedium)
                        Text(item.notice.id, style = MaterialTheme.typography.labelSmall)
                        if (System.currentTimeMillis() >= Instant.parse(item.notice.expiresAt).toEpochMilli())
                            Text(labels["expired"], style = MaterialTheme.typography.labelSmall)
                        Text(localized(item.notice.body), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
