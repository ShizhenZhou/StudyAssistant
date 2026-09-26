package com.zsz.studyassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B9 查重（题干归一化 + 命中判定）与 B6 批改结论判定的回归测试。
 *
 * 为什么测这里：
 *  · 查重**误报**会让用户觉得"存不进去"、**漏报**则等于没做；而"太短的题干不比"这类边界一旦写错，
 *    "1+1"就会跟一堆题撞车。
 *  · 结论判定错了，重做模式会把做对的题降档（复习计划直接乱掉），中文里"不正确"还包含"正确"，
 *    判定顺序反了就是必错。
 */
class DuplicateCheckTest {

    private fun q(id: Long, text: String, deleted: Boolean = false) =
        Question(id = id, text = text, answer = "", createdAt = 0L, deleted = deleted)

    // ── 归一化 ────────────────────────────────────────────────────────────

    @Test
    fun `归一化 去掉空白 大小写 常见标点 与 LaTeX 的美元符`() {
        // 注意：^ 这类"数学记号"**保留**（x^2 与 x2 不是一回事，不能归一化到一起）
        assertEquals("求定积分x^2dx", DuplicateCheck.normalize("求定积分 \$x^2 dx\$"))
        assertEquals("abc", DuplicateCheck.normalize(" A B C "))
        assertEquals("求导", DuplicateCheck.normalize("求导。"))
        assertEquals("求导", DuplicateCheck.normalize("求导，"))
        assertEquals("求导", DuplicateCheck.normalize("求导（ ）"))
        // 全角/半角标点与大小写都要能对上
        assertEquals(DuplicateCheck.normalize("Apple, 苹果"), DuplicateCheck.normalize("apple 苹果"))
    }

    // ── 命中判定 ──────────────────────────────────────────────────────────

    @Test
    fun `同题干（仅空白与标点不同）能命中`() {
        val existing = listOf(q(1, "求下列定积分的值：∫₀¹ x² dx。"))
        val hit = DuplicateCheck.find(existing, "求下列定积分的值 ∫₀¹  x² dx")
        assertNotNull("应该判定为重复", hit)
        assertEquals(1L, hit!!.id)
    }

    @Test
    fun `不同题干不命中`() {
        val existing = listOf(q(1, "求下列定积分的值：∫₀¹ x² dx"))
        assertNull(DuplicateCheck.find(existing, "证明勾股定理"))
    }

    @Test
    fun `太短的题干不参与查重（避免 1+1 之类误报）`() {
        val existing = listOf(q(1, "1+1"))
        assertNull(DuplicateCheck.find(existing, "1+1"))
        // 两侧都够长才算：已有题太短、新题够长 → 不命中
        assertNull(DuplicateCheck.find(listOf(q(1, "短题")), "这是一道足够长的题目"))
    }

    @Test
    fun `已删除的题不算重复`() {
        val existing = listOf(q(1, "求下列定积分的值：∫₀¹ x² dx", deleted = true))
        assertNull(DuplicateCheck.find(existing, "求下列定积分的值：∫₀¹ x² dx"))
    }

    @Test
    fun `可以排除某条题自身（重新保存同一条时不提示自己）`() {
        val existing = listOf(q(7, "求下列定积分的值：∫₀¹ x² dx"))
        assertNotNull(DuplicateCheck.find(existing, "求下列定积分的值：∫₀¹ x² dx"))
        assertNull(DuplicateCheck.find(existing, "求下列定积分的值：∫₀¹ x² dx", excludeId = 7L))
    }

    @Test
    fun `空题干不报重复`() {
        val existing = listOf(q(1, "求下列定积分的值"))
        assertNull(DuplicateCheck.find(existing, ""))
        assertNull(DuplicateCheck.find(existing, "   "))
    }

    // ── B6 批改结论 → 掌握度档位 ─────────────────────────────────────────

    @Test
    fun `批改结论：不正确必须判成忘记 而不是正确`() {
        // 中文里"不正确"包含"正确" —— 判定顺序错了就会把答错当答对
        assertEquals(GradeVerdict.FORGOT, GradeVerdict.level("结论：不正确，第二步符号写反了。"))
        assertEquals(GradeVerdict.FORGOT, GradeVerdict.level("解答：答案错误。讲解：…"))
        assertEquals(GradeVerdict.FORGOT, GradeVerdict.level("结论：❌ 错在换元"))
    }

    @Test
    fun `批改结论：正确判成熟悉 判不出来退到模糊`() {
        assertEquals(GradeVerdict.FAMILIAR, GradeVerdict.level("结论：正确，思路完整。讲解：…"))
        assertEquals(GradeVerdict.FAMILIAR, GradeVerdict.level("结论：✅ 答对"))
        assertEquals(GradeVerdict.VAGUE, GradeVerdict.level("讲解：这道题可以这样想……"))
    }

    @Test
    fun `只看开头一段 —— 正文里冒出的正确 错误 不会翻转结论`() {
        val long = "结论：正确。讲解：" + "这是一段很长的讲解。".repeat(100) + "顺便说一句，这种写法是错误的。"
        assertEquals(GradeVerdict.FAMILIAR, GradeVerdict.level(long, headLen = 60))
    }
}
