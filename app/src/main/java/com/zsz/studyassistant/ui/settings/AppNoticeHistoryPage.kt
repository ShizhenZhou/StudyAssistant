package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zsz.studyassistant.data.AppNoticeHistory
import com.zsz.studyassistant.data.PendingAppNotice
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
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
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(labels["hint"], style = MaterialTheme.typography.bodySmall)
        Button(enabled = !loading, onClick = {
            // Clear synchronously so a refresh never leaves a withdrawn body visible.
            loading = true
            notices = null
            attempt++
        }) {
            Text(labels[if (!loading && notices == null) "retry" else "refresh"])
        }
        when {
            loading -> Text(labels["loading"])
            notices == null -> Text(labels["error"])
            notices!!.isEmpty() -> Text(labels["empty"])
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(notices.orEmpty(), key = { it.key }) { item ->
                    AppNoticeHistoryCard(item, language, labels, s)
                }
            }
        }
    }
}

@Composable
private fun AppNoticeHistoryCard(item: PendingAppNotice, language: UiLang, labels: Strings, s: Strings) {
    var detailsExpanded by remember(item.identity) { mutableStateOf(false) }
    fun localized(values: Map<String, String>): String {
        val value = item.notice.text(values, language.id)
        return if (language == UiLang.ZH_TW && values["zh-TW"] == null) s2t(value) else value
    }
    // IDs are validated before display and encode Beijing time, not the optional startsAt window.
    val published = remember(item.notice.id) {
        LocalDateTime.parse(item.notice.id.take(15), noticeIdDateFormat).format(noticeDisplayDateFormat)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s[if (item.unstable) "notices.unstableTitle" else "notices.title"], style = MaterialTheme.typography.labelMedium)
                    Text(localized(item.notice.title), style = MaterialTheme.typography.titleMedium)
                    Text(labels.format("published", "time" to published), style = MaterialTheme.typography.labelSmall)
                    if (System.currentTimeMillis() >= Instant.parse(item.notice.expiresAt).toEpochMilli())
                        Text(labels["expired"], style = MaterialTheme.typography.labelSmall)
                    Text(localized(item.notice.body), style = MaterialTheme.typography.bodyMedium)
                }
            }
            TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                Text(labels[if (detailsExpanded) "hideNoticeDetails" else "noticeDetails"])
            }
            if (detailsExpanded) SelectionContainer {
                Text(labels.format("noticeId", "id" to item.notice.id), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private val noticeIdDateFormat = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss", Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)
private val noticeDisplayDateFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)
