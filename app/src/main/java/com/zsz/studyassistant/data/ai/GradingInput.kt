package com.zsz.studyassistant.data

import com.zsz.studyassistant.ChatItem

/** In-memory interpretation of the existing conversation. Images are already Base64 encoded. */
internal data class GradingInput(
    val questionText: String?,
    val questionImages: List<String>,
    val answerImage: String?
) {
    val canGrade: Boolean
        get() = if (questionText != null) !answerImage.isNullOrBlank() else questionImages.isNotEmpty()
}

internal fun gradingInput(
    grading: Boolean,
    questionText: String,
    items: List<ChatItem>,
    storedImages: List<String>
): GradingInput? {
    val original = items.firstOrNull { it.role == "question" }
    if (original?.gradePart == "question") {
        val answer = items.firstOrNull { it.role == "user" && it.gradePart == "answer" }
        return GradingInput(questionText, original.images.orEmpty(), answer?.images?.singleOrNull())
    }
    if (!grading) return null

    // Older handwritten redos put the answer image and a localized label on the question.
    if (questionText.isNotBlank() && original?.content?.startsWith(questionText + "\n") == true) {
        return GradingInput(questionText, emptyList(), original.images?.firstOrNull() ?: storedImages.firstOrNull())
    }
    // Camera grading has one question photo, followed by an optional answer photo.
    val photos = original?.images.orEmpty().ifEmpty { storedImages }
    return GradingInput(null, photos.take(1), photos.getOrNull(1))
}
