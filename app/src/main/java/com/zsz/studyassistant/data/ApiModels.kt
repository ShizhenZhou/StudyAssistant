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

@Serializable
data class DeepSeekResponse(val choices: List<DeepSeekChoice>)
