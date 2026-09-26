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
        // 注意：normalize 里 ^ 这类"数学记号"**保留**（x^2 与 x2 不是一回事）；
        // 查重实际用的是 compareKey（再去掉 LaTeX 记号），见下面的用例
        assertEquals("求定积分x^2dx", DuplicateCheck.normalize("求定积分 \$x^2 dx\$"))
        assertEquals("abc", DuplicateCheck.normalize(" A B C "))
        assertEquals("求导", DuplicateCheck.normalize("求导。"))
        assertEquals("求导", DuplicateCheck.normalize("求导，"))
        assertEquals("求导", DuplicateCheck.normalize("求导（ ）"))
        // 全角/半角标点与大小写都要能对上
        assertEquals(DuplicateCheck.normalize("Apple, 苹果"), DuplicateCheck.normalize("apple 苹果"))
    }

    @Test
    fun `比较键 去掉 LaTeX 记号 —— x 的平方两种写法要能对上`() {
        assertEquals("求定积分x2dx", DuplicateCheck.compareKey("求定积分 \$x^{2} dx\$"))
        assertEquals(DuplicateCheck.compareKey("x^{2}+y_{1}"), DuplicateCheck.compareKey("x^2 + y_1"))
        assertEquals("frac12", DuplicateCheck.compareKey("\\frac{1}{2}"))
    }

    // ── 同一道题两次转写的差异（真实 bug：只比"完全相同"永远查不出来）──────

    @Test
    fun `AI 转写略有差异（LaTeX 记号 与 少量字词）仍然命中`() {
        val existing = listOf(q(1, "求下列定积分的值：∫₀¹ \$x^{2}\\,dx\$，并说明所用的公式。"))
        // 第二次拍同一道题：记号写成 $x^2$、逗号变顿号、少一个"所"
        val hit = DuplicateCheck.find(existing, "求下列定积分的值 ∫₀¹ \$x^2 dx\$，并说明用的公式")
        assertNotNull("同一道题的两次转写没被判为重复", hit)
        assertEquals(1L, hit!!.id)
    }

    @Test
    fun `包含关系（第二次多写了一句说明）仍然命中`() {
        val existing = listOf(q(1, "求下列定积分的值：∫₀¹ x² dx"))
        val hit = DuplicateCheck.find(existing, "求下列定积分的值：∫₀¹ x² dx（请写出完整步骤）")
        assertNotNull(hit)
    }

    @Test
    fun `相似度函数本身：完全相同 1_0 短串不给相似度`() {
        assertEquals(1.0, DuplicateCheck.similarity("abc", "abc"), 0.0001)
        assertEquals(0.0, DuplicateCheck.similarity("", "abc"), 0.0001)
        // 短题干（< FUZZY_MIN_LEN）只走"完全相同"，不给模糊相似度 → 避免 x2/x3 误判
        assertEquals(0.0, DuplicateCheck.similarity("求定积分x2dx", "求定积分x3dx"), 0.0001)
    }

    @Test
    fun `同一题型但数值不同（短题干）不能误报`() {
        val existing = listOf(q(1, "求定积分 ∫₀¹ x² dx"))
        // 只差一个指数：短题干不做模糊判定 → 不提示（否则满屏误报）
        assertNull(DuplicateCheck.find(existing, "求定积分 ∫₀¹ x³ dx"))
    }

    @Test
    fun `同一个分类里的两道不同长题不会互相误报`() {
        val existing = listOf(q(1, "证明：若函数 f(x) 在闭区间 [a,b] 上连续，则它在该区间上一致连续。"))
        assertNull(DuplicateCheck.find(existing, "计算二重积分 ∬_D xy dxdy，其中 D 是由抛物线 y=x² 与直线 y=x 围成的区域。"))
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
