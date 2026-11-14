package com.zsz.studyassistant.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.*

/** Kept separate from chat: uploads/OCR must never be retried as ordinary completions. */
internal interface DeepSeekFileService {
    @Multipart @POST("files")
    suspend fun upload(@Header("Authorization") authorization: String,
        @Part("purpose") purpose: RequestBody,
        @Part("expires_after[anchor]") anchor: RequestBody,
        @Part("expires_after[seconds]") seconds: RequestBody,
        @Part file: MultipartBody.Part): DeepSeekFile

    @GET("files/{id}")
    suspend fun retrieve(@Header("Authorization") authorization: String, @Path("id") id: String): DeepSeekFile

    @GET("files")
    suspend fun list(@Header("Authorization") authorization: String,
        @Query("after") after: String?, @Query("limit") limit: Int,
        @Query("order") order: String, @Query("purpose") purpose: String?): AiFilePage

    @DELETE("files/{id}")
    suspend fun delete(@Header("Authorization") authorization: String, @Path("id") id: String): AiFileDeletion
}

internal interface DeepSeekOcrService {
    // OCR accepts exactly purpose + file; expiry/page-range fields are not supported.
    @Streaming @Multipart @POST("v1/tools/ocr")
    suspend fun recognize(@Header("Authorization") authorization: String,
        @Part("purpose") purpose: RequestBody, @Part file: MultipartBody.Part): ResponseBody
}

enum class DocumentRecognition(val wireValue: String) {
    TEXT("text_recognition"), TEXT_AND_FIGURES("rich_layout_recognition")
}

@Serializable
data class AiFilePage(val data: List<DeepSeekFile>,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("last_id") val lastId: String? = null)

@Serializable
data class AiFileDeletion(val id: String, val deleted: Boolean)

@Serializable
data class OcrPage(val index: Int, val text: String,
    @SerialName("finish_reason") val finishReason: String) {
    val complete: Boolean get() = finishReason == "stop"
}

/** OCR token counts are informational; billing is based on pages, not chat token usage. */
@Serializable
data class OcrUsage(@SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0)

@Serializable
data class OcrResult(val id: String, val purpose: String,
    @SerialName("page_count") val pageCount: Int, val pages: List<OcrPage>, val usage: OcrUsage? = null) {
    val complete: Boolean get() = pages.all { it.complete }

    /** Selection happens after OCR, not a promise of partial-page billing. Raw markup is retained. */
    fun selectedPages(indices: Set<Int>): List<OcrPage> {
        require(indices.isNotEmpty() && indices.all { it in 0 until pageCount })
        return pages.filter { it.index in indices }
    }
}
