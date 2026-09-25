package com.zsz.studyassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 自动备份（B8）纯逻辑的回归测试。
 *
 * 为什么测这里：这几条决定了**用户的数据会不会被误删**（裁剪只该碰自己写的文件）、
 * 以及**备份会不会该跑的时候没跑 / 不该跑的时候天天跑**（"每天第一次打开应用"的判定）。
 * 文件读写本身依赖 SAF，无法在纯 JVM 里测，所以把判定逻辑单独抽成了纯函数。
 */
class AutoBackupTest {

    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0): Long =
        Calendar.getInstance().apply {
            set(y, mo - 1, d, h, mi, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `文件名带定宽时间戳 —— 字典序倒排就是时间倒排`() {
        val a = AutoBackup.backupFileName(at(2026, 9, 26, 9, 5))
        val b = AutoBackup.backupFileName(at(2026, 9, 26, 23, 59))
        val c = AutoBackup.backupFileName(at(2026, 10, 1, 0, 0))

        assertEquals("study-assistant-auto-20260926-0905.json", a)
        assertTrue(a < b)   // 同一天：09:05 早于 23:59
        assertTrue(b < c)   // 跨月仍然成立
        assertTrue(AutoBackup.backupFileName(at(2026, 9, 26)).startsWith(AutoBackup.PREFIX))
    }

    @Test
    fun `只保留最近 3 份 —— 更旧的列进待删清单`() {
        val names = (1..5).map { AutoBackup.backupFileName(at(2026, 9, it)) }
        val del = AutoBackup.pruneTargets(names).sorted()
        assertEquals(2, del.size)
        // 9/1 与 9/2 最旧 → 该删
        assertTrue(del[0].contains("20260901"))
        assertTrue(del[1].contains("20260902"))
    }

    @Test
    fun `不足 3 份时什么都不删`() {
        assertEquals(emptyList<String>(), AutoBackup.pruneTargets(emptyList()))
        assertEquals(emptyList<String>(), AutoBackup.pruneTargets(listOf(AutoBackup.backupFileName(at(2026, 9, 26)))))
        val three = (1..3).map { AutoBackup.backupFileName(at(2026, 9, it)) }
        assertEquals(emptyList<String>(), AutoBackup.pruneTargets(three))
    }

    @Test
    fun `绝不碰用户自己的文件 —— 只认自己的前缀`() {
        val mine = (1..4).map { AutoBackup.backupFileName(at(2026, 9, it)) }
        val others = listOf(
            "我的笔记.json",
            "study-assistant-backup.json",          // 手动导出的备份：名字不以 auto- 开头，不能删
            "study-assistant-auto-20260901.txt",     // 前缀对但后缀不对 → 不删
            "photo.jpg"
        )
        val del = AutoBackup.pruneTargets(mine + others)
        assertEquals(1, del.size)                    // 只有最旧的那份自动备份
        assertTrue(del[0].contains("20260901"))
        for (o in others) assertFalse("误删了用户文件：$o", o in del)
    }

    @Test
    fun `keep 参数可调（界面文案里的份数说明与之一致）`() {
        val names = (1..5).map { AutoBackup.backupFileName(at(2026, 9, it)) }
        assertEquals(3, AutoBackup.pruneTargets(names, keep = 2).size)
        assertEquals(0, AutoBackup.pruneTargets(names, keep = 5).size)
        assertEquals(3, AutoBackup.KEEP)   // 用户要求：只保存最近 3 份
    }

    @Test
    fun `每天第一次打开应用才跑 —— 同一天再打开不重复备份`() {
        val today = AutoBackup.dayKey(at(2026, 9, 26))
        val yesterday = AutoBackup.dayKey(at(2026, 9, 25))

        // 开着 + 已选文件夹 + 今天没备份过 → 跑
        assertTrue(AutoBackup.shouldRunToday(true, true, yesterday, today))
        assertTrue(AutoBackup.shouldRunToday(true, true, null, today))
        // 今天已经备份过 → 不再跑
        assertFalse(AutoBackup.shouldRunToday(true, true, today, today))
        // 开关关掉 / 没选文件夹 → 不跑
        assertFalse(AutoBackup.shouldRunToday(false, true, yesterday, today))
        assertFalse(AutoBackup.shouldRunToday(true, false, yesterday, today))
    }

    @Test
    fun `日期键是本地日期（跨零点就算新的一天）`() {
        assertEquals("2026-09-26", AutoBackup.dayKey(at(2026, 9, 26, 0, 0)))
        assertEquals("2026-09-26", AutoBackup.dayKey(at(2026, 9, 26, 23, 59)))
        assertEquals("2026-09-27", AutoBackup.dayKey(at(2026, 9, 27, 0, 1)))
        assertFalse(AutoBackup.dayKey(at(2026, 9, 26, 23, 59)) == AutoBackup.dayKey(at(2026, 9, 27, 0, 0)))
    }
}
