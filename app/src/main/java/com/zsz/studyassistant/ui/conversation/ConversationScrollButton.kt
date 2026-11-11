package com.zsz.studyassistant.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun ConversationScrollButton(atTop: Boolean, onTop: () -> Unit, onBottom: () -> Unit) {
    val s = LocalStrings.current
    val top by rememberUpdatedState(onTop)
    val bottom by rememberUpdatedState(onBottom)
    var armed by remember { mutableStateOf(false) }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .92f),
        shadowElevation = 3.dp, modifier = Modifier.size(44.dp).semantics {
            role = Role.Button
            contentDescription = s[if (atTop || armed) "solve.jumpBottom" else "solve.jumpTop"]
            onClick { if (atTop) bottom() else top(); true }
            if (!atTop) onLongClick(label = s["solve.jumpBottom"]) { bottom(); true }
        }.pointerInput(atTop) {
            coroutineScope {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var cancelled = false
                    armed = false
                    val timer = if (!atTop) launch { delay(1_000); if (!cancelled) armed = true } else null
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.singleOrNull { it.id == down.id }
                            if (change == null || event.changes.size != 1 || change.isConsumed ||
                                change.position.x < 0 || change.position.x >= size.width ||
                                change.position.y < 0 || change.position.y >= size.height) {
                                cancelled = true
                                break // Leaving once cancels, even if the finger returns before release.
                            }
                            change.consume()
                            if (!change.pressed) {
                                if (atTop || armed) bottom() else top()
                                break
                            }
                        }
                    } finally { cancelled = true; timer?.cancel(); armed = false }
                }
            }
        }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (atTop || armed) "↓" else "↑", fontSize = 19.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
