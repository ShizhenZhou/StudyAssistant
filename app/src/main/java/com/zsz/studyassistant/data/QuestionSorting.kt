package com.zsz.studyassistant.data

import android.content.Context

/** 错题本排序方式（A5）；[id] 用于持久化，也是界面文案 key 的后缀（`notebook.sort.<id>`） */
enum class NotebookSortMode(val id: String) {
    /** 最近添加（默认，与历史行为一致） */
    NEWEST("newest"),
    /** 最早添加 */
    OLDEST("oldest"),
    /** 下次复习时间（近的在前；没有复习记录的排最后） */
    NEXT_REVIEW("next"),
    /** 复习次数（多的在前） */
    MOST_REVIEWED("most"),
    /** 最不熟（掌握档位低的在前；"未开始"排最前） */
    WEAKEST("weak");

    companion object {
        val DEFAULT = NEWEST
        fun fromId(id: String?): NotebookSortMode = entries.firstOrNull { it.id == id } ?: DEFAULT
        /** 界面下拉里的顺序 */
        val menuOrder: List<NotebookSortMode> = listOf(NEWEST, OLDEST, NEXT_REVIEW, MOST_REVIEWED, WEAKEST)
    }
}

/**
 * 错题本排序（纯函数，便于单元测试）。
 *
 * 设计取舍：排序放在**内存里做**（数据量是自用级别），不改 DAO 的 SQL、不动数据库结构，
 * 也不需要新建索引；排序方式变化时由 StateFlow 重新发射一次列表。
 * 没有复习记录的题在各模式下都有明确位置（不是"随机飘"）：
 *   · 下次复习：排最后（还没排进复习计划）
 *   · 复习次数：按 0 次算
 *   · 最不熟：按 -1 档算 → **排最前**（没复习过的最该先看）
 */
object NotebookSorting {

    fun sort(
        questions: List<Question>,
        reviews: Map<Long, Review>,
        mode: NotebookSortMode
    ): List<Question> = when (mode) {
        NotebookSortMode.NEWEST -> questions.sortedByDescending { it.createdAt }
        NotebookSortMode.OLDEST -> questions.sortedBy { it.createdAt }
        NotebookSortMode.NEXT_REVIEW -> questions.sortedWith(
            compareBy({ reviews[it.id]?.nextReviewAt ?: Long.MAX_VALUE }, { -it.createdAt })
        )
        NotebookSortMode.MOST_REVIEWED -> questions.sortedWith(
            compareByDescending<Question> { reviews[it.id]?.reviewCount ?: 0 }.thenByDescending { it.createdAt }
        )
        NotebookSortMode.WEAKEST -> questions.sortedWith(
            compareBy(
                { reviews[it.id]?.intervalStep ?: -1 },
                { reviews[it.id]?.nextReviewAt ?: Long.MAX_VALUE },
                { -it.createdAt }
            )
        )
    }
}

/** 排序方式的持久化（与主题/语言共用 `settings`） */
object NotebookSortStore {
    private const val PREFS = "settings"
    private const val KEY = "notebook_sort"

    fun load(context: Context): NotebookSortMode =
        NotebookSortMode.fromId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(context: Context, mode: NotebookSortMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.id).apply()
    }
}
