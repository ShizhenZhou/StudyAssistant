package com.zsz.studyassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A5 错题本排序的回归测试。
 *
 * 为什么测这里：排序错了用户一眼能看出来，但"没有复习记录的题排哪"这种边界最容易写错
 * （Long.MAX_VALUE / -1 档的兜底），而且改一次就要装到手机上看一次。
 */
class NotebookSortingTest {

    private fun q(id: Long, createdAt: Long, categoryId: Long? = null) =
        Question(id = id, text = "题$id", answer = "答$id", createdAt = createdAt, categoryId = categoryId)

    private fun r(questionId: Long, step: Int = 0, next: Long = 0L, count: Int = 0) =
        Review(questionId = questionId, intervalStep = step, nextReviewAt = next, lastReviewedAt = 0L, reviewCount = count)

    private val questions = listOf(
        q(1, 100), q(2, 300), q(3, 200)
    )

    @Test
    fun `最近添加 与 最早添加`() {
        assertEquals(listOf(2L, 3L, 1L), NotebookSorting.sort(questions, emptyMap(), NotebookSortMode.NEWEST).map { it.id })
        assertEquals(listOf(1L, 3L, 2L), NotebookSorting.sort(questions, emptyMap(), NotebookSortMode.OLDEST).map { it.id })
    }

    @Test
    fun `下次复习时间 —— 近的在前 没有复习记录的排最后`() {
        val reviews = mapOf(
            1L to r(1, next = 5_000),
            3L to r(3, next = 1_000)
            // 2 没有复习记录
        )
        val ids = NotebookSorting.sort(questions, reviews, NotebookSortMode.NEXT_REVIEW).map { it.id }
        assertEquals(listOf(3L, 1L, 2L), ids)
    }

    @Test
    fun `复习次数 —— 多的在前 没记录按 0 次算`() {
        val reviews = mapOf(
            1L to r(1, count = 2),
            3L to r(3, count = 7)
        )
        val ids = NotebookSorting.sort(questions, reviews, NotebookSortMode.MOST_REVIEWED).map { it.id }
        assertEquals(listOf(3L, 1L, 2L), ids)
    }

    @Test
    fun `最不熟优先 —— 没复习过的排最前 已掌握（高档位）在后`() {
        val reviews = mapOf(
            1L to r(1, step = 5, next = 9_000),   // 已掌握
            3L to r(3, step = 1, next = 2_000)    // 复习中
            // 2 从未复习 → 应该排最前
        )
        val ids = NotebookSorting.sort(questions, reviews, NotebookSortMode.WEAKEST).map { it.id }
        assertEquals(listOf(2L, 3L, 1L), ids)
    }

    @Test
    fun `排序不改变元素集合 也不修改入参列表`() {
        val before = questions.toList()
        for (mode in NotebookSortMode.menuOrder) {
            val out = NotebookSorting.sort(questions, emptyMap(), mode)
            assertEquals("$mode 丢了题", questions.size, out.size)
            assertEquals("$mode 改变了元素集合", questions.map { it.id }.toSet(), out.map { it.id }.toSet())
        }
        assertEquals("入参列表被改动了", before, questions)
    }

    @Test
    fun `空列表与单元素都安全`() {
        assertEquals(emptyList<Long>(), NotebookSorting.sort(emptyList(), emptyMap(), NotebookSortMode.NEWEST).map { it.id })
        assertEquals(listOf(1L), NotebookSorting.sort(listOf(q(1, 1)), emptyMap(), NotebookSortMode.WEAKEST).map { it.id })
    }

    @Test
    fun `排序方式的 id 与解析（持久化用）`() {
        assertEquals(NotebookSortMode.NEWEST, NotebookSortMode.DEFAULT)
        assertEquals(NotebookSortMode.WEAKEST, NotebookSortMode.fromId("weak"))
        assertEquals(NotebookSortMode.NEWEST, NotebookSortMode.fromId("不存在的值"))
        assertEquals(NotebookSortMode.NEWEST, NotebookSortMode.fromId(null))
        // 菜单顺序固定（界面下拉按它渲染）
        assertEquals(5, NotebookSortMode.menuOrder.size)
        assertEquals(NotebookSortMode.NEWEST, NotebookSortMode.menuOrder.first())
    }

    @Test
    fun `排序结果稳定 —— 同键元素按时间兜底 不会随机跳动`() {
        // 两道题复习次数相同（都无记录）→ 按 createdAt 倒序兜底
        val ids = NotebookSorting.sort(questions, emptyMap(), NotebookSortMode.MOST_REVIEWED).map { it.id }
        assertEquals(listOf(2L, 3L, 1L), ids)
        val ids2 = NotebookSorting.sort(questions, emptyMap(), NotebookSortMode.MOST_REVIEWED).map { it.id }
        assertEquals(ids, ids2)
        assertTrue(ids.isNotEmpty())
        assertNull(null as Question?)
    }
}
