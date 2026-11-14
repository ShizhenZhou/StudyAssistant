package com.zsz.studyassistant.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
data class AiSource(val title: String, val url: String)

/** Only public HTTP(S) references supplied by the model; never executable schemes or credentials. */
fun safeSourceUrl(value: String): String? {
    if (value.length > 8192) return null
    val url = value.toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    return url.toString()
}

fun AiResponseContext.sources(): List<AiSource> = annotations.orEmpty().mapNotNull { value ->
    val annotation = value as? JsonObject ?: return@mapNotNull null
    if ((annotation["type"] as? JsonPrimitive)?.contentOrNull != "url_citation") return@mapNotNull null
    val citation = annotation["url_citation"] as? JsonObject ?: return@mapNotNull null
    val url = safeSourceUrl((citation["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()) ?: return@mapNotNull null
    val title = (citation["title"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: url
    AiSource(title.take(300), url)
}.distinctBy { it.url }.take(100)

/** UI receives a localization key, never untrusted error text or the encrypted tool payload. */
fun toolProgressKey(event: JsonObject): String? {
    if ((event["type"] as? JsonPrimitive)?.contentOrNull != "call_started") return null
    val call = event["call"] as? JsonObject ?: return null
    val action = call["action"] as? JsonObject
    return when ((action?.get("type") as? JsonPrimitive)?.contentOrNull) {
        "read_link", "open_page", "find_in_page" -> "ai.web.reading"
        else -> "ai.web.searching"
    }
}
