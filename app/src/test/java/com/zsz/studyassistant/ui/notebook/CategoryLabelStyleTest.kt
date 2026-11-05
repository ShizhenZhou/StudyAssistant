package com.zsz.studyassistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「📁 分类」按钮的名字排版决策（用户 2026-10-11 提出、2026-10-12 修订）。
 *
 * 规则（见 [categoryLabelStyle]）：
 *   · 名字 ≤ [CATEGORY_FULL_CHARS]（5）字 → 基准字号**单行**（emoji 固定同字号，右侧留白补占位）；
 *   · 多于 5 字 → **不许出现「…」**：先在**单行**里逐档调小字号（13→12→11→10→9），
 *     连 9sp 单行都放不下才允许**第二行**；
 *   · 按钮占位固定 = 「📁 + 5 个汉字」宽 × 「⏸ 中止 / 🔄 重新生成」实测高。
 *
 * 能否放下由 [categoryLabelStyle] 的 `fits` 回调注入，所以这里用假实现精确验证决策顺序，
 * 不需要 Compose 运行时。
 */
class CategoryLabelStyleTest {

    @Test
    fun `五个字以内用基准字号单行`() {
        val neverFits: (Int, Int) -> Boolean = { _, _ -> false }
        for (n in 0..CATEGORY_FULL_CHARS) {
            assertEquals("len=$n", BASE_CATEGORY_SP to 1, categoryLabelStyle(n, neverFits))
        }
    }

    @Test
    fun `多于五个字先在单行里逐档缩小`() {
        // 假设 11sp 单行刚好放得下 → 要拿到"能放下的最大字号"，即 12 试不过、11 通过
        val fits = { sp: Int, lines: Int -> lines == 1 && sp <= 11 }
        assertEquals(11 to 1, categoryLabelStyle(6, fits))
        assertEquals(11 to 1, categoryLabelStyle(30, fits))   // 再长也不换行，只要单行能放下
    }

    @Test
    fun `单行连最小字号都放不下才允许两行`() {
        val fits = { sp: Int, lines: Int -> lines == 2 }
        assertEquals(12 to 2, categoryLabelStyle(8, fits))    // 两行也优先用较大的字号
        assertEquals(12 to 2, categoryLabelStyle(40, fits))
    }

    @Test
    fun `完全没有可行组合时兜底为最小字号两行（不出省略号）`() {
        val neverFits: (Int, Int) -> Boolean = { _, _ -> false }
        assertEquals(CATEGORY_SHRINK_SP.last() to 2, categoryLabelStyle(40, neverFits))
    }

    @Test
    fun `决策顺序：大字号优先，单行优先于两行`() {
        val tried = mutableListOf<Pair<Int, Int>>()
        val fits = { sp: Int, lines: Int ->
            tried += sp to lines
            false
        }
        categoryLabelStyle(9, fits)
        // 先按 12/11/10/9 试单行，再按 12/11/10/9 试两行
        assertEquals(
            listOf(12 to 1, 11 to 1, 10 to 1, 9 to 1, 12 to 2, 11 to 2, 10 to 2, 9 to 2),
            tried
        )
    }

    @Test
    fun `emoji 字号任何时候都等于顶栏基准字号（不随名字缩放）`() {
        assertEquals(13, BTN_LABEL_SOLVE.value.toInt())
        assertEquals(13, BASE_CATEGORY_SP)
        assertTrue("缩小档位必须是严格递减的候选值", CATEGORY_SHRINK_SP == CATEGORY_SHRINK_SP.sortedDescending())
        assertTrue("缩小档位都不应大于基准字号", CATEGORY_SHRINK_SP.all { it < BASE_CATEGORY_SP })
    }
}
