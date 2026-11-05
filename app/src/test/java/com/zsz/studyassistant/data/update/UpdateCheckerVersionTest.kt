package com.zsz.studyassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新判定的回归测试（A2）。
 *
 * 为什么测这里：版本比较一旦写错，用户**收不到更新**（或反复被提示），而且肉眼很难发现 ——
 * 只能靠装机试。这里把 `isNewer` 的分段数值语义、以及 Release 说明的清洗固定下来。
 */
class UpdateCheckerVersionTest {
    @Test fun numberedBetasKeepIndependentUpgradeAndSkipIdentities() {
        val base = "0.6.9.9"
        fun release(suffix: String, stamp: String) = UpdateChecker.ReleaseInfo(
            "v$base$suffix", base, "", "StudyAssistant-$base$suffix-$stamp.apk", "", null, 1,
            channel = if (suffix.isEmpty()) UpdateChecker.Channel.STABLE else UpdateChecker.Channel.BETA)
        val early = "20261101000000"
        val late = "20261102120000"
        val legacy = release("_beta", late)
        val first = release("_beta1", late)
        val second = release("_beta2", late)
        val tenth = release("_beta10", early)
        val stable = release("", early)
        for ((old, newer) in listOf(legacy, first, second, tenth, stable).zipWithNext()) {
            assertTrue(UpdateChecker.compareReleases(newer, old) > 0)
            assertFalse(UpdateChecker.isNewer(old.displayVersion, newer.displayVersion))
        }
        val candidates = listOf(second, stable, tenth, first).map { it to emptyList<kotlinx.serialization.json.JsonObject>() }
        assertEquals(UpdateChecker.AvailableReleases(stable, tenth), UpdateChecker.resolveAvailable(candidates) { it.first })
        assertEquals(base, UpdateChecker.skipIdentity(legacy.displayVersion))
        assertEquals("${base}_beta1", UpdateChecker.skipIdentity(first.displayVersion))
        assertEquals("${base}_beta2", UpdateChecker.skipIdentity(second.displayVersion))
    }

    @Test fun betaBuildOrderingKeepsPublishedSuffix() {
        fun release(stamp: String) = UpdateChecker.ReleaseInfo("v0.6.9_beta", "0.6.9", "",
            "StudyAssistant-0.6.9_beta-$stamp.apk", "", null, 1, channel=UpdateChecker.Channel.BETA)
        val old = release("20261031051459")
        val newer = release("20261031053820")
        assertTrue(UpdateChecker.compareReleases(newer, old) > 0)
        assertFalse(BuildIdentity.isReplacement(BuildIdentity.assetTime(newer.assetName, newer.displayVersion),
            BuildIdentity.assetTime(old.assetName, old.displayVersion), "new", "old"))
    }

    @Test
    fun `normalizeVersion 去掉 v 前缀与 _beta 这类后缀`() {
        assertEquals("0.6.1.2", UpdateChecker.normalizeVersion("v0.6.1.2"))
        assertEquals("0.6.1.2", UpdateChecker.normalizeVersion("  V0.6.1.2  "))
        assertEquals("0.5.8", UpdateChecker.normalizeVersion("0.5.8_beta"))
    }

    @Test
    fun `isNewer 是分段数值比较 —— 不是字典序`() {
        assertTrue("1.10 必须大于 1.9（字典序会判错）", UpdateChecker.isNewer("1.10", "1.9"))
        assertTrue(UpdateChecker.isNewer("0.6.1", "0.6.0"))
        assertTrue(UpdateChecker.isNewer("0.6.1.1", "0.6.1"))
        assertTrue(UpdateChecker.isNewer("0.6.1.2", "0.6.1.1"))
        assertTrue(UpdateChecker.isNewer("v0.6.1.3", "0.6.1.2"))
        assertTrue(UpdateChecker.isNewer("0.6.2", "0.6.1.9"))
    }

    @Test
    fun `同版本或更低版本都算没有更新`() {
        assertFalse(UpdateChecker.isNewer("0.6.1.2", "0.6.1.2"))
        assertFalse(UpdateChecker.isNewer("0.6.1", "0.6.1.2"))
        assertFalse(UpdateChecker.isNewer("0.6.0", "0.6.1"))
        assertFalse(UpdateChecker.isNewer("1.0", "1"))
    }

    @Test
    fun `段数不同时 缺失段按 0 处理`() {
        assertTrue(UpdateChecker.isNewer("1.0.1", "1"))
        assertFalse(UpdateChecker.isNewer("1", "1.0.0"))
        assertTrue(UpdateChecker.isNewer("0.6.1.0.1", "0.6.1"))
    }

    @Test
    fun `Release 说明的 Markdown 会清洗成能直接显示的纯文本`() {
        val md = """
            ### 🌙 深色主题
            - **修复**：深色下气泡仍是浅灰
            正文里的 `代码` 与 **加粗**
        """.trimIndent()

        val t = UpdateChecker.prettyReleaseNotes(md)

        assertFalse("还留着 # 标记：$t", t.contains("#"))
        assertFalse("还留着 ** 标记：$t", t.contains("**"))
        assertFalse("还留着反引号：$t", t.contains("`"))
        assertTrue(t.startsWith("🌙 深色主题"))
        assertTrue(t.contains("· 修复：深色下气泡仍是浅灰"))
        assertTrue(t.contains("正文里的 代码 与 加粗"))
    }

    @Test
    fun `空说明清洗后仍然是空 —— 界面上不该出现空标题`() {
        assertEquals("", UpdateChecker.prettyReleaseNotes(""))
        assertEquals("", UpdateChecker.prettyReleaseNotes("   \n\n  \n"))
    }
    @Test
    fun stableNotificationDeduplicatesBuildsAndHonorsEligibility() {
        val stable = UpdateChecker.ReleaseInfo("v0.6.9.4", "0.6.9.4", "", "build1.apk",
            "https://github.com/ShizhenZhou/StudyAssistant/releases/download/v0.6.9.4/build1.apk", "a".repeat(64), 100)
        val key = UpdateNotifications.candidateKey(stable, eligible = true, lastNotified = null)
        assertTrue(key != null)
        assertEquals(null, UpdateNotifications.candidateKey(stable, true, key))
        assertEquals(null, UpdateNotifications.candidateKey(stable, false, null)) // skipped, installed or incompatible
        assertEquals(null, UpdateNotifications.candidateKey(stable.copy(channel = UpdateChecker.Channel.BETA), true, null))
        assertTrue(UpdateNotifications.candidateKey(stable.copy(assetSha256 = "b".repeat(64)), true, key) != null)
        assertTrue(UpdateNotifications.candidateKey(stable.copy(assetUrl = stable.assetUrl.replace("build1", "build2")), true, key) != null)
    }
}
