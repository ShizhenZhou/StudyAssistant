package com.zsz.studyassistant.data

import kotlinx.serialization.json.*
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response

/** Only classified protocol information escapes the HTTP layer; no echoed prompts or credentials. */
class AiRequestException internal constructor(response: Response<ResponseBody>,
    val fileReferenceRejected: Boolean, val parameter: String?) : HttpException(response)

internal data class AiErrorDetails(val fileReferenceRejected: Boolean, val parameter: String?)

internal fun classifyAiError(status: Int, body: String): AiErrorDetails {
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    val error = root?.get("error") as? JsonObject
    val code = (error?.get("code") as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()
    val param = (error?.get("param") as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()
    val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()
    val file = Regex("\\bfile(?:_id)?\\b").containsMatchIn("$param $message") || code.startsWith("file_") || code == "invalid_file_id"
    val rejected = code in setOf("file_expired", "file_not_found", "invalid_file_id") ||
        listOf("expired", "not found", "does not exist", "invalid file", "not accessible", "access denied").any { it in message }
    // Validation error paths may include array indices. Return only an approved top-level field.
    val paths = buildString {
        append(param)
        (root?.get("detail") as? JsonArray)?.forEach { item ->
            val loc = (item as? JsonObject)?.get("loc") as? JsonArray
            loc?.forEach { append(' '); append((it as? JsonPrimitive)?.contentOrNull.orEmpty()) }
        }
    }
    val field = listOf("thinking", "reasoning_effort", "max_tokens", "model", "tools", "messages")
        .firstOrNull { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(paths) }
    return AiErrorDetails(status in setOf(400, 404, 410, 422) && file && rejected, field)
}

internal fun aiRequestException(response: Response<ResponseBody>): AiRequestException {
    val text = runCatching {
        response.errorBody()?.use { body ->
            val source = body.source()
            source.request(65537)
            if (source.buffer.size > 65536) "" else source.readUtf8()
        }.orEmpty()
    }.getOrDefault("")
    val details = classifyAiError(response.code(), text)
    return AiRequestException(response, details.fileReferenceRejected, details.parameter)
}
