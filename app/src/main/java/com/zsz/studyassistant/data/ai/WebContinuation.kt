package com.zsz.studyassistant.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

internal data class WebContinuation(
    val messages: List<DeepSeekMessage>,
    val prefix: String,
    val history: List<DeepSeekMessage>,
    val annotations: JsonArray?
)

/** Preserve signed tool steps as separate assistant turns; never rewrite their raw content. */
internal fun webContinuation(
    messages: List<DeepSeekMessage>, prefix: String, history: List<DeepSeekMessage>,
    annotations: JsonArray?, resumable: AiResponseContext?
): WebContinuation {
    // A broken stream has no complete final metadata. Retry its original request,
    // retaining only earlier segments that reached a verified terminal boundary.
    if (resumable == null || resumable.rawContent.isBlank()) {
        return WebContinuation(messages, prefix, history, annotations)
    }
    val turn = listOf(
        DeepSeekMessage("assistant", JsonPrimitive(resumable.rawContent), resumable.reasoningContent, resumable.serverToolCalls),
        DeepSeekMessage("user", JsonPrimitive("请接着上面未写完的内容继续输出，从中断处直接续写；不要重复已经写过的部分，也不要重新开始。"))
    )
    return WebContinuation(messages + turn, prefix + ThinkingOutput.split(resumable.rawContent).body,
        history + turn, mergeAnnotations(annotations, resumable.annotations))
}

internal fun completedWebContext(context: AiResponseContext?, history: List<DeepSeekMessage>, annotations: JsonArray?): AiResponseContext? =
    context?.copy(continuationHistory = history, annotations = mergeAnnotations(annotations, context.annotations))

// Annotation offsets are not replayed to the API; the UI uses only the source URLs/titles.
private fun mergeAnnotations(first: JsonArray?, second: JsonArray?): JsonArray? =
    (first.orEmpty() + second.orEmpty()).takeIf { it.isNotEmpty() }?.let(::JsonArray)
