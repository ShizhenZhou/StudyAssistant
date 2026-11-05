package com.zsz.studyassistant.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.zsz.studyassistant.MainViewModel
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Short-lived UI feedback only. Routine persistence must not call this controller. */
internal data class FeedbackVisuals(
    override val message: String,
    val isError: Boolean = false,
    val source: String = "",
    override val actionLabel: String? = null,
    val undo: Boolean = false,
) : SnackbarVisuals {
    override val withDismissAction = false
    override val duration = if (undo) SnackbarDuration.Indefinite else if (isError) SnackbarDuration.Long else SnackbarDuration.Short
}

@Stable
class AppFeedbackState(private val scope: CoroutineScope) {
    val hostState = SnackbarHostState()
    var saveRecoveryRequested by mutableStateOf(false)
    var draftRecoveryRequested by mutableStateOf(false)
    var draftOrigin = AppNav.Origin.HOME
    private var displayJob: Job? = null
    private var activeVisuals: FeedbackVisuals? = null
    private var activeAction: (() -> Unit)? = null
    private var afterUndo: (() -> Unit)? = null
    var backExitHint by mutableStateOf<String?>(null)
        private set
    private var backExitJob: Job? = null

    fun showBackExitHint(message: String) {
        backExitJob?.cancel()
        backExitHint = message
        backExitJob = scope.launch {
            kotlinx.coroutines.delay(ROOT_BACK_HINT_MS)
            backExitHint = null
        }
    }
    fun clearBackExitHint() {
        backExitJob?.cancel()
        backExitJob = null
        backExitHint = null
    }

    fun show(message: String, isError: Boolean = false, source: String = "",
             actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        val visuals = FeedbackVisuals(message, isError, source, actionLabel)
        val current = activeVisuals
        if (current?.undo == true) {
            if (isError) afterUndo = { show(message, isError, source, actionLabel, onAction) }
            return
        }
        // Never bury an actionable failure under success messages or build a stale queue.
        if (current == visuals || (current?.isError == true && !isError)) return
        publish(visuals, onAction)
    }
    fun showDeletion(message: String, label: String, action: () -> Unit) {
        val previous = activeVisuals
        val previousAction = activeAction
        if(previous?.isError == true) afterUndo = {
            show(previous.message, true, previous.source, previous.actionLabel, previousAction)
        }
        publish(FeedbackVisuals(message, source="delete", actionLabel=label, undo=true), action)
    }
    private fun publish(visuals: FeedbackVisuals, action: (() -> Unit)?) {
        val previousJob = displayJob
        activeVisuals = visuals
        activeAction = action
        previousJob?.cancel()
        displayJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (hostState.showSnackbar(visuals) == SnackbarResult.ActionPerformed) action?.invoke()
            } finally {
                if (activeVisuals === visuals) {
                    activeVisuals = null
                    activeAction = null
                    if(visuals.undo) { val next=afterUndo; afterUndo=null; next?.invoke() }
                }
            }
        }
    }

    fun dismiss(source: String) {
        if (activeVisuals?.source == source) {
            displayJob?.cancel()
        }
    }

    fun showBackupFailure(strings: Strings, automatic: Boolean, onDetails: () -> Unit) {
        show(strings[if (automatic) "data.backupNotifyFailed" else "feedback.backupFailed"],
            isError = true, source = "backup", actionLabel = strings["feedback.details"], onAction = onDetails)
    }
}

val LocalAppFeedback = staticCompositionLocalOf<AppFeedbackState> {
    error("AppFeedbackLayout must wrap the app content")
}

/** A sibling overlay, never a row in the app's Column: messages cannot resize navigation/content. */
@Composable
fun AppFeedbackLayout(vm: MainViewModel, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val state = remember(scope) { AppFeedbackState(scope) }
    val deletes by vm.deletionState.collectAsStateWithLifecycle()
    val strings = LocalStrings.current
    LaunchedEffect(deletes.pending?.token) {
        val batch=deletes.pending
        if(batch==null) state.dismiss("delete")
        else state.showDeletion(strings.format("delete.undoMessage", "n" to "${batch.count}"), strings["delete.undo"]) {
            vm.undoDelete(batch.token)
        }
    }
    CompositionLocalProvider(LocalAppFeedback provides state) {
        FeedbackMessage(vm.deleteError, true) { vm.clearDeleteError() }
        FeedbackMessage(vm.draftError, true) { vm.clearDraftError() }
        Surface(Modifier.fillMaxSize(), contentColor = MaterialTheme.colorScheme.onSurface,
            color = MaterialTheme.colorScheme.surface.let {
            if (vm.wallpaperBackground) wallpaperSurface(it, vm.wallpaperShade) else it
        }) {
            Box(Modifier.fillMaxSize()) {
                content()
                SaveRecoveryDialogs(vm)
                FeedbackHost(state)
            }
        }
    }
}

@Composable
private fun FeedbackHost(state: AppFeedbackState) {
    // Official SnackbarHost owns animation, dismissal and accessible timeout adjustment.
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        state.backExitHint?.let { message ->
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = maxHeight * .18f + if (state.hostState.currentSnackbarData != null) 72.dp else 0.dp)
                    .padding(horizontal = 24.dp).widthIn(max = 480.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface, shadowElevation = 6.dp
            ) {
                Text(message, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
        }
        SnackbarHost(
            hostState = state.hostState,
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = maxHeight * 0.18f)
                .padding(horizontal = 24.dp).widthIn(max = 480.dp)
        ) { data ->
            val failure = (data.visuals as? FeedbackVisuals)?.isError == true
            val errorRed = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f)
                Color(0xFFFF8A80) else Color(0xFFB3261E)
            val visuals = data.visuals as? FeedbackVisuals
            Surface(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                shape = RoundedCornerShape(if (visuals?.undo == true) 12.dp else 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (failure) errorRed else MaterialTheme.colorScheme.onSurface,
                shadowElevation = 6.dp,
            ) {
                Row(Modifier.width(IntrinsicSize.Max).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(data.visuals.message, modifier = Modifier.weight(1f, fill = false),
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    data.visuals.actionLabel?.let { label ->
                        Spacer(Modifier.width(12.dp))
                        val detailsLink = visuals?.source == "backup" && failure
                        Text(label,
                            modifier = Modifier.clickable(onClick = data::performAction)
                                .widthIn(min = 48.dp).heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically),
                            color = if (detailsLink) {
                                if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF90CAF9) else Color(0xFF1565C0)
                            } else MaterialTheme.colorScheme.primary,
                            textDecoration = if (detailsLink) TextDecoration.Underline else TextDecoration.None,
                            textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/** Consume one-shot results so re-entering a settings page cannot replay old success notices. */
@Composable
fun FeedbackMessage(message: String?, isError: Boolean, onConsumed: () -> Unit) {
    val feedback = LocalAppFeedback.current
    LaunchedEffect(message, isError) {
        if (message != null) {
            feedback.show(message, isError = isError)
            onConsumed()
        }
    }
}
