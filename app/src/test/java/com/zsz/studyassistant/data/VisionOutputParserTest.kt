package com.zsz.studyassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 答案解析的回归测试（A2：关键逻辑补单元测试）。
 *
 * 为什么测这里：`parseVisionOutput` / `stripMetaLines` / `stripLeakedSectionMarks` 是历史上
 * **反复出错**的地方 ——「答案里又出现题目」「莫名其妙的④」「答案生成完就消失」三个真 bug 都出自这段，
 * 而以前只能装到手机上拍一道题看气泡才知道有没有改坏。现在改完跑 `gradlew test` 几秒出结果。
 */
class VisionOutputParserTest {

    @Test
    fun `标准四段输出 —— 题目 分类 知识点 解答 都能取到`() {
        val out = """
            ① 题目：求定积分 ∫₀¹ x² dx
            ② 分类：数学
            ③ 知识点：定积分、微积分基础、牛顿-莱布尼茨公式
            ④ 解答：第一步，求原函数。
            第二步，代入上下限得 1/3。
        """.trimIndent()

        val r = StudyAssistant.parseVisionOutput(out)

        assertEquals("求定积分 ∫₀¹ x² dx", r.question)
        assertEquals("数学", r.category)
        assertEquals(listOf("定积分", "微积分基础", "牛顿-莱布尼茨公式"), r.tags)
        assertTrue(r.answer.startsWith("第一步"))
        assertTrue(r.answer.contains("1/3"))
        // 元信息行绝不能混进答案（用户反馈过的"答案里又出现题目"）
        assertFalse(r.answer.contains("分类"))
        assertFalse(r.answer.contains("知识点"))
    }

    @Test
    fun `模型没写「解答：」时 —— 剔掉回显的元信息行 但正文一个字都不能丢`() {
        val out = """
            ① 题目：介绍一下高斯公式
            ② 分类：数学
            ③ 知识点：高斯定理
            这是正文第一段。
            这是正文第二段。
        """.trimIndent()

        val r = StudyAssistant.parseVisionOutput(out)

        assertEquals("这是正文第一段。\n这是正文第二段。", r.answer)
    }

    @Test
    fun `题干与解答写在同一行 —— 只去掉行首标签 保住正文`() {
        val out = "① 题目：介绍一下高斯公式：它是描述通量与散度关系的定理。"

        val r = StudyAssistant.parseVisionOutput(out)

        assertTrue("正文被整段删掉了：${r.answer}", r.answer.startsWith("介绍一下高斯公式"))
        assertTrue(r.answer.contains("通量"))
        assertFalse(r.answer.startsWith("题目"))
    }

    @Test
    fun `圈码节标签泄漏到每一行 —— 同一个圈码重复出现就整篇剔掉`() {
        val out = """
            ④ 解答：
            ④ 1. 条件：曲面封闭。
            ④ 2. 结论：通量为零。
        """.trimIndent()

        val r = StudyAssistant.parseVisionOutput(out)

        assertFalse("泄漏的④没被剔掉：${r.answer}", r.answer.contains("④"))
        assertTrue(r.answer.contains("1. 条件：曲面封闭。"))
        assertTrue(r.answer.contains("2. 结论：通量为零。"))
    }

    @Test
    fun `只出现一次的圈码是模型自己的分点 —— 不能误删`() {
        val out = """
            解答：
            ④ 这是模型自己写的一条分点。
        """.trimIndent()

        val r = StudyAssistant.parseVisionOutput(out)

        assertTrue("被误删了：${r.answer}", r.answer.contains("④ 这是模型自己写的一条分点。"))
    }

    @Test
    fun `正常答案里的冒号行（注意 结论 分析）不能被当成元信息删掉`() {
        val out = """
            解答：
            注意：这里要用换元法。
            结论：结果为 1。
        """.trimIndent()

        val r = StudyAssistant.parseVisionOutput(out)

        assertTrue(r.answer.contains("注意：这里要用换元法。"))
        assertTrue(r.answer.contains("结论：结果为 1。"))
    }

    @Test
    fun `带修饰语的元信息行（核心知识点）也要剔掉`() {
        val out = "解答：正文一段\n核心知识点：欧姆定律"

        val r = StudyAssistant.parseVisionOutput(out)

        assertTrue(r.answer.contains("正文一段"))
        assertFalse("带修饰语的元信息行没剔掉：${r.answer}", r.answer.contains("核心知识点"))
    }

    @Test
    fun `同义写法（科目、考点）也能识别`() {
        val out = """
            题目：滑块问题
            科目：物理
            考点：牛顿定律、动量守恒
            解答：见下
        """.trimIndent()

        val r = StudyAssistant.parseVisionOutput(out)

        assertEquals("物理", r.category)
        assertEquals(listOf("牛顿定律", "动量守恒"), r.tags)
    }

    @Test
    fun `标签最多 5 个 且逗号顿号都能拆`() {
        val r1 = StudyAssistant.parseVisionOutput("题目：T\n知识点：a、b、c、d、e、f、g\n解答：ok")
        assertEquals(5, r1.tags.size)

        val r2 = StudyAssistant.parseVisionOutput("题目：T\n知识点：a，b, c\n解答：ok")
        assertEquals(listOf("a", "b", "c"), r2.tags)
    }

    @Test
    fun `整段都被当成元信息剔掉时 —— 兜底回正文 绝不返回空气泡`() {
        val out = "① 题目：只有一个标题行"

        val r = StudyAssistant.parseVisionOutput(out)

        assertTrue("返回了空答案", r.answer.isNotBlank())
        assertEquals("只有一个标题行", r.answer)
    }

    @Test
    fun `混进正文的思考块要先剥掉 不能污染题目与解答`() {
        val out = """
            <思考>这里是想歪了的推理过程</思考>
            ① 题目：求 1+1
            ② 分类：数学
            ④ 解答：等于 2。
        """.trimIndent()

        val r = StudyAssistant.parseVisionOutput(out)

        assertEquals("求 1+1", r.question)
        assertFalse(r.answer.contains("想歪了"))
        assertTrue(r.answer.contains("等于 2。"))
    }
}
