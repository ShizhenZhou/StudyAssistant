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
    val temperature: Double = 0.3
)

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
