package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import com.zsz.studyassistant.data.BuildIdentity
import com.zsz.studyassistant.data.UpdateChecker
import com.zsz.studyassistant.data.UpdatePromptStore

internal suspend fun Flow<Interaction>.watchRequiredUpdateHold(onHeld: () -> Unit) {
    filterIsInstance<PressInteraction>().collectLatest { interaction ->
        if (interaction is PressInteraction.Press) {
            delay(10_000)
            onHeld()
        }
    }
}

/** Shown after compatibility and installed-build checks, independently of notification permission. */
@Composable
fun StableUpdatePrompt(release: UpdateChecker.ReleaseInfo?, atRequiredPage: Boolean,
                       deferredThisVisit: Boolean, setDeferredThisVisit: (Boolean) -> Unit,
                       whenHidden: @Composable () -> Unit = {},
                       onRequiredBack: () -> Unit,
                       onOpenUpdate: (required: Boolean) -> Unit) {
    val context = LocalContext.current
    val s = LocalStrings.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val key = release?.let(UpdatePromptStore::identity)
    var dismissed by remember { mutableStateOf<String?>(null) }
    var allowedRequiredPage by remember(key) { mutableStateOf(false) }
    var confirmingDeferral by remember(key) { mutableStateOf(false) }
    var acknowledged by remember(key) { mutableStateOf(release != null && UpdatePromptStore.canDefer(context, release)) }
    val manualIdentity by UpdatePromptStore.manualCheckIdentity.collectAsStateWithLifecycle()
    // Also observe checks whose release content is unchanged. Read the current marker so separate
    // flow collectors cannot briefly present a dialog before the manual-check emission arrives.
    val manualCheck = release?.let(UpdatePromptStore::wasManuallyChecked) == true
    LaunchedEffect(key, manualIdentity, manualCheck) {
        if (release != null && manualCheck && UpdatePromptStore.consumeManualCheck(release)) {
            if (release.mandatory) {
                if (!deferredThisVisit) {
                    allowedRequiredPage = true
                    onOpenUpdate(true)
                }
            } else dismissed = key
        }
    }
    DisposableEffect(lifecycle, key) {
        val observer = LifecycleEventObserver { _, event ->
            // Returning from the launcher must still explain why this update is required.
            if (event == Lifecycle.Event.ON_STOP) {
                allowedRequiredPage = false
                confirmingDeferral = false
                setDeferredThisVisit(false)
                UpdatePromptStore.invalidateManualChecks()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(atRequiredPage) {
        if (!atRequiredPage) allowedRequiredPage = false
    }
    if (release == null || release.channel != UpdateChecker.Channel.STABLE) { whenHidden(); return }
    if (manualCheck) { whenHidden(); return }
    val required = release.mandatory
    if (required && (deferredThisVisit || (atRequiredPage && allowedRequiredPage))) { whenHidden(); return }
    if (!required && (dismissed == key || UpdateChecker.isSkipped(context, release))) { whenHidden(); return }
    if (required && confirmingDeferral) {
        AlertDialog(
            onDismissRequest = { confirmingDeferral = false },
            title = { Text(s["update.prompt.deferWarningTitle"]) },
            text = { Text(s["update.prompt.deferWarning"]) },
            dismissButton = { TextButton(onClick = { confirmingDeferral = false }) { Text(s["common.cancel"]) } },
            confirmButton = {
                Button(onClick = {
                    UpdatePromptStore.acknowledgeWarning(context, release)
                    acknowledged = true
                    confirmingDeferral = false
                    setDeferredThisVisit(true)
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError)) { Text(s["update.prompt.confirmDefer"]) }
            }
        )
        return
    }
    AlertDialog(
        // Let committed Back reach the callback, but never dismiss a required update.
        onDismissRequest = { if (required) onRequiredBack() else dismissed = key },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = !required),
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
            else if (acknowledged) TextButton(onClick = { setDeferredThisVisit(true) }) {
                Text(s["update.prompt.later"], color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            val presses = remember(key) { MutableInteractionSource() }
            if (required && !acknowledged) LaunchedEffect(presses, lifecycle) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    presses.interactions.watchRequiredUpdateHold { confirmingDeferral = true }
                }
            }
            Button(interactionSource = presses, onClick = {
                if (confirmingDeferral) return@Button
                if (required) allowedRequiredPage = true else dismissed = key
                onOpenUpdate(required)
            }) { Text(s["update.prompt.start"]) }
        }
    )
}
