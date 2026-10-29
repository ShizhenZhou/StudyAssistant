package com.zsz.studyassistant.ui

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal const val ROOT_BACK_HINT_MS = 2000L

/** Monotonic clock window, deliberately not saved across recreation or backgrounding. */
internal class RootBackExitWindow {
    private var armedUntil: Long? = null
    fun commit(now: Long): Boolean {
        if (armedUntil?.let { now < it } == true) {
            reset()
            return true
        }
        armedUntil = now + ROOT_BACK_HINT_MS
        return false
    }
    fun reset() { armedUntil = null }
}

/** Register after NavHost; nested pages disable this callback and retain their own Back. */
@Composable
internal fun RootBackExitHandler(enabled: Boolean, pageKey: String?, onExit: () -> Unit) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher ?: return
    val owner = LocalLifecycleOwner.current
    val feedback = LocalAppFeedback.current
    val strings = LocalStrings.current
    val window = remember { RootBackExitWindow() }
    val commit by rememberUpdatedState<(Boolean) -> Unit> { gesture ->
        if (window.commit(android.os.SystemClock.elapsedRealtime())) {
            feedback.clearBackExitHint()
            onExit()
        } else {
            feedback.showBackExitHint(strings[if (gesture) "back.exit.swipeAgain" else "back.exit.pressAgain"])
        }
    }
    val callback = remember(dispatcher, owner) {
        object : OnBackPressedCallback(false) {
            private var gesture = false
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                gesture = backEvent.swipeEdge == BackEventCompat.EDGE_LEFT || backEvent.swipeEdge == BackEventCompat.EDGE_RIGHT
            }
            override fun handleOnBackProgressed(backEvent: BackEventCompat) { handleOnBackStarted(backEvent) }
            override fun handleOnBackCancelled() { gesture = false }
            override fun handleOnBackPressed() {
                val wasGesture = gesture
                gesture = false
                commit(wasGesture)
            }
        }
    }
    SideEffect { callback.isEnabled = enabled }
    LaunchedEffect(enabled, pageKey) {
        window.reset()
        feedback.clearBackExitHint()
    }
    DisposableEffect(dispatcher, owner, callback) {
        dispatcher.addCallback(owner, callback)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                window.reset()
                feedback.clearBackExitHint()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            callback.remove()
            owner.lifecycle.removeObserver(observer)
            feedback.clearBackExitHint()
        }
    }
}
