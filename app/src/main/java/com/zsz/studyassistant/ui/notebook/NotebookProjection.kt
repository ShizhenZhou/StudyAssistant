package com.zsz.studyassistant.ui

import androidx.compose.runtime.*
import com.zsz.studyassistant.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class NotebookProjection(
    val counts: Map<Long, Int> = emptyMap(),
    val tags: Map<QuestionId, List<Long>> = emptyMap(),
    val rows: List<QuestionPreview> = emptyList()
)

internal suspend fun projectNotebook(questions: List<QuestionPreview>, relations: List<QuestionTag>,
    uncategorized: Boolean, category: Long?, tags: Set<Long>, matched: Set<QuestionId>?): NotebookProjection {
    val counts = HashMap<Long, Int>()
    val grouped = HashMap<QuestionId, MutableList<Long>>()
    val context = currentCoroutineContext()
    for (relation in relations) {
        context.ensureActive()
        grouped.getOrPut(relation.questionId) { mutableListOf() }.add(relation.tagId)
    }
    val rows = questions.filter { q ->
        context.ensureActive()
        val key = q.categoryId ?: UNCATEGORIZED_ORDER_ID
        counts[key] = (counts[key] ?: 0) + 1
        (if (uncategorized) q.categoryId == null else category == null || q.categoryId == category) &&
            (tags.isEmpty() || grouped[q.id].orEmpty().any { it in tags }) &&
            (matched == null || q.id in matched)
    }
    return NotebookProjection(counts, grouped, rows)
}

@Composable
internal fun rememberNotebookProjection(questions: List<QuestionPreview>, relations: List<QuestionTag>,
    uncategorized: Boolean, category: Long?, tags: Set<Long>, matched: Set<QuestionId>?): NotebookProjection {
    // Identity guards prevent actions using results from the preceding filter while the new one computes.
    val token = remember(questions, relations, uncategorized, category, tags, matched) { Any() }
    val result by produceState<Pair<Any, NotebookProjection>?>(null, token) {
        value = token to withContext(Dispatchers.Default) {
            projectNotebook(questions, relations, uncategorized, category, tags, matched)
        }
    }
    return result?.let { if (it.first === token) it.second else it.second.copy(rows = emptyList()) } ?: NotebookProjection()
}
