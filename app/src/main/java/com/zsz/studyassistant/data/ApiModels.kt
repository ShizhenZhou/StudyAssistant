package com.zsz.studyassistant.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class DeepSeekMessage(val role: String, val content: JsonElement)

@Serializable
data class DeepSeekRequest(
    val model: String,
    val messages: List<DeepSeekMessage>,
    @SerialName("max_tokens") val maxTokens: Int = 4096,
    val temperature: Double = 0.3,
    /** true = 流式（SSE，逐块返回）；仅解题/追问链路使用 */
    val stream: Boolean = false,
    /** 流式时让服务端在最后一片里带上 token 用量，保证「API 管理」的统计不断档 */
    @SerialName("stream_options") val streamOptions: StreamOptions? = null
)

@Serializable
data class StreamOptions(@SerialName("include_usage") val includeUsage: Boolean = true)

@Serializable
data class DeepSeekChoice(val message: DeepSeekMessage)

/** 接口返回的 token 用量（用于「API 管理」里的用量统计） */
@Serializable
data class DeepSeekUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0
)

@Serializable
data class DeepSeekResponse(val choices: List<DeepSeekChoice>, val usage: DeepSeekUsage? = null)

// ---- 流式（SSE）分片 ----
// 每个 "data: {...}" 形如：{"choices":[{"delta":{"content":"增"},"finish_reason":null}],"usage":null}
// 最后一片（include_usage 生效时）choices 为空、usage 有值。纯解析用，字段全部可选。

@Serializable
data class StreamChunk(
    val choices: List<StreamChoice> = emptyList(),
    val usage: DeepSeekUsage? = null
)

@Serializable
data class StreamChoice(val delta: StreamDelta? = null)

@Serializable
data class StreamDelta(
    val content: String? = null,
    /** 推理模型（如 deepseek-reasoner）的思考增量；非推理模型不会返回该字段 */
    @SerialName("reasoning_content") val reasoningContent: String? = null
)

