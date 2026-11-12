package com.zsz.studyassistant.data

/** 统计快照（全部由现有表计算，不改数据库结构） */
data class StatsSnapshot(
    val totalQuestions: Int = 0,
    val todayReviewed: Int = 0,
    val weekReviewed: Int = 0,
    val streakDays: Int = 0,
    val dueNow: Int = 0,
    val mastery: List<Pair<String, Int>> = emptyList(),   // 未开始 / 复习中 / 已掌握
    val subjects: List<Pair<String, Int>> = emptyList(),  // 科目（分类）→ 题数
    val last7Days: List<Int> = emptyList(),               // 近 7 天新增（最早 → 今天）
    val last30Days: List<Int> = emptyList()               // 近 30 天新增
)

/** Pure aggregation over lightweight rows; never reads text, images or database state. */
object StudyStatistics {
    fun calculate(questions: List<QuestionStatsRow>, reviews: List<Review>, categories: List<Category>,
        day: CalendarDay, excluded: Set<QuestionId> = emptySet()): StatsSnapshot {
        val alive = questions.filterNot { it.id in excluded }
        val ids = alive.mapTo(HashSet()) { it.id }
        val byId = HashMap<QuestionId, Review>()
        val reviewedAt = ArrayList<Long>()
        val today = day.start; val end = day.end; val week = day.after(-6)
        var todayReviewed = 0; var weekReviewed = 0; var due = 0
        for (review in reviews) {
            if (review.questionId !in ids) continue
            byId[review.questionId] = review
            reviewedAt.add(review.lastReviewedAt)
            if (review.lastReviewedAt in today..end) todayReviewed++
            if (review.lastReviewedAt in week..end) weekReviewed++
            if (review.nextReviewAt <= end) due++
        }
        val names = categories.associate { it.id to it.name }
        val subjects = linkedMapOf<String, Int>()
        // Calendar boundaries preserve 23/25-hour DST days; no fixed 24-hour arithmetic.
        val boundaries = LongArray(31) { day.after(it - 29) }
        val counts = IntArray(30)
        var notStarted = 0; var learning = 0; var mastered = 0
        for (question in alive) {
            when (val step = byId[question.id]?.intervalStep) {
                null -> notStarted++
                else -> if (step >= 3) mastered++ else learning++
            }
            val name = names[question.categoryId] ?: "未分类"
            subjects[name] = (subjects[name] ?: 0) + 1
            if (question.createdAt >= boundaries.first() && question.createdAt < boundaries.last()) {
                val found = boundaries.binarySearch(question.createdAt)
                val bucket = if (found >= 0) found else -found - 2
                counts[bucket]++
            }
        }
        val last30 = counts.toList()
        return StatsSnapshot(alive.size, todayReviewed, weekReviewed,
            ReviewPlanning.recordedStreak(reviewedAt, day), due,
            listOf("stats.mastery.notStarted" to notStarted, "stats.mastery.learning" to learning, "stats.mastery.mastered" to mastered),
            subjects.entries.sortedByDescending { it.value }.map { it.key to it.value },
            last30.takeLast(7), last30)
    }
}
