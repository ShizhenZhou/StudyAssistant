package com.zsz.studyassistant.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Live neighbour preview, without another navigation stack or offscreen page actions. */
@Composable
internal fun RootTabSwipeHost(
    route: String?, enabled: Boolean, modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
    preview: @Composable (String) -> Unit,
    content: @Composable (Modifier) -> Unit
) {
    var offset by remember(route) { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    var settling by remember(route) { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val select by rememberUpdatedState(onSelect)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.systemGestures
    val leftEdge = maxOf(insets.getLeft(density, direction), with(density) { 24.dp.roundToPx() })
    val rightEdge = maxOf(insets.getRight(density, direction), with(density) { 24.dp.roundToPx() })
    DisposableEffect(route, enabled) {
        onDispose { job?.cancel(); offset = 0f; settling = false }
    }
    fun finish(commit: Boolean) {
        val target = AppNav.adjacentRootTab(route, next = offset < 0f)
        val end = if (commit && target != null) (if (offset < 0f) -width else width).toFloat() else 0f
        settling = true
        job?.cancel()
        job = scope.launch {
            try {
                animate(offset, end, animationSpec = tween(180)) { value, _ -> offset = value }
                if (end != 0f && target != null) select(target)
            } finally { if (end == 0f) settling = false }
        }
    }
    val input = Modifier.pointerInput(route, enabled, leftEdge, rightEdge) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            if (settling || down.isConsumed || down.type != PointerType.Touch ||
                down.position.x < leftEdge || down.position.x > size.width - rightEdge) return@awaitEachGesture
            var dragging = false
            var released = false
            val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    if (event.changes.size != 1) break
                    val change = event.changes.single()
                    if (change.id != down.id || change.isConsumed) break
                    val delta = change.position - down.position
                    velocity.addPosition(change.uptimeMillis, change.position)
                    if (!dragging) {
                        if (abs(delta.y) > viewConfiguration.touchSlop) break
                        if (abs(delta.x) > viewConfiguration.touchSlop && abs(delta.x) > abs(delta.y) * 1.5f) {
                            if (AppNav.adjacentRootTab(route, next = delta.x < 0f) == null) break
                            dragging = true
                        }
                    }
                    if (dragging) {
                        change.consume()
                        offset = if (AppNav.adjacentRootTab(route, next = delta.x < 0f) != null)
                            delta.x.coerceIn(-size.width.toFloat(), size.width.toFloat()) else 0f
                    }
                    if (!change.pressed) {
                        released = true
                        if (dragging) {
                            val vx = velocity.calculateVelocity().x
                            val forwardFling = abs(vx) > 700.dp.toPx() && vx * offset > 0 && abs(offset) > 24.dp.toPx()
                            val reversing = abs(vx) > 300.dp.toPx() && vx * offset < 0
                            finish(!reversing && (abs(offset) >= size.width * .25f || forwardFling))
                        }
                        break
                    }
                }
            } finally {
                if (dragging && !released) finish(false)
            }
        }
    }
    Box(modifier.clipToBounds().onSizeChanged { width = it.width }.then(input)) {
        val neighbour = if (offset != 0f) AppNav.adjacentRootTab(route, next = offset < 0f) else null
        if (neighbour != null) Box(Modifier.fillMaxSize()
            .graphicsLayer { translationX = offset + if (offset < 0f) width else -width }
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }) { preview(neighbour) }
        content(Modifier.graphicsLayer { translationX = offset })
        if (settling) Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        })
    }
}
