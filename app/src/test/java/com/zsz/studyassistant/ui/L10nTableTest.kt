package com.zsz.studyassistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文案表的回归测试（A2）。
 *
 * 为什么测这里：加新文案时最容易出三类低级错误，而且**只有切到那个语言/繁体才看得出来**：
 *   ① 忘了给某语言补 key → 界面上静默回落到中文（看起来很怪，但不报错）；
 *   ② 占位符写错（`{v}` 写成 `{V}`）→ `format()` 少替换，界面直接显示 `{V}`；
 *   ③ 新增文案出现繁体字表里没收录的简体字 → 繁體界面残留简体字（v0.6.0 就漏过一次）。
 */
class L10nTableTest {

    private val others = mapOf("EN" to EN, "JA" to JA, "KO" to KO)

    @Test
    fun `四种语言都覆盖了简体中文的全部键（漏翻会静默回落中文）`() {
        val missing = mutableListOf<String>()
        for ((name, table) in others) {
            for (k in ZH.keys) if (!table.containsKey(k)) missing += "$name:$k"
        }
        assertTrue("以下文案缺少翻译：\n" + missing.sorted().joinToString("\n"), missing.isEmpty())
    }

    @Test
    fun `没有空文案`() {
        for ((name, table) in others + mapOf("ZH" to ZH)) {
            val empty = table.filterValues { it.isBlank() }.keys.sorted()
            assertTrue("$name 里有空文案：$empty", empty.isEmpty())
        }
    }

    @Test
    fun `占位符在各语言里必须一致（否则 format 会少替换）`() {
        val ph = Regex("\\{[a-zA-Z]+\\}")
        fun placeholders(v: String) = ph.findAll(v).map { it.value }.sorted().toList()
        val bad = mutableListOf<String>()
        for (k in ZH.keys) {
            val base = placeholders(ZH.getValue(k))
            for ((name, table) in others) {
                val other = placeholders(table[k].orEmpty())
                if (base != other) bad += "$k: ZH$base vs $name$other"
            }
        }
        assertTrue("占位符不一致：\n" + bad.sorted().joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `繁体界面不能残留简体字（本版新增文案逐条固定）`() {
        val tw = stringsFor(UiLang.ZH_TW)

        // v0.6.1.2 崩溃日志文案
        assertEquals("🐞 崩潰日誌", tw["data.crash"])
        assertEquals("導出崩潰日誌", tw["data.crash.export"])

        // v0.6.1.3 更新页文案
        assertTrue(tw["update.skip"].contains("跳過"))
        assertTrue(tw["update.redownload"].contains("重新下載"))
        assertTrue(tw["update.notes"].contains("更新內容"))
        assertTrue(tw["update.unskip"].contains("跳過"))
    }

    @Test
    fun `新增的更新页文案四语言都在`() {
        val keys = listOf(
            "update.notes", "update.lastCheck", "update.neverChecked",
            "update.skip", "update.skipped", "update.unskip", "update.retry", "update.redownload"
        )
        for (k in keys) {
            for ((name, table) in others) {
                assertTrue("$name 缺少 $k", table.containsKey(k))
            }
            assertTrue("ZH 缺少 $k", ZH.containsKey(k))
        }
    }
}
