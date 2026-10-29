package com.zsz.studyassistant.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun HomeCaptureButton(multiple: Boolean, onToggle: () -> Unit, onCapture: (Boolean) -> Unit) {
    val s = LocalStrings.current
    val latestOnCapture by rememberUpdatedState(onCapture)
    val haptics = LocalHapticFeedback.current
    val latestHaptics by rememberUpdatedState(haptics)
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    var cancelled by remember { mutableStateOf(false) }
    Surface(shape = smoothPill(), color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.fillMaxWidth().height(64.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight()
                .clickable(role = Role.Button) { latestOnCapture(false) }
                .semantics(mergeDescendants = true) {
                    onLongClick(label = s["home.camera.grade"]) { latestOnCapture(true); true }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(); down.consume()
                        val start = SystemClock.uptimeMillis()
                        var released = false
                        cancelled = false; armed = false; progress = 0f
                        val timer = scope.launch {
                            while (!cancelled) {
                                progress = ((SystemClock.uptimeMillis() - start) / 1000f).coerceIn(0f, 1f)
                                if (progress >= 1f) {
                                    armed = true
                                    latestHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    break
                                }
                                delay(16)
                            }
                        }
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed || event.changes.count { it.pressed } > 1 ||
                                    down.position.y - change.position.y > 36.dp.toPx() ||
                                    change.position.x !in 0f..size.width.toFloat()) cancelled = true
                                change.consume()
                                if (!change.pressed) { released = true; break }
                            }
                            if (released && !cancelled) {
                                if (armed) latestOnCapture(true)
                                else if (SystemClock.uptimeMillis() - start < 200L) latestOnCapture(false)
                            }
                        } finally {
                            timer.cancel(); progress = 0f; armed = false; cancelled = false
                        }
                    }
                }, contentAlignment = Alignment.Center) {
                if (progress > 0f) Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress)
                    .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = if (cancelled) .1f else .22f)))
                Text(s[if (cancelled) "home.camera.cancel" else if (armed) "home.camera.grade" else "home.camera"],
                    fontSize = 20.sp, maxLines = 1)
            }
            Box(Modifier.width(1.dp).height(30.dp).background(MaterialTheme.colorScheme.onPrimary.copy(alpha = .4f)))
            Box(Modifier.width(76.dp).fillMaxHeight().clickable(role = Role.Button, onClick = onToggle),
                contentAlignment = Alignment.Center) {
                Text(s[if (multiple) "home.camera.multiple" else "camera.mode.one"], fontSize = 14.sp, maxLines = 1)
            }
        }
    }
}
