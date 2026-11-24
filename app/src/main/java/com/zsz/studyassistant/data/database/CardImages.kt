package com.zsz.studyassistant.data

import androidx.room3.withReadTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class QuestionCardContent(val questionText: String?, val images: List<ByteArray>)

internal suspend fun AppDatabase.previewImages(qid: QuestionId): List<ByteArray> = previewCard(qid, null).images

/** Review reads the first question's grading metadata without loading the full conversation. */
internal suspend fun AppDatabase.previewCard(qid: QuestionId, reviewText: String?): QuestionCardContent = withReadTransaction {
    val dao = questionDao()
    // The DAO's @Transaction convenience methods request a writer. Read their chunks on this reader.
    val stored = dao.imageHeadersForQuestion(qid)
    suspend fun storedImage(id: Long) = checkNotNull(readChunkedBlob(dao.imageSize(id)) { offset, count ->
        dao.imagePart(id, offset, count)
    })
    suspend fun primaryImages() = listOfNotNull(readChunkedBlob(dao.questionImageSize(qid)) { offset, count ->
        dao.questionImagePart(qid, offset, count)
    })
    if (reviewText == null && stored.isNotEmpty()) {
        return@withReadTransaction QuestionCardContent(null, stored.map { storedImage(it.id) })
    }
    val historyGrading = reviewText != null && dao.sessionLightOnce(qid)?.source == "grade"
    fun savedLabel(content: String?, singlePhotoWithCorrection: Boolean = false) =
        legacyGradingKind(content, singlePhotoWithCorrection) { it.previewText() == reviewText }
    val needsGradingDetails: ((String?) -> Boolean)? = reviewText?.let {
        { content -> historyGrading || savedLabel(content) != null || isLegacySinglePhotoGradingLabel(content) }
    }
    val operation = currentCoroutineContext()
    val legacy = usePrepared("SELECT substr(conversationJson,?,?) FROM questions WHERE id=?") { statement ->
        legacyQuestionPreview(stored.isEmpty(), needsGradingDetails) { offset, count ->
            operation.ensureActive()
            statement.reset()
            statement.bindInt(1, offset)
            statement.bindInt(2, count)
            statement.bindText(3, qid)
            if (statement.step() && !statement.isNull(0)) statement.getText(0) else null
        }
    }
    val singlePhotoWithCorrection = isLegacySinglePhotoGradingLabel(legacy.content) &&
        !legacy.questionCorrection.isNullOrBlank() && when {
            stored.isNotEmpty() -> stored.size == 1
            legacy.imageSlots.isNotEmpty() -> legacy.imageSlots.singleOrNull() != null
            else -> (dao.questionImageSize(qid) ?: 0) > 0
        }
    val kind = when {
        reviewText == null || legacy.gradePart == "question" -> null
        else -> savedLabel(legacy.content ?: reviewText, singlePhotoWithCorrection) ?: when {
            !historyGrading || legacy.content == null -> null
            isLegacyTextGrading(reviewText, legacy.content) -> LegacyGradingKind.TEXT_REDO
            else -> LegacyGradingKind.CAMERA
        }
    }
    if (kind == LegacyGradingKind.CAMERA) {
        val images = when {
            stored.isNotEmpty() -> listOfNotNull(stored.firstOrNull { it.position == 0 }?.let { storedImage(it.id) })
            legacy.imageSlots.isNotEmpty() -> listOfNotNull(legacy.imageSlots.first())
            else -> primaryImages()
        }
        return@withReadTransaction QuestionCardContent(
            reviewCameraQuestionText(reviewText.orEmpty(), legacy.content, legacy.questionCorrection).previewText(), images)
    }
    if (kind == LegacyGradingKind.TEXT_REDO || historyGrading && legacy.content == null) {
        return@withReadTransaction QuestionCardContent(reviewText, emptyList())
    }
    val images = if (stored.isNotEmpty()) stored.map { storedImage(it.id) } else {
        val primary = primaryImages()
        primary + additionalQuestionImages(primary, legacy.imageSlots.filterNotNull())
    }
    QuestionCardContent(reviewText, images)
}
