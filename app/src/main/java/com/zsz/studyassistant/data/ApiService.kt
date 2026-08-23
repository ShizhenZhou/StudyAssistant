package com.zsz.studyassistant.data

import com.zsz.studyassistant.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

interface DeepSeekService {
    @POST("chat/completions")
    suspend fun chat(@Body request: DeepSeekRequest): DeepSeekResponse
}

object ApiClient {
    private val json = Json { ignoreUnknownKeys = true }

    val deepSeek: DeepSeekService by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Authorization", "Bearer ${BuildConfig.DEEPSEEK_API_KEY}")
                    .build()
                chain.proceed(req)
            }
            .build()
        Retrofit.Builder()
            .baseUrl("https://api.deepseek.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DeepSeekService::class.java)
    }
}
