package com.zsz.studyassistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

private val ITEM_H = 40.dp
private const val VISIBLE = 5

/**
 * 自定义上下滑动滚轮时间选择器（时 / 分两列，中间高亮吸附）。
 * 替代系统的钟面 TimePicker。
 */
@Composable
fun WheelTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var hour by remember { mutableStateOf(initialHour) }
    var minute by remember { mutableStateOf(initialMinute) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("设置提醒时间", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WheelColumn(0..23, hour, { hour = it }, Modifier.width(76.dp))
                    Text(":", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 6.dp))
                    WheelColumn(0..59, minute, { minute = it }, Modifier.width(76.dp))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = { onConfirm(hour, minute) }) { Text("确定") }
                }
            }
        }
    }
}

@Composable
private fun WheelColumn(range: IntRange, selected: Int, onSelected: (Int) -> Unit, modifier: Modifier) {
    val items = remember(range) { range.toList() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (selected - range.first).coerceIn(0, items.size - 1))
    val fling = rememberSnapFlingBehavior(lazyListState = state)

    // 滚动停止时把"居中项"回报出去
    LaunchedEffect(state, items) {
        snapshotFlow { state.firstVisibleItemIndex }.collect { idx ->
            val v = range.first + idx
            if (v in range) onSelected(v)
        }
    }

    Box(modifier.height(ITEM_H * VISIBLE), contentAlignment = Alignment.Center) {
        // 中间高亮条
        Box(
            Modifier
                .fillMaxWidth()
                .height(ITEM_H)
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
        )
        LazyColumn(
            state = state,
            flingBehavior = fling,
            modifier = Modifier.height(ITEM_H * VISIBLE),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = ITEM_H * (VISIBLE / 2)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(items.size) { i ->
                val value = items[i]
                val centerIdx = state.firstVisibleItemIndex
                val isCenter = i == centerIdx
                Box(Modifier.height(ITEM_H).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "%02d".format(value),
                        style = if (isCenter) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCenter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}
