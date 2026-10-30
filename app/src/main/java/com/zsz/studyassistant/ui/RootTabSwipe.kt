package com.zsz.studyassistant.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Buttons consume down first; vertical scroll and system Back keep ownership of their gestures. */
@Composable
internal fun Modifier.rootTabSwipe(route: String?, enabled: Boolean, onSelect: (String) -> Unit): Modifier {
    val select by rememberUpdatedState(onSelect)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.systemGestures
    val leftEdge = maxOf(insets.getLeft(density, direction), with(density) { 24.dp.roundToPx() })
    val rightEdge = maxOf(insets.getRight(density, direction), with(density) { 24.dp.roundToPx() })
    return pointerInput(route, enabled, leftEdge, rightEdge) {
        if (!enabled) return@pointerInput
        val threshold = 56.dp.toPx()
        awaitEachGesture {
            // Main pass reaches children first. Never turn a button press into a tab swipe.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            if (down.isConsumed || down.type != PointerType.Touch ||
                down.position.x < leftEdge || down.position.x > size.width - rightEdge
            ) return@awaitEachGesture
            var dragging = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.changes.size != 1) break
                val change = event.changes.single()
                if (change.id != down.id || change.isConsumed) break
                val delta = change.position - down.position
                if (!dragging) {
                    if (abs(delta.y) > viewConfiguration.touchSlop) break
                    if (abs(delta.x) > viewConfiguration.touchSlop && abs(delta.x) > abs(delta.y) * 1.5f) {
                        if (AppNav.adjacentRootTab(route, next = delta.x < 0f) == null) break
                        dragging = true
                    }
                }
                if (dragging) change.consume()
                if (!change.pressed) {
                    if (dragging && abs(delta.x) >= threshold && abs(delta.x) > abs(delta.y) * 1.5f) {
                        AppNav.adjacentRootTab(route, next = delta.x < 0f)?.let(select)
                    }
                    break
                }
            }
        }
    }
}
