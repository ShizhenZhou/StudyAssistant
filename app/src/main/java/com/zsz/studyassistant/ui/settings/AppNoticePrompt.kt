package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.zsz.studyassistant.data.AppNoticeStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AppNoticePrompt(enabled: Boolean, canOpenUpdate: Boolean, onOpenUpdate: () -> Unit) {
    val context = LocalContext.current
    val s = LocalStrings.current
    val language = LocalUiLang.current.let { if (it == UiLang.SYSTEM) systemUiLang() else it }
    val pending by AppNoticeStore.pending.collectAsStateWithLifecycle()
    val nextChangeAt by AppNoticeStore.nextChangeAt.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { AppNoticeStore.restore(context) }
    }
    val item = pending.firstOrNull()
    var acknowledging by remember(item?.identity) { mutableStateOf(false) }
    LaunchedEffect(lifecycle, nextChangeAt) {
        val deadline = nextChangeAt
        if (deadline != null) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay((deadline - System.currentTimeMillis()).coerceAtLeast(1))
            AppNoticeStore.restore(context)
        }
    }
    if (!enabled || item == null) return
    fun text(values: Map<String, String>): String {
        val value = item.notice.text(values, language.id)
        return if (language == UiLang.ZH_TW && values["zh-TW"] == null) s2t(value) else value
    }
    fun acknowledge(openUpdate: Boolean = false) {
        if (acknowledging) return
        acknowledging = true
        scope.launch {
            AppNoticeStore.acknowledge(context, item)
            if (openUpdate) onOpenUpdate()
        }
    }
    AlertDialog(
        onDismissRequest = { acknowledge() },
        title = { Text(s[if (item.unstable) "notices.unstableTitle" else "notices.title"]) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text(item.notice.title), style = MaterialTheme.typography.titleMedium)
                if (item.unstable) Text(s["notices.unstableHint"], color = MaterialTheme.colorScheme.error)
                Text(text(item.notice.body))
            }
        },
        confirmButton = {
            TextButton(enabled = !acknowledging, onClick = { acknowledge() }) { Text(s["notices.acknowledge"]) }
        },
        dismissButton = {
            if (item.unstable && canOpenUpdate) TextButton(enabled = !acknowledging,
                onClick = { acknowledge(true) }) { Text(s["notices.openUpdate"]) }
        }
    )
}
