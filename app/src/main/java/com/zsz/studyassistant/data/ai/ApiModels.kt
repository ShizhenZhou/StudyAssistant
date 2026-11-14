package com.zsz.studyassistant.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Serializable
data class DeepSeekMessage(val role: String, val content: JsonElement,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("server_tool_calls") val serverToolCalls: JsonArray? = null,
    /** Private draft data, removed by document preparation before network serialization. */
    val localDocuments: List<ConversationDocument> = emptyList())

@Serializable
data class DeepSeekRequest(
    val model: String,
    val messages: List<DeepSeekMessage>,
    @SerialName("max_tokens") val maxTokens: Int = 4096,
    val temperature: Double = 0.3,
    /** true = 流式（SSE，逐块返回）；仅解题/追问链路使用 */
    val stream: Boolean = false,
    /** 流式时让服务端在最后一片里带上 token 用量，保证「API 管理」的统计不断档 */
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
    /**
     * 思考模式开关（官方 OpenAI 格式：{"thinking":{"type":"enabled"|"disabled"}}）。
     * null = 不传该字段，用服务端默认（默认就是 enabled、effort=high）。
     */
    val thinking: ThinkingOption? = null,
    /** 思考强度：low / high / max（仅思考模式有效） */
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    /** Optional structured output for label analysis; other callers retain their current behavior. */
    @SerialName("response_format") val responseFormat: DeepSeekResponseFormat? = null,
    val tools: List<DeepSeekTool>? = null
)

@Serializable
data class DeepSeekTool(val type: String)

@Serializable
data class DeepSeekFile(val id: String, @SerialName("expires_at") val expiresAt: Long? = null,
    val bytes: Long = 0, val filename: String = "", val purpose: String = "",
    @SerialName("created_at") val createdAt: Long = 0)

/** Exact assistant payload for tool context; presentation/section parsing must not rewrite it. */
@Serializable
data class AiResponseContext(val rawContent: String, val reasoningContent: String? = null,
    val serverToolCalls: JsonArray? = null, val annotations: JsonArray? = null,
    val continuationHistory: List<DeepSeekMessage> = emptyList())

@Serializable
data class DeepSeekResponseFormat(val type: String)

@Serializable
data class ThinkingOption(val type: String)

@Serializable
data class StreamOptions(@SerialName("include_usage") val includeUsage: Boolean)

@Serializable
data class DeepSeekChoice(val message: DeepSeekMessage,
    @SerialName("finish_reason") val finishReason: String? = null)

/** 接口返回的 token 用量（用于「API 管理」里的用量统计） */
@Serializable
data class DeepSeekUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("prompt_cache_hit_tokens") val cacheHitTokens: Int = 0,
    @SerialName("server_tool_use") val serverToolUse: DeepSeekToolUsage? = null
)

@Serializable
data class DeepSeekToolUsage(
    @SerialName("tool_search") val searches: Int = 0,
    @SerialName("tool_fetch") val fetches: Int = 0,
    @SerialName("tool_image_search") val imageSearches: Int = 0)

@Serializable
data class DeepSeekResponse(val choices: List<DeepSeekChoice>, val usage: DeepSeekUsage? = null)

// ---- 流式（SSE）分片 ----
// 每个 "data: {...}" 形如：{"choices":[{"delta":{"content":"增"},"finish_reason":null}],"usage":null}
// 最后一片（include_usage 生效时）choices 为空、usage 有值。纯解析用，字段全部可选。

@Serializable
data class StreamChunk(
    val choices: List<StreamChoice> = emptyList(),
    val usage: DeepSeekUsage? = null,
    val error: JsonObject? = null
)

@Serializable
data class StreamChoice(val delta: StreamDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
    @SerialName("server_tool_event") val serverToolEvent: JsonObject? = null)

@Serializable
data class StreamDelta(
    val content: String? = null,
    /** 推理模型（如 deepseek-reasoner）的思考增量；非推理模型不会返回该字段 */
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("server_tool_calls") val serverToolCalls: JsonArray? = null,
    val annotations: JsonArray? = null
)

