package com.zsz.studyassistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * B7 导出与 C14 字号的纯逻辑测试（渲染本身依赖 WebView，只能在真机上看效果）。
 *
 * 为什么测这里：
 *  · 文件名会直接进系统"保存到文件"对话框 —— 题干里带 `/ \ : * ? " < >` 或超长会把保存搞失败；
 *  · PDF 分页算错会丢内容（最后一页被截掉）或做出空白页。
 */
class QuestionExporterTest {

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance().apply {
            set(y, mo - 1, d, h, mi, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    // ── 文件名（要能直接交给 SAF"保存到文件"）────────────────────────────

    @Test
    fun `文件名 带上时间戳与正确后缀`() {
        val t = at(2026, 9, 26, 23, 5)
        assertEquals("求导_20260926-2305.png", QuestionExporter.fileName(QuestionExporter.Format.PNG, "求导", t))
        assertEquals("求导_20260926-2305.pdf", QuestionExporter.fileName(QuestionExporter.Format.PDF, "求导", t))
    }

    @Test
    fun `文件名 去掉非法字符与空白 并截断过长题干`() {
        val name = QuestionExporter.fileName(
            QuestionExporter.Format.PNG,
            "求 a/b\\c:d*e?f\"g<h>i|j 的 值，还要再长一点超过十八个字",
            at(2026, 9, 26, 8, 30)
        )
        for (bad in listOf("/", "\\", ":", "*", "?", "\"", "<", ">", "|")) {
            assertFalse("文件名里还有非法字符 $bad：$name", name.contains(bad))
        }
        assertFalse("文件名里不应该有空格：$name", name.contains(" "))
        assertTrue(name.endsWith(".png"))
        // 题干部分最多 18 字 + "_20260926-0830.png"
        assertTrue("题干没截断：$name", name.length <= 18 + 1 + 13 + 4)
    }

    @Test
    fun `空题干也能给出可用文件名`() {
        val name = QuestionExporter.fileName(QuestionExporter.Format.PDF, "   ", at(2026, 9, 26, 8, 30))
        assertTrue(name.startsWith("StudyAssistant_"))
        assertTrue(name.endsWith(".pdf"))
    }

    // ── PDF 分页切片 ────────────────────────────────────────────────────

    @Test
    fun `分页 覆盖整张图 不重不漏`() {
        val slices = QuestionExporter.pageSlices(contentHeight = 2500, pageHeight = 1000)
        assertEquals(3, slices.size)
        assertEquals(0..999, slices[0])
        assertEquals(1000..1999, slices[1])
        assertEquals(2000..2499, slices[2])          // 最后一页只到内容底部（不留空白）
        // 相邻页首尾相接，没有缝隙也没有重叠
        slices.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        assertEquals(0, slices.first().first)
        assertEquals(2499, slices.last().last)
    }

    @Test
    fun `分页 刚好整除时不多出一页空白`() {
        val slices = QuestionExporter.pageSlices(2000, 1000)
        assertEquals(2, slices.size)
        assertEquals(1000..1999, slices[1])
    }

    @Test
    fun `分页 内容比一页还短时只有一页`() {
        val slices = QuestionExporter.pageSlices(300, 1000)
        assertEquals(1, slices.size)
        assertEquals(0..299, slices[0])
    }

    @Test
    fun `分页 参数非法时返回空表 而不是抛异常或死循环`() {
        assertEquals(emptyList<IntRange>(), QuestionExporter.pageSlices(0, 1000))
        assertEquals(emptyList<IntRange>(), QuestionExporter.pageSlices(-5, 1000))
        assertEquals(emptyList<IntRange>(), QuestionExporter.pageSlices(1000, 0))
    }

    // ── 导出格式 / 字号档位（持久化 id 与解析）──────────────────────────

    @Test
    fun `导出格式 的 id mimetype 与后缀`() {
        assertEquals(QuestionExporter.Format.PNG, QuestionExporter.Format.DEFAULT)
        assertEquals(QuestionExporter.Format.PDF, QuestionExporter.Format.fromId("pdf"))
        assertEquals(QuestionExporter.Format.PNG, QuestionExporter.Format.fromId("不认识"))
        assertEquals("image/png", QuestionExporter.Format.PNG.mime)
        assertEquals("application/pdf", QuestionExporter.Format.PDF.mime)
    }

    @Test
    fun `字号档位 的取值与解析`() {
        assertEquals(FontScale.NORMAL, FontScale.DEFAULT)
        assertEquals(1.0f, FontScale.NORMAL.factor, 0.0001f)
        assertTrue(FontScale.SMALL.factor < 1f)
        assertTrue(FontScale.LARGE.factor > 1f)
        assertTrue(FontScale.XLARGE.factor > FontScale.LARGE.factor)
        assertEquals(FontScale.XLARGE, FontScale.fromId("xlarge"))
        assertEquals(FontScale.NORMAL, FontScale.fromId(null))
        assertEquals(4, FontScale.menuOrder.size)
    }

    @Test
    fun `按钮字号 比 Material3 默认小 1 到 2 sp`() {
        // 用户要求：所有按钮字号缩小 1~2 个字号（labelLarge 默认 14sp）
        assertTrue("按钮字号没有变小", BTN_LABEL.value <= 12f)
        assertTrue(BTN_LABEL.value >= 11f)
        assertTrue(BTN_LABEL_SMALL.value < BTN_LABEL.value)
        assertTrue(BTN_LABEL_BIG.value <= 14f)
        // 内部页标题：比 titleLarge 默认 22sp 小 1sp
        assertEquals(21f, SUBPAGE_TITLE.value, 0.001f)
    }
}
