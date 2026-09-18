package com.zsz.studyassistant.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * 苹果式**连续圆角**（倒 R 角 / squircle）。
 *
 * 普通 `RoundedCornerShape` 用圆弧做角，在"直线 → 圆弧"交接处曲率突变，视觉上有个硬接点；
 * 这里改用**三次贝塞尔**并把控制点向角内收（`smoothing` 越大收得越多）：
 * 曲率从直线段的 0 平滑长起来，交接处不再突变 —— 就是 iOS 那种"方中带圆"的手感。
 *
 * smoothing = 0 时退化为接近圆弧的观感；0.6 左右接近 iOS；越大越方。
 */
class SmoothCornerShape(
    private val radius: Dp,
    private val smoothing: Float = 0.6f
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val r = (with(density) { radius.toPx() })
            .coerceAtMost(minOf(size.width, size.height) / 2f)
            .coerceAtLeast(0f)
        return Outline.Generic(smoothRoundRectPath(size.width, size.height, r, smoothing))
    }
}

/** 胶囊形（半径 = 短边一半）的连续圆角，用于按钮 */
class SmoothPillShape(private val smoothing: Float = 0.6f) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val r = (minOf(size.width, size.height) / 2f).coerceAtLeast(0f)
        return Outline.Generic(smoothRoundRectPath(size.width, size.height, r, smoothing))
    }
}

/** 构造"连续圆角矩形"路径：每个角一段三次贝塞尔 */
private fun smoothRoundRectPath(w: Float, h: Float, rIn: Float, smoothing: Float): Path {
    val p = Path()
    if (w <= 0f || h <= 0f) return p
    val r = rIn.coerceIn(0f, minOf(w, h) / 2f)
    if (r <= 0.01f) {
        p.addRect(androidx.compose.ui.geometry.Rect(0f, 0f, w, h))
        return p
    }
    // 角的起点沿边外扩、控制点向角内收：e 越大越"方中带圆"（越苹果）。
    // ★ 归一化：半径先按 (1+e) 缩放，保证曲线占用长度恒等于 r；
    //   否则胶囊形按钮（r = 短边一半）上下两角的曲线会互相穿插，交接处出现毛刺。
    // ★ 自适应：越接近胶囊（r 越接近短边一半）越减弱平滑量，让左右两端更圆润。
    val pillness = (r / (minOf(w, h) / 2f)).coerceIn(0f, 1f)
    val e = smoothing.coerceIn(0f, 1f) * 0.45f * (1f - 0.5f * pillness)
    val rEff = r / (1f + e)
    val ext = rEff * (1f + e)        // == r，永不超出半径
    val k = rEff * (1f - e) * 0.5523f // 0.5523 ≈ 圆弧标准控制点系数

    p.moveTo(ext, 0f)
    p.lineTo(w - ext, 0f)
    // 右上角
    p.cubicTo(w - k, 0f, w, k, w, ext)
    p.lineTo(w, h - ext)
    // 右下角
    p.cubicTo(w, h - k, w - k, h, w - ext, h)
    p.lineTo(ext, h)
    // 左下角
    p.cubicTo(k, h, 0f, h - k, 0f, h - ext)
    p.lineTo(0f, ext)
    // 左上角
    p.cubicTo(0f, k, k, 0f, ext, 0f)
    p.close()
    return p
}

/** 固定半径的连续圆角（卡片用） */
fun smoothShape(radius: Dp = 14.dp, smoothing: Float = 0.6f): Shape =
    SmoothCornerShape(radius, smoothing)

/** 胶囊形连续圆角（按钮用） */
fun smoothPill(smoothing: Float = 0.6f): Shape = SmoothPillShape(smoothing)
