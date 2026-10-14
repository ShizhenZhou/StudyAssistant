package com.zsz.studyassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `questionImageBase64` 的回归测试（纯 JVM，不碰 Room / Android / kotlinx）。
 *
 * 对应缺陷（用户 2026-10-14 报）：**加错题本后原图有时会丢，多图搜题时更容易**。
 * 根因是 `imageBytes`（单图专用字段）对"多图拍题 / 图文提问"两条链路从不赋值，
 * 而错题本卡片缩略图与复习页自测原图只读 `imageBytes` → 看不到图；
 * 图片其实一直在 `conversationJson`（`ChatItem.images`）里，所以读取端用本函数兜底。
 */
class ConversationImagesTest {

    /** 形状与 kotlinx 序列化 `ChatItem` 一致（题目气泡永远第 0 条） */
    private val twoImages =
        """[{"id":0,"role":"question","content":"求电容","images":["AAAABBBB","CCCCDDDD"],"thinking":null},""" +
            """{"id":1,"role":"assistant","content":"解：","images":null,"thinking":"思路"}]"""

    @Test
    fun `取出题目气泡的附图（多图按顺序）`() {
        assertEquals(listOf("AAAABBBB", "CCCCDDDD"), questionImageBase64(twoImages))
    }

    @Test
    fun `题目没有图时返回空表（不误取后面答案里的图）`() {
        val json = """[{"id":0,"role":"question","content":"文字题","images":null,"thinking":null},""" +
            """{"id":1,"role":"assistant","content":"解：","images":["ZZZZ"],"thinking":null}]"""
        assertTrue(questionImageBase64(json).isEmpty())
    }

    @Test
    fun `空数组返回空表`() {
        val json = """[{"id":0,"role":"question","content":"q","images":[],"thinking":null}]"""
        assertTrue(questionImageBase64(json).isEmpty())
    }

    @Test
    fun `单图也取得到（与 imageBytes 口径无关）`() {
        val json = """[{"id":0,"role":"question","content":"q","images":["ONLYONE"],"thinking":null}]"""
        assertEquals(listOf("ONLYONE"), questionImageBase64(json))
    }

    @Test
    fun `空串、null 与垃圾 JSON 都安全返回空表`() {
        assertTrue(questionImageBase64(null).isEmpty())
        assertTrue(questionImageBase64("").isEmpty())
        assertTrue(questionImageBase64("not json at all").isEmpty())
        assertTrue(questionImageBase64("""[{"role":"assistant","content":"x"}]""").isEmpty())
    }

    @Test
    fun `图片后面的字段不会串进来（thinking 里有引号也安全）`() {
        val json = """[{"id":0,"role":"question","content":"q","images":["A1B2C3"],""" +
            """"thinking":"他说 \"answer\" 是这样"}]"""
        assertEquals(listOf("A1B2C3"), questionImageBase64(json))
    }

    @Test
    fun `content 里出现 role 字样也不影响（只看 JSON 结构里的键）`() {
        val json = """[{"id":0,"role":"question","content":"role=question 是什么意思","images":["K9"],"thinking":null}]"""
        assertEquals(listOf("K9"), questionImageBase64(json))
    }
}
