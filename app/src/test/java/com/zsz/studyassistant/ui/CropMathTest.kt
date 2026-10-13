package com.zsz.studyassistant.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 框选页的纯数学 —— 用户 2026-10-13 报「上边框偶尔蹦到最上边」之后补的回归测试。
 *
 * 两条关键约定（都在 `ui/CropScreen.kt` 文件级）：
 *  · [safeCoerceRange]：区间非法（a > b）时**返回原值**（保持不动、不跳），且绝不抛异常
 *    —— 旧实现返回中点 `(a+b)/2`，框被压到小于 [MIN_SIZE] 时它会算到**图片边界之外**，
 *    于是拖上边手柄时上边"蹦"到图片顶边上方（这就是那个 bug 的数学根因）。
 *  · [normalizeSelRect]：把选框夹回图片显示范围，并在图片够大时保证不小于 [MIN_SIZE]
 *    —— 换图 / 自动框写回 / 缩放结束后都要调用，避免 `sel` 处于非法几何。
 */
class CropMathTest {

    @Test
    fun `区间合法时正常夹取`() {
        assertEquals(5f, safeCoerceRange(5f, 0f, 10f), 0.001f)
        assertEquals(0f, safeCoerceRange(-3f, 0f, 10f), 0.001f)
        assertEquals(10f, safeCoerceRange(99f, 0f, 10f), 0.001f)
    }

    @Test
    fun `区间非法时返回原值而不是中点（蹦顶回归）`() {
        // 旧实现会返回 (100 + (-10)) / 2 = 45f —— 比图片顶边(100)还靠上，上边因此"蹦顶"
        assertEquals(120f, safeCoerceRange(120f, 100f, -10f), 0.001f)
        // a == b（区间退化成一个点）时返回该点
        assertEquals(7f, safeCoerceRange(3f, 7f, 7f), 0.001f)
    }

    @Test
    fun `normalizeSelRect 把跑到图片外的框拉回图片内`() {
        val d = Rect(0f, 100f, 1000f, 1800f)   // 图片显示区：上边在 y=100
        val r = Rect(-50f, 20f, 900f, 1500f)   // 选框跑到图片上方/左侧
        val n = normalizeSelRect(r, d)
        assertEquals(0f, n.left, 0.001f)
        assertEquals(100f, n.top, 0.001f)      // ← 旧代码这段夹取算出的上边会落在图片上方
        assertTrue(n.right <= d.right + 0.001f)
        assertTrue(n.bottom <= d.bottom + 0.001f)
    }

    @Test
    fun `normalizeSelRect 保证不小于最小尺寸`() {
        val d = Rect(0f, 0f, 1000f, 2000f)
        val tiny = Rect(500f, 500f, 505f, 505f)      // 5×5 的极小框
        val n = normalizeSelRect(tiny, d)
        assertTrue("宽应 >= MIN_SIZE，实际 ${n.width}", n.width >= MIN_SIZE - 0.001f)
        assertTrue("高应 >= MIN_SIZE，实际 ${n.height}", n.height >= MIN_SIZE - 0.001f)
        assertTrue(n.left >= d.left && n.top >= d.top && n.right <= d.right && n.bottom <= d.bottom)
    }

    @Test
    fun `图片退化（过小）时原样返回`() {
        val degenerate = Rect(10f, 10f, 15f, 15f)    // 宽高都 < 20px
        val r = Rect(0f, 0f, 100f, 100f)
        assertEquals(r, normalizeSelRect(r, degenerate))
    }
}
