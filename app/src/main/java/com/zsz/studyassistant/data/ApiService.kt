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
import java.util.concurrent.TimeUnit

interface DeepSeekService {
    @POST("chat/completions")
    suspend fun chat(@Body request: DeepSeekRequest): DeepSeekResponse

    /**
     * 流式：@Streaming 让 Retrofit 不把响应整体缓冲，可以逐行读 SSE。
     * 返回 ResponseBody，调用方负责 close（用 use{}）。
     */
    @Streaming
    @POST("chat/completions")
    suspend fun chatStream(@Body request: DeepSeekRequest): ResponseBody
}

object ApiClient {
    private val json = Json { ignoreUnknownKeys = true }

    /** 运行时动态读取用户填写的 key（Keystore 解密） */
    private val authInterceptor = Interceptor { chain ->
        val key = KeyManager.getApiKey()
        val req = chain.request().newBuilder()
            .header("Authorization", "Bearer $key")
            .build()
        chain.proceed(req)
    }

    val deepSeek: DeepSeekService by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            // 视觉模型带长推理 + 大图片上传，默认 10s 超时会导致 timeout，放宽到 3 分钟
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://api.deepseek.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DeepSeekService::class.java)
    }

    /**
     * 流式专用：**不加整体 callTimeout**——长解答边生成边返回，用总时长上限会中途掐断；
     * readTimeout 只约束「相邻数据块之间的静默」，120s 足够模型思考。
     */
    val deepSeekStream: DeepSeekService by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)   // 0 = 不限总时长
            .build()
        Retrofit.Builder()
            .baseUrl("https://api.deepseek.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DeepSeekService::class.java)
    }
}
