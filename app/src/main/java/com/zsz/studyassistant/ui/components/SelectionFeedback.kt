package com.zsz.studyassistant.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Reserve the wider label so Select all/Deselect all never shifts the count. */
@Composable
internal fun SelectionSummary(count: Int, allSelected: Boolean, countKey: String = "solve.selectedCount", onToggleAll: () -> Unit) {
    val s = LocalStrings.current
    val style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp)
    val measurer = rememberTextMeasurer()
    val width = with(LocalDensity.current) {
        maxOf(measurer.measure(s["solve.selectAll"], style).size.width,
            measurer.measure(s["solve.deselectAll"], style).size.width).toDp() + 30.dp
    }
    val color by animateColorAsState(if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, tween(140))
    val countText = s.format(countKey, "n" to "$count")
    val countStyle = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium)
    val countWidth = with(LocalDensity.current) { measurer.measure(countText, countStyle).size.width.toDp() }
    BoxWithConstraints {
        // Keep long localized labels readable when the title slot cannot fit the extra icon.
        val showToggleIcon = maxWidth >= width
        val toggleWidth = if (showToggleIcon) width else width - 22.dp
        val toggle: @Composable () -> Unit = {
            TextButton(onClick = onToggleAll, modifier = Modifier.widthIn(min = toggleWidth), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                AppIconLabel(s[if (allSelected) "solve.deselectAll" else "solve.selectAll"],
                    icon = if (!showToggleIcon) null else if (allSelected) AppIcon.Deselect else AppIcon.SelectAll, iconSize = 18.dp, iconSpacing = 4.dp, style = style, maxLines = 1, softWrap = false)
            }
        }
        val total: @Composable () -> Unit = {
            Text(countText, style = countStyle, color = color, maxLines = 1, softWrap = false,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (maxWidth >= maxOf(toggleWidth, 64.dp) + countWidth + 4.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                toggle(); total()
            }
        } else {
            // Large fonts and long translations use two lines instead of hiding the count.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Box(Modifier.height(28.dp)) { toggle() }
                }
                total()
            }
        }
    }
}

@Composable
internal fun SelectionMark(
    selected: Boolean, modifier: Modifier = Modifier, accent: Color = Color(0xFFF44336),
    idleBorder: Color = Color(0xFF9E9E9E), idleFill: Color = Color.Transparent
) {
    val fill by animateColorAsState(if (selected) accent else idleFill, tween(140))
    val border by animateColorAsState(if (selected) accent else idleBorder, tween(140))
    Box(modifier.semantics { this.selected = selected }.size(22.dp).background(fill, CircleShape).border(2.dp, border, CircleShape), contentAlignment = Alignment.Center) {
        if (selected) androidx.compose.foundation.Canvas(Modifier.size(14.dp)) {
            val unit = size.width / 14f
            val tick = androidx.compose.ui.graphics.Path().apply {
                moveTo(2.5f * unit, 7f * unit); lineTo(6f * unit, 10.5f * unit); lineTo(12f * unit, 3.5f * unit)
            }
            drawPath(tick, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(
                1.8f * unit, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}
