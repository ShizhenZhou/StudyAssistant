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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
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
            measurer.measure(s["solve.deselectAll"], style).size.width).toDp() + 8.dp
    }
    val color by animateColorAsState(if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, tween(140))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onToggleAll, modifier = Modifier.widthIn(min = width), contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(s[if (allSelected) "solve.deselectAll" else "solve.selectAll"], style = style, maxLines = 1, softWrap = false)
        }
        Text(s.format(countKey, "n" to "$count"), fontSize = 12.sp, fontWeight = FontWeight.Medium,
            color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
internal fun SelectionMark(
    selected: Boolean, modifier: Modifier = Modifier, accent: Color = Color(0xFFF44336),
    idleBorder: Color = Color(0xFF9E9E9E), idleFill: Color = Color.Transparent
) {
    val fill by animateColorAsState(if (selected) accent else idleFill, tween(140))
    val border by animateColorAsState(if (selected) accent else idleBorder, tween(140))
    Box(modifier.size(22.dp).background(fill, CircleShape).border(2.dp, border, CircleShape), contentAlignment = Alignment.Center) {
        if (selected) Text("✓", color = Color.White, fontSize = 13.sp)
    }
}
