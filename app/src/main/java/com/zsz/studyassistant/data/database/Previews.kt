package com.zsz.studyassistant.data

/** Read-only projections. Truncated display content must never enter entity write APIs. */
data class QuestionPreview(
    val id: QuestionId, val text: String, val answer: String,
    val createdAt: Long, val deleted: Boolean = false, val categoryId: Long? = null
)

data class SolveSessionPreview(
    val id: QuestionId, val createdAt: Long, val updatedAt: Long,
    val source: String = "camera", val text: String = "", val answer: String = "",
    val savedQuestionId: QuestionId? = null
)

private fun String.previewText(): String = substring(0, offsetByCodePoints(0, minOf(256, codePointCount(0, length))))
fun Question.toPreview() = QuestionPreview(id, text.previewText(), answer.previewText(), createdAt, deleted, categoryId)
fun SolveSession.toPreview() = SolveSessionPreview(id, createdAt, updatedAt, source, text.previewText(), answer.previewText(), savedQuestionId)
