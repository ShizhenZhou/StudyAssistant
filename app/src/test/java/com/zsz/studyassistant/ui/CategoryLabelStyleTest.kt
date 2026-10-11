package com.zsz.studyassistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「📁 分类」按钮的字号档位（用户 2026-10-11 要求）。
 *
 * 规则：名字 ≤4 字用顶栏基准字号（右侧留白把占位补齐，emoji 不挪位）；
 * 超过 4 字逐档缩小；名字很长时允许折成两行。按钮本身的宽高在界面上锁死为
 * 「⏸ 中止 / 🔄 重新生成」按钮的实测尺寸，所以这里只测字号/行数这层纯逻辑。
 */
class CategoryLabelStyleTest {

    @Test
    fun `四个字以内用顶栏基准字号`() {
        for (n in 0..4) assertEquals("len=$n", 13, categoryLabelSp(n))
    }

    @Test
    fun `超过四个字开始逐档缩小`() {
        assertEquals(12, categoryLabelSp(5))
        assertEquals(12, categoryLabelSp(6))
        assertEquals(11, categoryLabelSp(7))
        assertEquals(11, categoryLabelSp(8))
        assertEquals(10, categoryLabelSp(9))
        assertEquals(10, categoryLabelSp(11))
        assertEquals(9, categoryLabelSp(12))
        assertEquals(9, categoryLabelSp(40))
    }

    @Test
    fun `字号随名字变长单调不增`() {
        var prev = categoryLabelSp(0)
        for (n in 1..64) {
            val cur = categoryLabelSp(n)
            assertTrue("len=$n 时字号不应变大：$prev → $cur", cur <= prev)
            prev = cur
        }
    }

    @Test
    fun `名字够长才允许两行`() {
        for (n in 0..8) assertEquals("len=$n", 1, categoryLabelMaxLines(n))
        for (n in 9..40) assertEquals("len=$n", 2, categoryLabelMaxLines(n))
    }

    @Test
    fun `基准字号与顶栏按钮一致（13sp）`() {
        assertEquals(13, BTN_LABEL_SOLVE.value.toInt())
    }
}
