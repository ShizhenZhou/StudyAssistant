package com.zsz.studyassistant.data

import com.zsz.studyassistant.ChatItem
import com.zsz.studyassistant.ui.UiLang
import com.zsz.studyassistant.ui.stringsFor

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
    if (isLegacyTextGrading(questionText, original?.content)) {
        return GradingInput(questionText, emptyList(), original?.images?.firstOrNull() ?: storedImages.firstOrNull())
    }
    // Camera grading has one question photo, followed by an optional answer photo.
    val photos = original?.images.orEmpty().ifEmpty { storedImages }
    return GradingInput(null, photos.take(1), photos.getOrNull(1))
}

private val gradeLabels by lazy {
    UiLang.entries.filter { it != UiLang.SYSTEM }.map { language ->
        val strings = stringsFor(language)
        strings["grade.label.question"] to strings["grade.label.answer"]
    }
}

private fun isCameraGradingLabel(content: String?): Boolean = gradeLabels.any { (question, answer) ->
    content == question || content == "$question + $answer"
}

internal fun isLegacySinglePhotoGradingLabel(content: String?): Boolean = gradeLabels.any { (question, _) ->
    content == question
}

internal enum class LegacyGradingKind { CAMERA, TEXT_REDO }

/** The history row can expire before its saved grading question. */
internal fun legacyGradingKind(content: String?, singlePhotoWithCorrection: Boolean = false,
                               matchesQuestionText: (String) -> Boolean): LegacyGradingKind? {
    if (content == null) return null
    for ((question, answer) in gradeLabels) {
        if (content == "$question + $answer") return LegacyGradingKind.CAMERA
        if (singlePhotoWithCorrection && content == question) return LegacyGradingKind.CAMERA
        val suffix = "\n$answer"
        if (content.endsWith(suffix) && matchesQuestionText(content.dropLast(suffix.length))) return LegacyGradingKind.TEXT_REDO
    }
    return null
}

internal fun isLegacyTextGrading(questionText: String, content: String?): Boolean =
    questionText.isNotBlank() && content?.startsWith(questionText + "\n") == true

/** Only the first photo's correction belongs to the camera question. */
internal fun reviewCameraQuestionText(questionText: String, content: String?, correction: String?): String =
    correction?.takeIf { it.isNotBlank() }
        ?: content?.takeUnless { it.isBlank() || isCameraGradingLabel(it) }
        ?: questionText.takeUnless(::isCameraGradingLabel).orEmpty()

internal fun reviewGradingInput(
    grading: Boolean, questionText: String, items: List<ChatItem>, storedImages: List<String>
): GradingInput? {
    val question = items.firstOrNull { it.role == "question" }
    val correction = question?.ocrCorrections?.firstOrNull()
    val singlePhotoWithCorrection = !correction.isNullOrBlank() &&
        question?.images.orEmpty().ifEmpty { storedImages }.size == 1
    val knownGrading = grading || storedImages.isNotEmpty() &&
        legacyGradingKind(question?.content, singlePhotoWithCorrection) { it == questionText } != null
    val input = gradingInput(knownGrading, questionText, items, storedImages) ?: return null
    return input.copy(questionText = input.questionText ?: reviewCameraQuestionText(
        questionText, question?.content, correction))
}
