package com.zsz.studyassistant.data

import kotlin.random.Random

/** Old numeric keys are used only while constructing the v9 -> v10 mapping. */
data class LegacyQuestionTime(val id: Long, val createdAt: Long)

fun inferQuestionTimes(
    rows: List<LegacyQuestionTime>, upgradeTime: Long, random: Random = Random.Default
): Map<Long, Long> {
    val sorted = rows.sortedBy { it.id }
    require(sorted.map { it.id }.distinct().size == sorted.size)
    val known = sorted.indices.filter { sorted[it].createdAt > 0 }
    if (known.isEmpty()) return sorted.associate { it.id to upgradeTime.coerceAtLeast(1) }
    val result = sorted.filter { it.createdAt > 0 }.associate { it.id to it.createdAt }.toMutableMap()
    val first = known.first()
    for (i in 0 until first) {
        result[sorted[i].id] = (sorted[first].createdAt - (first - i) * 1000L).coerceAtLeast(1)
    }
    val last = known.last()
    for (i in last + 1 until sorted.size) {
        val delta = (i - last) * 1000L
        result[sorted[i].id] = sorted[last].createdAt.coerceAtMost(Long.MAX_VALUE - delta) + delta
    }
    for ((left, right) in known.zipWithNext()) {
        val low = minOf(sorted[left].createdAt, sorted[right].createdAt)
        val high = maxOf(sorted[left].createdAt, sorted[right].createdAt)
        val times = List(right - left - 1) {
            if (high - low > 1) random.nextLong(low + 1, high) else low
        }.sorted()
        times.forEachIndexed { index, time -> result[sorted[left + 1 + index].id] = time }
    }
    return result
}

/** The same SQL plan is executed by Room and by the isolated SQLite migration test. */
object IdentityMigrationPlan {
    fun statements(
        questions: List<LegacyQuestionTime>, orphanIds: List<Long>,
        legacySessions: List<LegacyQuestionTime>, upgradeTime: Long
    ): List<String> = buildList {
        val times = inferQuestionTimes(questions, upgradeTime)
        add("CREATE TEMP TABLE identity_map_v10 (oldId INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE, createdAt INTEGER NOT NULL)")
        for (row in questions + orphanIds.distinct().filter { it !in times }.map { LegacyQuestionTime(it, upgradeTime) }) {
            val time = times[row.id] ?: upgradeTime
            add("INSERT INTO identity_map_v10 VALUES (${row.id}, '${QuestionIdentity.create(time)}', $time)")
        }
        add("CREATE TABLE questions_v10 (id TEXT NOT NULL PRIMARY KEY, text TEXT NOT NULL, answer TEXT NOT NULL, createdAt INTEGER NOT NULL, imageBytes BLOB, conversationJson TEXT, deleted INTEGER NOT NULL, categoryId INTEGER)")
        add("INSERT INTO questions_v10 SELECT m.id,q.text,q.answer,m.createdAt,q.imageBytes,q.conversationJson,q.deleted,q.categoryId FROM questions q JOIN identity_map_v10 m ON m.oldId=q.id")
        add("CREATE TABLE question_tags_v10 (questionId TEXT NOT NULL, tagId INTEGER NOT NULL, PRIMARY KEY(questionId,tagId))")
        add("INSERT INTO question_tags_v10 SELECT m.id,t.tagId FROM question_tags t JOIN identity_map_v10 m ON m.oldId=t.questionId")
        add("CREATE TABLE review_v10 (questionId TEXT NOT NULL PRIMARY KEY, intervalStep INTEGER NOT NULL, nextReviewAt INTEGER NOT NULL, lastReviewedAt INTEGER NOT NULL, reviewCount INTEGER NOT NULL)")
        add("INSERT INTO review_v10 SELECT m.id,r.intervalStep,r.nextReviewAt,r.lastReviewedAt,r.reviewCount FROM review r JOIN identity_map_v10 m ON m.oldId=r.questionId")
        add("CREATE TABLE question_images_v10 (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, questionId TEXT NOT NULL, position INTEGER NOT NULL, bytes BLOB NOT NULL, source TEXT, createdAt INTEGER NOT NULL)")
        add("INSERT INTO question_images_v10 SELECT i.id,m.id,i.position,i.bytes,i.source,i.createdAt FROM question_images i JOIN identity_map_v10 m ON m.oldId=i.questionId")
        add("CREATE TABLE solve_sessions_v10 (id TEXT NOT NULL PRIMARY KEY, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, source TEXT NOT NULL, text TEXT NOT NULL, answer TEXT NOT NULL, conversationJson TEXT, imageBytes BLOB, imagesJson TEXT, savedQuestionId TEXT)")
        // Compatibility of historical sessions is explicitly out of scope. Preserve unexpected
        // leftovers independently rather than deleting them or attaching them to a guessed problem.
        for (row in legacySessions) {
            val time = row.createdAt.takeIf { it > 0 } ?: upgradeTime
            val id = QuestionIdentity.create(time)
            add("INSERT INTO solve_sessions_v10 SELECT '$id',$time,updatedAt,source,text,answer,conversationJson,imageBytes,imagesJson,NULL FROM solve_sessions WHERE id=${row.id}")
        }
        // A count mismatch must abort BEFORE any old table is replaced.
        add("CREATE TEMP TABLE identity_count_check (ok INTEGER NOT NULL CHECK(ok=1))")
        for (table in listOf("questions", "question_tags", "review", "question_images", "solve_sessions")) {
            add("INSERT INTO identity_count_check SELECT CASE WHEN (SELECT COUNT(*) FROM $table)=(SELECT COUNT(*) FROM ${table}_v10) THEN 1 ELSE 0 END")
        }
        for (table in listOf("question_tags", "review", "question_images", "solve_sessions", "questions")) {
            add("DROP TABLE $table")
            add("ALTER TABLE ${table}_v10 RENAME TO $table")
        }
        add("CREATE INDEX index_question_images_questionId_position ON question_images(questionId,position)")
        add("DROP TABLE identity_count_check")
        add("DROP TABLE identity_map_v10")
    }
}
