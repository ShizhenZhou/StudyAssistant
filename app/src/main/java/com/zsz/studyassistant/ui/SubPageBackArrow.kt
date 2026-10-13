package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 全应用**统一的子页面返回箭头**（用户 2026-10-13 要求）。
 *
 * 样式取自解题页（SolveScreen）左上角那个"美观大气"的箭头：
 * **21sp 的「←」**（[SUBPAGE_TITLE]）+ 左右各 4dp 的紧凑内边距 —— 内边距收紧让标题更靠近箭头。
 *
 * ⚠️ 以后新增子页面请直接用这个组件，**不要再各写各的** `Text("←", fontSize = ...)`：
 * 各页面分叉过（设置子页面曾漏写 fontSize → 箭头又小、又因为容器 16dp 内边距显得靠右）。
 *
 * @param onClick 返回动作（通常是 `{ nav.popBackStack() }`）
 */
@Composable
internal fun SubPageBackArrow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        Text("←", fontSize = SUBPAGE_TITLE)
    }
}
