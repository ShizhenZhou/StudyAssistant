package com.zsz.studyassistant.data

import android.graphics.BitmapFactory

/**
 * 本地「预框选」检测：把图片按**文本块**切分，找出最可能是题目的区域。
 *
 * 纯本地、毫秒级、零成本；对白底印刷题 / 作业 App 截图效果好，对斜拍手写照片一般
 * （那种场景由调用方叠加 AI 视觉结果兜底）。
 *
 * 算法：灰度化 → Otsu 自适应阈值 → 逐行暗像素投影得文本行 → 按行间空隙合并成「块」
 * → 取块内暗像素的左右边界收紧 → 归一到 0~1 相对坐标。
 */
object ImageAutoCrop {

    /** 归一化矩形（相对整张图，x/y 为左上角） */
    data class NormRect(val x: Float, val y: Float, val w: Float, val h: Float) {
        val area: Float get() = w * h
        /** 本地结果是否"可疑"（几乎整页 / 只有一行 / 太窄）——可疑时更应采纳 AI 的结果 */
        /**
     * 是否"贴到照片边界"。
     * AI 给的框一旦贴边（x/y≈0 或 x+w/y+h≈1），往往意味着它没框准（把整页当一题），
     * 而且这类框在拖动/裁剪时更容易踩到边界条件。策略：宁可丢弃它，回退到默认框。
     */
    fun touchesEdge(margin: Float = 0.012f): Boolean =
        x <= margin || y <= margin || (x + w) >= (1f - margin) || (y + h) >= (1f - margin)
    fun looksUnreliable(): Boolean = h > 0.72f || h < 0.055f || w < 0.30f
    }

    /** 按文本块切分，返回按面积从大到小排序的归一化矩形 */
    fun detectBlocks(path: String, maxDim: Int = 600): List<NormRect> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return emptyList()
        val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / maxDim)
        val bmp = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return emptyList()

        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val gray = IntArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            gray[i] = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }

        // ── Otsu 自适应阈值（对拍照的明暗不均比固定阈值稳） ──
        val hist = IntArray(256)
        for (g in gray) hist[g]++
        val total = gray.size
        var sum = 0.0
        for (i in 0..255) sum += i.toDouble() * hist[i]
        var sumB = 0.0
        var wB = 0
        var maxVar = 0.0
        var thr = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val v = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (v > maxVar) { maxVar = v; thr = t }
        }
        val darkThr = minOf(thr, 200)

        // ── 逐行暗像素占比 → 文本行 ──
        val rowDark = FloatArray(h)
        for (y in 0 until h) {
            var d = 0
            val base = y * w
            for (x in 0 until w) if (gray[base + x] < darkThr) d++
            rowDark[y] = d.toFloat() / w
        }
        val lines = ArrayList<IntArray>()
        var st = -1
        for (y in 0 until h) {
            if (rowDark[y] > 0.015f) {
                if (st < 0) st = y
            } else if (st >= 0) {
                lines += intArrayOf(st, y - 1); st = -1
            }
        }
        if (st >= 0) lines += intArrayOf(st, h - 1)
        if (lines.isEmpty()) return emptyList()

        // ── 合并成块：行间空隙 > 2.6 × 平均行高 才分段 ──
        // （取 2.6 是为了把「题干 + 其下方选项」归到同一块；太大则整页会连成一块）
        val avgLineH = lines.map { it[1] - it[0] + 1 }.average().toFloat().coerceAtLeast(4f)
        val gapThr = (avgLineH * 2.6f).toInt().coerceAtLeast(10)
        val blocks = ArrayList<IntArray>()
        var top = lines[0][0]
        var bottom = lines[0][1]
        for (i in 1 until lines.size) {
            val (a, b) = lines[i]
            if (a - bottom > gapThr) {
                blocks += intArrayOf(top, bottom); top = a; bottom = b
            } else bottom = b
        }
        blocks += intArrayOf(top, bottom)

        // ── 每块收紧左右边界 ──
        val out = ArrayList<NormRect>()
        for (blk in blocks) {
            val t = blk[0]
            val b = blk[1]
            var minX = w
            var maxX = -1
            for (y in t..b) {
                val base = y * w
                for (x in 0 until w) {
                    if (gray[base + x] < darkThr) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                    }
                }
            }
            if (maxX < 0) continue
            val padX = (w * 0.012f).toInt()
            val padY = (h * 0.006f).toInt()
            val x0 = (minX - padX).coerceAtLeast(0)
            val x1 = (maxX + padX).coerceAtMost(w - 1)
            val y0 = (t - padY).coerceAtLeast(0)
            val y1 = (b + padY).coerceAtMost(h - 1)
            out += NormRect(
                x0.toFloat() / w,
                y0.toFloat() / h,
                (x1 - x0 + 1).toFloat() / w,
                (y1 - y0 + 1).toFloat() / h
            )
        }
        return out.sortedByDescending { it.area }
    }

    /**
     * 选出最像"题目"的一块。
     * 不能只看面积：状态栏/页眉/底部导航/「提交」按钮都很长很扁，面积未必小。
     * 因此按「面积 × 中段权重 × 边缘惩罚」打分：
     *  - 越靠近页面中部权重越高（题目通常在中间）
     *  - 贴上下边缘的块显著降权（状态栏、底部导航、提交按钮、页脚页码）
     */
    fun detectQuestionRect(path: String): NormRect? {
        val blocks = detectBlocks(path)
        if (blocks.isEmpty()) return null
        val cand = blocks.filter { it.h in 0.02f..0.90f && it.w >= 0.20f }
        if (cand.isEmpty()) return blocks.firstOrNull()
        val best = cand.maxByOrNull { score(it) } ?: return null

        // ── 向上下"吸收"紧邻的块：把题干下方的选项 / 上方的「单选题」标签一起并入 ──
        // （行间空隙比块本身还大时才会分开，所以单看一个块往往只有一行）
        var top = best.y
        var bottom = best.y + best.h
        var left = best.x
        var right = best.x + best.w
        val maxGap = maxOf(0.055f, best.h * 2.5f)
        var grew = true
        var guard = 0
        while (grew && guard++ < 16) {
            grew = false
            for (b in blocks) {
                val bTop = b.y
                val bBottom = b.y + b.h
                val hOverlap = minOf(right, b.x + b.w) - maxOf(left, b.x) > 0.15f * (right - left)
                if (!hOverlap) continue
                val gapBelow = bTop - bottom
                val gapAbove = top - bBottom
                if (gapBelow >= -0.001f && gapBelow <= maxGap) {
                    bottom = maxOf(bottom, bBottom); left = minOf(left, b.x); right = maxOf(right, b.x + b.w); grew = true
                } else if (gapAbove >= -0.001f && gapAbove <= maxGap) {
                    top = minOf(top, bTop); left = minOf(left, b.x); right = maxOf(right, b.x + b.w); grew = true
                }
            }
        }
        // ── 向四周略微外扩：视觉更舒适，也给识别留出上下文（题号、单位、边距） ──
        // 外扩量按比例给：横向上限 2%、纵向上限 1.6%，且不低于块高的 12%（一行字的呼吸感）
        val padX = 0.02f
        val padY = maxOf(0.016f, (bottom - top) * 0.12f).coerceAtMost(0.05f)
        val l2 = (left - padX).coerceAtLeast(0f)
        val t2 = (top - padY).coerceAtLeast(0f)
        val r2 = (right + padX).coerceAtMost(1f)
        val b2 = (bottom + padY).coerceAtMost(1f)
        return NormRect(
            l2,
            t2,
            (r2 - l2).coerceIn(0.05f, 1f),
            (b2 - t2).coerceIn(0.03f, 1f)
        )
    }

    /** 像不像"题目块"：面积 × 中段权重 × 边缘惩罚（见 detectQuestionRect 注释） */
    private fun score(b: NormRect): Float {
        val centerY = b.y + b.h / 2f
        val centerScore = (1f - kotlin.math.abs(centerY - 0.5f) * 1.2f).coerceAtLeast(0.15f)
        val edgePenalty = when {
            b.y < 0.04f || b.y + b.h > 0.96f -> 0.35f   // 状态栏 / 底部导航
            b.y < 0.08f || b.y + b.h > 0.90f -> 0.70f   // 页眉 / 「提交」按钮附近
            else -> 1f
        }
        return b.area * centerScore * edgePenalty
    }
}
