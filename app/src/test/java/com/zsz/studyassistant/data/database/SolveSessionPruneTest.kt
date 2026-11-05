package com.zsz.studyassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 搜题记录**裁剪策略**的回归测试（纯 JVM，不碰 Room / Android）。
 *
 * 为什么测这里：这条纯函数决定**用户的搜题记录会不会被误删**（保留 200 条 / 30 天），
 * 也决定**记录会不会无限膨胀**（BLOB 体积 → 列表查询撑爆 CursorWindow）。
 * DAO 与迁移依赖真机，所以把判定逻辑单独抽成了纯函数（见 `Question.kt` 的 `solveSessionsToDelete`）。
 *
 * ⚠️ 只依赖基本类型，不引用任何 Compose / Android 类 —— 否则会像 `CropScreenKt` 那样
 *    因 `Color` 拉起 `ui-graphics`，本机的手拼 classpath 跑不起来。
 */
class SolveSessionPruneTest {
    // Numeric fixture labels keep the original expectations readable; production IDs are opaque strings.
    private fun solveSessionsToDelete(sessions: List<SolveSession>, cutoffTs: Long, keep: Int = SOLVE_SESSION_KEEP): List<Long> =
        com.zsz.studyassistant.data.solveSessionsToDelete(sessions, cutoffTs, keep).map { it.toLong() }


    private fun at(y: Int, mo: Int, d: Int, h: Int = 12): Long =
        Calendar.getInstance().apply {
            set(y, mo - 1, d, h, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** 一条轻查询能取到的记录（只带 id/createdAt，够裁剪用） */
    private fun s(id: Long, createdAt: Long) = SolveSession(id = id.toString(), createdAt = createdAt, updatedAt = createdAt)

    /**
     * ★ 2026-10-13 回归：`createdAt == 0` 的坏记录**不能被裁剪误删**。
     *
     * 用户实测事故链：老版本的 UPDATE 把 `createdAt` 写成 0 → 列表里那条掉进「更早」→
     * **下一次启动就被裁剪当成"过期 30 天"删掉了**（记录凭空消失）。
     * 现在 `createdAt <= 0` 一律改用 `updatedAt` 参与判定。
     */
    @Test
    fun `createdAt 为 0 的坏记录用 updatedAt 判定，不能被误删`() {
        val now = System.currentTimeMillis()
        val broken = SolveSession(id = "7", createdAt = 0L, updatedAt = now)
        val cutoff = now - TimeUnit.DAYS.toMillis(30)
        assertTrue(
            "createdAt=0 的记录被误判为过期：${solveSessionsToDelete(listOf(broken), cutoff)}",
            solveSessionsToDelete(listOf(broken), cutoff).isEmpty()
        )
    }

    /** 同样地，条数上限排序也要用 updatedAt 兜底，不能把坏记录排到最旧而被挤掉 */
    @Test
    fun `createdAt 为 0 的坏记录在条数裁剪里按 updatedAt 排序（不会被挤掉）`() {
        val now = System.currentTimeMillis()
        val broken = SolveSession(id = "8", createdAt = 0L, updatedAt = now)          // 最新的一条
        val old = s(9, now - TimeUnit.DAYS.toMillis(1))                             // 更早
        val del = solveSessionsToDelete(listOf(broken, old), cutoffTs = 0L, keep = 1)
        assertEquals("应删更早的那条，而不是坏记录", listOf(9L), del)
    }

    @Test
    fun `空表什么也不删`() {
        assertEquals(emptyList<Long>(), solveSessionsToDelete(emptyList(), at(2026, 10, 1)))
    }

    @Test
    fun `超过 200 条时删掉最旧的`() {
        // 201 条，id = 1..201 且 id 越大越新 → 最旧的 id=1 该删
        val list = (1..201).map { s(it.toLong(), at(2026, 9, 1) + it * 60_000L) }
        val del = solveSessionsToDelete(list, cutoffTs = 0, keep = 200)
        assertEquals(listOf(1L), del)
        // 恰好 200 条：一条都不删
        assertEquals(emptyList<Long>(), solveSessionsToDelete(list.drop(1), cutoffTs = 0, keep = 200))
    }

    @Test
    fun `超过 30 天的记录被删`() {
        val cutoff = at(2026, 10, 1)
        val list = listOf(
            s(1, at(2026, 8, 30)),                  // 太旧 → 删
            s(2, cutoff - 1),                       // 卡在边界外 → 删
            s(3, cutoff),                           // 正好等于截止点 → 保留
            s(4, at(2026, 10, 2))                   // 新 → 保留
        )
        val del = solveSessionsToDelete(list, cutoff, keep = 200).sorted()
        assertEquals(listOf(1L, 2L), del)
    }

    @Test
    fun `条数与时间两条规则先到先删 —— 结果按 id 去重`() {
        val cutoff = at(2026, 10, 6)      // 9/1、9/2 由"条数"删；9/3 同时命中两条规则
        val list = listOf(
            s(1, at(2026, 9, 1)),
            s(2, at(2026, 9, 2)),
            s(3, at(2026, 9, 3)),
            s(4, at(2026, 10, 7)),
            s(5, at(2026, 10, 8))
        )
        val del = solveSessionsToDelete(list, cutoff, keep = 2)
        assertEquals(listOf(1L, 2L, 3L), del.sorted())
        assertEquals("同一条被两条规则同时命中时不能重复出现", del.size, del.distinct().size)
    }

    @Test
    fun `keep 参数可调 且默认值与设计文档一致`() {
        val list = (1..5).map { s(it.toLong(), at(2026, 9, 1) + it * 60_000L) }
        assertEquals(3, solveSessionsToDelete(list, cutoffTs = 0, keep = 2).size)
        assertEquals(0, solveSessionsToDelete(list, cutoffTs = 0, keep = 5).size)
        // 走默认 keep（200）：只有 5 条 → 一条都不该删（默认值不能是"随手就删"的小数字）
        assertEquals(0, solveSessionsToDelete(list, cutoffTs = 0).size)
        assertEquals(200, SOLVE_SESSION_KEEP)
        assertEquals(30, SOLVE_SESSION_KEEP_DAYS)
    }

    @Test
    fun `keep 或截止时间无效时 —— 宁可不删，绝不清空用户记录`() {
        val list = (1..5).map { s(it.toLong(), at(2026, 9, 1) + it * 60_000L) }
        assertEquals(emptyList<Long>(), solveSessionsToDelete(list, cutoffTs = 0, keep = 0))
        assertEquals(emptyList<Long>(), solveSessionsToDelete(list, cutoffTs = 0, keep = -1))
        // cutoffTs = 0（调用方没给截止时间）→ 不能因为"都早于 0"就把全部删光
        assertEquals(emptyList<Long>(), solveSessionsToDelete(list, cutoffTs = 0, keep = 100))
        // 少于 keep 条且都在 30 天内 → 一条不删
        assertEquals(emptyList<Long>(), solveSessionsToDelete(list, cutoffTs = at(2026, 9, 1), keep = 100))
    }

    @Test
    fun `不依赖传入顺序 —— 内部按 createdAt 倒序取最新的 keep 条`() {
        val cutoff = at(2026, 9, 1)
        // 故意乱序传入：最新的 2 条是 id=301、id=30
        val list = listOf(
            s(30, at(2026, 9, 30)),
            s(1, at(2026, 9, 1)),
            s(301, at(2026, 10, 1)),
            s(2, at(2026, 9, 2))
        )
        val del = solveSessionsToDelete(list, cutoff, keep = 2).sorted()
        assertEquals(listOf(1L, 2L), del)
        assertTrue("最新的两条必须留下", 30L !in del && 301L !in del)
    }
}
