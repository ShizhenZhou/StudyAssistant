package com.zsz.studyassistant.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

private val continueInstruction = DeepSeekMessage("user", JsonPrimitive("请接着上面未写完的内容继续输出，从中断处直接续写；不要重复已经写过的部分，也不要重新开始。"))

internal data class ContinuationContext(
    val messages: List<DeepSeekMessage>,
    val prefix: String,
    val history: List<DeepSeekMessage>,
    val annotations: JsonArray?
)

/** Plain requests keep the original turns separate from the accumulated output. */
internal fun ContinuationContext.messagesForRequest(webEnabled: Boolean): List<DeepSeekMessage> =
    if (webEnabled || prefix.isBlank()) messages
    else messages + DeepSeekMessage("assistant", JsonPrimitive(prefix)) + continueInstruction

internal fun continuationContext(
    messages: List<DeepSeekMessage>, partial: String, requestPrefix: String,
    webEnabled: Boolean, history: List<DeepSeekMessage>, annotations: JsonArray?, resumable: AiResponseContext?
): ContinuationContext {
    if (webEnabled) return webContinuation(messages, requestPrefix, history, annotations, resumable)

    // Earlier drafts saved the expanded request at each resume. Recognize only the
    // synthetic suffix tied to that draft's prefix, leaving ordinary chat history intact.
    var end = messages.size
    var previous = requestPrefix
    while (previous.isNotBlank() && end >= 3 && messages[end - 1] == continueInstruction) {
        val turn = messages[end - 2]
        val text = (turn.content as? JsonPrimitive)?.content ?: break
        if (text.isBlank() || !previous.startsWith(text) ||
            turn != DeepSeekMessage("assistant", JsonPrimitive(text)) ||
            (end == messages.size && text != requestPrefix)) break
        end -= 2
        previous = text
    }
    return ContinuationContext(messages.takeIf { end == it.size } ?: messages.take(end), partial, emptyList(), null)
}

/** Preserve signed tool steps as separate assistant turns; never rewrite their raw content. */
internal fun webContinuation(
    messages: List<DeepSeekMessage>, prefix: String, history: List<DeepSeekMessage>,
    annotations: JsonArray?, resumable: AiResponseContext?
): ContinuationContext {
    // A broken stream has no complete final metadata. Retry its original request,
    // retaining only earlier segments that reached a verified terminal boundary.
    if (resumable == null || resumable.rawContent.isBlank()) {
        return ContinuationContext(messages, prefix, history, annotations)
    }
    val turn = listOf(
        DeepSeekMessage("assistant", JsonPrimitive(resumable.rawContent), resumable.reasoningContent, resumable.serverToolCalls),
        continueInstruction
    )
    return ContinuationContext(messages + turn, ThinkingOutput.split(resumable.rawContent, prefix).body,
        history + turn, mergeAnnotations(annotations, resumable.annotations))
}

internal fun completedWebContext(context: AiResponseContext?, history: List<DeepSeekMessage>, annotations: JsonArray?): AiResponseContext? =
    context?.copy(continuationHistory = history, annotations = mergeAnnotations(annotations, context.annotations))

// Annotation offsets are not replayed to the API; the UI uses only the source URLs/titles.
private fun mergeAnnotations(first: JsonArray?, second: JsonArray?): JsonArray? =
    (first.orEmpty() + second.orEmpty()).takeIf { it.isNotEmpty() }?.let(::JsonArray)
