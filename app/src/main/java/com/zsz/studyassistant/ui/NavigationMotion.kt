package com.zsz.studyassistant.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween

internal fun pageEnterTransition(from: String?, to: String?, pop: Boolean = false, fastReturn: Boolean = false): EnterTransition {
    if (fastReturn) return fadeIn(tween(90)) + slideInHorizontally(tween(90)) { -it / 16 }
    val fromIndex = AppNav.rootTabs.indexOf(from)
    val toIndex = AppNav.rootTabs.indexOf(to)
    if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
        val direction = if (toIndex > fromIndex) 1 else -1
        return slideInHorizontally(tween(220)) { direction * it }
    }
    if (toIndex >= 0 && from != null && fromIndex < 0 && AppNav.rootTab(from) == to) {
        return fadeIn(tween(90)) + slideInHorizontally(tween(90)) { -it / 16 }
    }
    return fadeIn(tween(180)) + slideInHorizontally(tween(180)) { (if (pop) -1 else 1) * it / 20 }
}

internal fun pageExitTransition(from: String?, to: String?, pop: Boolean = false, fastReturn: Boolean = false): ExitTransition {
    if (fastReturn) return fadeOut(tween(90)) + slideOutHorizontally(tween(90)) { it / 16 }
    val fromIndex = AppNav.rootTabs.indexOf(from)
    val toIndex = AppNav.rootTabs.indexOf(to)
    if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
        val direction = if (toIndex > fromIndex) 1 else -1
        return slideOutHorizontally(tween(220)) { -direction * it }
    }
    if (toIndex >= 0 && from != null && fromIndex < 0 && AppNav.rootTab(from) == to) {
        return fadeOut(tween(90)) + slideOutHorizontally(tween(90)) { it / 16 }
    }
    return fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { (if (pop) 1 else -1) * it / 20 }
}
