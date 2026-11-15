package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Keep the content at one composition site when a window changes between bottom bar and rail. */
@Composable
internal fun AdaptiveMainLayout(showNavigation: Boolean, route: String?, onSelect: (String) -> Unit,
    content: @Composable (bottomBar: Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rail = showNavigation && maxWidth >= 600.dp && maxHeight >= 320.dp
        Row(Modifier.fillMaxSize()) {
            if (rail) MainNavigation(route, onSelect, rail = true)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth().then(if (rail)
                    Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)) else Modifier)) {
                    content(showNavigation && !rail)
                }
                if (showNavigation && !rail) MainNavigation(route, onSelect, rail = false)
            }
        }
    }
}

@Composable
private fun MainNavigation(route: String?, onSelect: (String) -> Unit, rail: Boolean) {
    val strings = LocalStrings.current
    val tabs = listOf(AppNav.HOME to "nav.learn", AppNav.STATS to "nav.stats", AppNav.SETTINGS to "nav.settings")
    val icon: @Composable (String) -> Unit = { tab ->
        Box {
            Text(when (tab) { AppNav.HOME -> "📚"; AppNav.STATS -> "📊"; else -> "⚙️" })
            if (tab == AppNav.SETTINGS && UpdateBadgeState.availableVersion != null) {
                UpdateBadgeDot(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp), 12.dp, 8.sp)
            }
        }
    }
    if (rail) {
        NavigationRail(Modifier.fillMaxHeight()) {
            Spacer(Modifier.weight(1f))
            tabs.forEach { (tab, label) ->
                NavigationRailItem(selected = AppNav.rootTab(route) == tab, onClick = { onSelect(tab) },
                    icon = { icon(tab) }, label = { Text(strings[label]) })
            }
            Spacer(Modifier.weight(1f))
        }
    } else {
        NavigationBar {
            tabs.forEach { (tab, label) ->
                NavigationBarItem(selected = AppNav.rootTab(route) == tab, onClick = { onSelect(tab) },
                    icon = { icon(tab) }, label = { Text(strings[label]) })
            }
        }
    }
}
