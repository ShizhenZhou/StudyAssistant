package com.zsz.studyassistant.ui

import androidx.compose.ui.geometry.Rect

/**
 * 框选页的**纯数学**（从 `CropScreen.kt` 抽出来单独放一个文件）。
 *
 * 独立于框选页面的 UI 初始化；纯函数只依赖 `Rect`，便于在 JVM 单测中验证几何边界。
 */
internal const val MIN_SIZE = 60f  // 选框最小尺寸(px)

/**
 * 安全钳制：把 v 夹到 `[a, b]`；**区间非法（a > b）时返回原值 v**（保持不动、不跳），
 * 绝不抛 `IllegalArgumentException`（曾导致"拖动选框时应用自动退出"）。
 *
 * ★ 2026-10-13 修「上边框偶尔蹦到最上边」：原来非法区间返回**中点** `(a + b) / 2` ——
 *   当选框已被压到小于 [MIN_SIZE]（或比图片还大）时，这个中点会算到**图片边界之外** →
 *   拖动上边手柄时上边就"蹦"到图片顶边上方（左/右/下边与整框拖动同理）。
 *   现在非法时**不动**；同时各调用点都把夹取区间夹成合法（`coerceAtLeast/coerceAtMost`，见拖动分支），双保险。
 *   注：`coerceIn` 只在 a > b 时抛异常，"返回 v" 天然不会抛 → 老坑不会复发。
 */
internal fun safeCoerceRange(v: Float, a: Float, b: Float): Float =
    if (a <= b) v.coerceIn(a, b) else v

/**
 * 把选框夹回图片显示范围，并在图片够大时保证不小于 [MIN_SIZE]。
 *
 * 用在每一次"整框被重写"之后：换图（`dispRect` 同步）、自动框选写回、缩放手势结束。
 * 这样 `sel` 永远不会处于"比图片大 / 比 MIN_SIZE 小 / 跑到图片外"的非法几何，
 * 拖动时的夹取区间也就不会塌缩（这正是"上边被钉住、完全不跟手"的土壤）。
 */
internal fun normalizeSelRect(r: Rect, d: Rect): Rect {
    if (d.width < 20f || d.height < 20f) return r
    val l = r.left.coerceIn(d.left, (d.right - MIN_SIZE).coerceAtLeast(d.left + 10f))
    val t = r.top.coerceIn(d.top, (d.bottom - MIN_SIZE).coerceAtLeast(d.top + 10f))
    val rr = r.right.coerceIn((l + MIN_SIZE).coerceAtMost(d.right), d.right)
    val bb = r.bottom.coerceIn((t + MIN_SIZE).coerceAtMost(d.bottom), d.bottom)
    return Rect(l, t, rr, bb)
}
