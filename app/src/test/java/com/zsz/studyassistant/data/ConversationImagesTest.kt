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

    // ── mergeImageKeys：修「两张图仍会丢」的关键函数（有主图时也必须带上会话里的其余图） ──

    @Test
    fun `mergeImageKeys 主图在前、会话图去重后追加`() {
        assertEquals(listOf("MAIN", "B", "C"), mergeImageKeys("MAIN", listOf("B", "MAIN", "C")))
    }

    @Test
    fun `mergeImageKeys 主图为空时只用会话图`() {
        assertEquals(listOf("A", "B"), mergeImageKeys(null, listOf("A", "B")))
        assertEquals(listOf("A"), mergeImageKeys("", listOf("A")))
    }

    @Test
    fun `mergeImageKeys 最多 3 张（拍题上限）`() {
        assertEquals(listOf("M", "1", "2"), mergeImageKeys("M", listOf("1", "2", "3")))
    }

    @Test
    fun `mergeImageKeys 空输入返回空表`() {
        assertTrue(mergeImageKeys(null, emptyList()).isEmpty())
        assertTrue(mergeImageKeys("   ", listOf("", " ")).isEmpty())
    }


    @Test
    fun `保留已有主图并按内容去重追加（不同数组同内容）`() {
        val main = byteArrayOf(1, 2)
        val next = byteArrayOf(3, 4)
        val result = additionalQuestionImages(listOf(main), listOf(main.copyOf(), next, next.copyOf()))
        assertEquals(1, result.size)
        assertTrue(next.contentEquals(result.single()))
        assertTrue(main.contentEquals(byteArrayOf(1, 2)))
    }

    @Test
    fun `已有两张时即使候选也两张仍能补入不同的第三张`() {
        val a = byteArrayOf(1)
        val b = byteArrayOf(2)
        val c = byteArrayOf(3)
        val result = additionalQuestionImages(listOf(a, b), listOf(b.copyOf(), c))
        assertEquals(1, result.size)
        assertTrue(c.contentEquals(result.single()))
    }

    @Test
    fun `保留三张上限且已满时不覆盖任何已有图`() {
        assertTrue(additionalQuestionImages(listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)),
            listOf(byteArrayOf(4))).isEmpty())
        assertEquals(3, additionalQuestionImages(emptyList(), (1..5).map { byteArrayOf(it.toByte()) }).size)
    }

    @Test
    fun `重复修复不再追加且空字节不占图片名额`() {
        val candidates = listOf(byteArrayOf(), byteArrayOf(1), byteArrayOf(2))
        val first = additionalQuestionImages(emptyList(), candidates)
        assertEquals(2, first.size)
        assertTrue(additionalQuestionImages(first, candidates).isEmpty())
    }

    @Test
    fun `同 DB 小版本更新不再提示，DB 升版仍提示`() {
        assertTrue(!needsDataUpgradePrompt(9, 9, 9, false))
        assertTrue(needsDataUpgradePrompt(10, 9, 9, false))
    }

    @Test
    fun `新安装完整数据不提示，首次发现旧格式提示`() {
        assertTrue(!needsDataUpgradePrompt(9, 0, 0, false))
        assertTrue(needsDataUpgradePrompt(9, 9, 0, true))
        assertTrue(!needsDataUpgradePrompt(9, 9, 9, true))
    }

}
