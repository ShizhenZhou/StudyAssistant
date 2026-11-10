package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zsz.studyassistant.data.BuildIdentity
import com.zsz.studyassistant.data.UpdateChecker
import com.zsz.studyassistant.data.UpdatePromptStore

/** Shown after compatibility and installed-build checks, independently of notification permission. */
@Composable
fun StableUpdatePrompt(release: UpdateChecker.ReleaseInfo?, atRequiredPage: Boolean,
                       onOpenUpdate: (required: Boolean) -> Unit) {
    val context = LocalContext.current
    val s = LocalStrings.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val key = release?.let(UpdatePromptStore::identity)
    var dismissed by remember { mutableStateOf<String?>(null) }
    var allowedRequiredPage by remember(key) { mutableStateOf(false) }
    DisposableEffect(lifecycle, key) {
        val observer = LifecycleEventObserver { _, event ->
            // Returning from the launcher must still explain why this update is required.
            if (event == Lifecycle.Event.ON_STOP) allowedRequiredPage = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(atRequiredPage) {
        if (!atRequiredPage) allowedRequiredPage = false
    }
    if (release == null || release.channel != UpdateChecker.Channel.STABLE) return
    val required = release.mandatory
    if (required && atRequiredPage && allowedRequiredPage) return
    if (!required && (dismissed == key || UpdateChecker.isSkipped(context, release))) return
    AlertDialog(
        onDismissRequest = { if (!required) dismissed = key },
        properties = DialogProperties(dismissOnBackPress = !required, dismissOnClickOutside = !required),
        title = { Text(s["update.prompt.title"]) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Study Assistant v${release.displayVersion}", style = MaterialTheme.typography.titleMedium)
                val time = BuildIdentity.assetTime(release.assetName, release.displayVersion)
                Text(s.format("update.prompt.build", "time" to if (time > 0)
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(time)) else s["update.prompt.unknownTime"]),
                    style = MaterialTheme.typography.bodySmall)
                if (required) Text(s["update.prompt.required"], color = MaterialTheme.colorScheme.primary)
                Text(UpdateChecker.prettyReleaseNotes(release.notes), style = MaterialTheme.typography.bodyMedium)
            }
        },
        dismissButton = {
            if (!required) TextButton(onClick = { dismissed = key }) { Text(s["update.prompt.later"]) }
        },
        confirmButton = {
            Button(onClick = {
                if (required) allowedRequiredPage = true else dismissed = key
                onOpenUpdate(required)
            }) { Text(s["update.prompt.start"]) }
        }
    )
}
