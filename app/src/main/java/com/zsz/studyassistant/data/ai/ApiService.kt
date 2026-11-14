package com.zsz.studyassistant.data

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Multipart
import retrofit2.http.Part
import retrofit2.http.Header
import okhttp3.MultipartBody
import okhttp3.RequestBody
import java.util.concurrent.TimeUnit

interface DeepSeekService {
    @Multipart
    @POST("files")
    suspend fun uploadImage(@Part("purpose") purpose: RequestBody,
        @Part("expires_after[anchor]") anchor: RequestBody,
        @Part("expires_after[seconds]") seconds: RequestBody,
        @Part file: MultipartBody.Part,
        @Header("Authorization") authorization: String? = null): DeepSeekFile
    @POST("chat/completions")
    suspend fun chat(@Body request: DeepSeekRequest): DeepSeekResponse

    /**
     * 流式：@Streaming 让 Retrofit 不把响应整体缓冲，可以逐行读 SSE。
     * 返回 ResponseBody，调用方负责 close（用 use{}）。
     */
    @Streaming
    @POST("chat/completions")
    fun chatStream(@Body request: DeepSeekRequest,
        @Header("Authorization") authorization: String? = null): retrofit2.Call<ResponseBody>
}

object ApiClient {
    private val json = Json { ignoreUnknownKeys = true }

    /** 运行时动态读取用户填写的 key（Keystore 解密） */
    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val req = if (original.header("Authorization") != null) original else original.newBuilder()
            .header("Authorization", "Bearer ${KeyManager.getApiKey()}").build()
        chain.proceed(req)
    }

    private val client by lazy {
        OkHttpClient.Builder().addInterceptor(authInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()
    }

    private fun service(client: OkHttpClient): DeepSeekService = Retrofit.Builder()
        .baseUrl("https://api.deepseek.com/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build().create(DeepSeekService::class.java)

    val deepSeek: DeepSeekService by lazy { service(client) }
    internal val imageUpload: DeepSeekService by lazy {
        service(client.newBuilder().retryOnConnectionFailure(false).build())
    }
    // Multipart parsing can outlive upload. Never transparently replay billable uploads.
    internal val files: DeepSeekFileService by lazy {
        Retrofit.Builder().baseUrl("https://api.deepseek.com/")
            .client(client.newBuilder().retryOnConnectionFailure(false)
                .readTimeout(250, TimeUnit.SECONDS).callTimeout(270, TimeUnit.SECONDS).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(DeepSeekFileService::class.java)
    }
    internal val ocr: DeepSeekOcrService by lazy {
        Retrofit.Builder().baseUrl("https://api.deepseek.com/")
            .client(client.newBuilder().retryOnConnectionFailure(false)
                .writeTimeout(30, TimeUnit.SECONDS).callTimeout(200, TimeUnit.SECONDS).build())
            .build().create(DeepSeekOcrService::class.java)
    }
    // Only the explicitly unlimited crop request may wait indefinitely for a response.
    val unlimitedCrop: DeepSeekService by lazy {
        service(client.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build())
    }

    /**
     * 流式专用：**不加整体 callTimeout**——长解答边生成边返回，用总时长上限会中途掐断；
     * readTimeout 只约束「相邻数据块之间的静默」，120s 足够模型思考。
     */
    val deepSeekStream: DeepSeekService by lazy {
        // newBuilder shares the connection pool and dispatcher with ordinary requests.
        service(client.newBuilder().retryOnConnectionFailure(false).callTimeout(0, TimeUnit.SECONDS).build())
    }
}
