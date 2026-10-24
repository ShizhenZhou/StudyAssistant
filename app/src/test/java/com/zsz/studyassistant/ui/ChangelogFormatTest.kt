package com.zsz.studyassistant.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「更新内容」的**排版规则**（2026-10-20 用户明确要求，且已经踩过两次）：
 *
 * > 每个新版本的更新内容，与**上一个版本的更新记录**之间必须有一行空行。
 *
 * 为什么老出问题：新版本是**往 `CHANGELOG_*` 的 `"""` 后面 prepend** 的，
 * 新块的最后一行正好顶着上一条 `v0.x.y` 标题 → 界面上两个版本粘在一起。
 * 所以这里把规则钉成用例：**任何版本标题行，上面一行必须是空行**（第一个版本除外）。
 */
class ChangelogFormatTest {

    private val tables: List<Pair<String, String>> = listOf(
        "ZH" to CHANGELOG_ZH,
        "ZH_TW" to s2t(CHANGELOG_ZH),
        "EN" to CHANGELOG_EN,
        "JA" to CHANGELOG_JA,
        "KO" to CHANGELOG_KO
    )

    /** 版本标题行：`v0.6.6.1` / `v0.6.6` / `v0.4.7.5（临时版本）` … */
    private val heading = Regex("^v\\d")

    private fun lines(text: String): List<String> =
        text.trim('\n').split('\n').map { it.trimEnd('\r', ' ') }

    @Test
    fun `每个版本标题上面都必须有一行空行`() {
        val bad = mutableListOf<String>()
        for ((name, text) in tables) {
            val ls = lines(text)
            assertTrue("$name 的更新内容为空", ls.isNotEmpty())
            for (i in ls.indices) {
                if (!heading.containsMatchIn(ls[i])) continue
                if (i == 0) continue                       // 第一个版本块上面是字符串开头，不需要空行
                if (ls[i - 1].isNotEmpty()) {
                    bad += "$name 第 ${i + 1} 行「${ls[i]}」上面没有空行（上一行：${ls[i - 1].take(24)}…）"
                }
            }
        }
        assertTrue("版本之间缺空行：\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `第一个版本块就是最新版本且没有多余空行`() {
        for ((name, text) in tables) {
            val ls = lines(text)
            assertTrue("$name 第一行不是版本标题：${ls.first()}", heading.containsMatchIn(ls.first()))
            assertTrue("$name 结尾有多余空行", ls.last().isNotEmpty())
            // 不允许连续两行空行（界面上会变成一大段空白）
            val doubled = ls.indices.filter { i -> i > 0 && ls[i].isEmpty() && ls[i - 1].isEmpty() }
            assertTrue("$name 有连续空行（行号 $doubled）", doubled.isEmpty())
        }
    }

    @Test
    fun `版本标题下面至少有一条内容行`() {
        val bad = mutableListOf<String>()
        for ((name, text) in tables) {
            val ls = lines(text)
            for (i in ls.indices) {
                if (!heading.containsMatchIn(ls[i])) continue
                val next = ls.getOrNull(i + 1)
                if (next == null || next.isEmpty() || heading.containsMatchIn(next)) {
                    bad += "$name「${ls[i]}」下面没有内容行"
                }
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `四语言都能取到更新内容`() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val version = Regex("val appVersionName = \"([^\"]+)\"")
            .find(File(root, "app/build.gradle.kts").readText())!!.groupValues[1]
        for (lang in listOf(UiLang.ZH_CN, UiLang.ZH_TW, UiLang.EN, UiLang.JA, UiLang.KO)) {
            val text = changelogText(lang)
            assertTrue("$lang 的更新内容为空", text.trim().isNotEmpty())
            assertTrue("$lang 少了当前版本 v$version", text.lineSequence().first { it.isNotBlank() } == "v$version")
        }
    }
}
