package com.zsz.studyassistant.ui

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
import com.zsz.studyassistant.data.AutoBackup
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
) : SnackbarVisuals {
    override val withDismissAction = false
    override val duration = if (isError) SnackbarDuration.Long else SnackbarDuration.Short
}

@Stable
class AppFeedbackState(private val scope: CoroutineScope) {
    val hostState = SnackbarHostState()
    var saveRecoveryRequested by mutableStateOf(false)
    private var displayJob: Job? = null
    private var activeVisuals: FeedbackVisuals? = null

    fun show(message: String, isError: Boolean = false, source: String = "",
             actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        val visuals = FeedbackVisuals(message, isError, source, actionLabel)
        val current = activeVisuals
        // Never bury an actionable failure under success messages or build a stale queue.
        if (current == visuals || (current?.isError == true && !isError)) return
        displayJob?.cancel()
        activeVisuals = visuals
        displayJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (hostState.showSnackbar(visuals) == SnackbarResult.ActionPerformed) onAction?.invoke()
            } finally {
                if (activeVisuals === visuals) activeVisuals = null
            }
        }
    }

    fun dismiss(source: String) {
        if (activeVisuals?.source == source) {
            displayJob?.cancel()
        }
    }

    fun backupResult(result: AutoBackup.Result, strings: Strings) {
        if (result.ok) show(strings["feedback.backupDone"], source = "backup")
        else show(strings.format("data.autoBackup.failed", "msg" to
            strings[result.errorKey ?: "data.autoBackup.storageFailed"]), isError = true, source = "backup")
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
    CompositionLocalProvider(LocalAppFeedback provides state) {
        Surface(Modifier.fillMaxSize()) {
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
        SnackbarHost(
            hostState = state.hostState,
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = maxHeight * 0.18f)
                .padding(horizontal = 24.dp).widthIn(max = 480.dp)
        ) { data ->
            val failure = (data.visuals as? FeedbackVisuals)?.isError == true
            val errorRed = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f)
                Color(0xFFFF8A80) else Color(0xFFB3261E)
            Snackbar(
                snackbarData = data,
                shape = RoundedCornerShape(28.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (failure) errorRed else MaterialTheme.colorScheme.onSurface,
                actionColor = if (failure) errorRed else MaterialTheme.colorScheme.primary,
            )
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
