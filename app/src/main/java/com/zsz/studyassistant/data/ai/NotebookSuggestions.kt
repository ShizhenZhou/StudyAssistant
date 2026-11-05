package com.zsz.studyassistant.data

import com.zsz.studyassistant.ChatItem
import com.zsz.studyassistant.ui.UiLang
import com.zsz.studyassistant.ui.stringsFor
import kotlinx.serialization.Serializable

/** Original AI metadata travels with the answer, independently of saved notebook edits. */
@Serializable
data class NotebookSuggestions(val category: String? = null, val tags: List<String> = emptyList())

internal fun firstAnswerSuggestions(
    messages: List<ChatItem>,
    fallback: NotebookSuggestions = NotebookSuggestions()
): NotebookSuggestions {
    val first = messages.firstOrNull { it.role == "assistant" } ?: return fallback
    first.notebookSuggestions?.let { return it }
    val parsed = StudyAssistant.parseVisionOutput(first.content)
    val hintTags = UiLang.entries.firstNotNullOfOrNull { lang ->
        val hint = stringsFor(lang)["solve.tagsHint"].trim()
        first.content.takeIf { hint in it }?.substringAfterLast(hint)?.lineSequence()?.firstOrNull()
    }?.split('、', '，', ',')?.map { it.trim() }?.filter { it.isNotBlank() }?.take(5).orEmpty()
    return NotebookSuggestions(
        parsed.category?.takeIf { it.isNotBlank() } ?: fallback.category,
        parsed.tags.ifEmpty { hintTags }.ifEmpty { fallback.tags }
    )
}
