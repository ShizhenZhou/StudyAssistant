package com.zsz.studyassistant.data

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Base64
import com.zsz.studyassistant.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 核心业务逻辑：DeepSeek 视觉模型直接识别图片题目并解答 + 文字题目解答 + 图片预处理
 */
object StudyAssistant {

    private fun requireKey() {
        if (BuildConfig.DEEPSEEK_API_KEY.isBlank()) {
            throw IllegalStateException("未配置 DeepSeek API Key\n请在 secrets.properties 中填写 DEEPSEEK_API_KEY")
        }
    }

    /** 拍照解答：图片 -> 视觉模型识别并解答，返回 (识别出的题目, 完整解答) */
    suspend fun solveWithImage(imageBytes: ByteArray): SolveResult {
        requireKey()
        val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val content = buildJsonArray {
            addJsonObject {
                put("type", "text")
                put(
                    "text",
                    "请识别图片中的理工科题目并给出详细分步解答。" +
                        "先输出一行“题目：<识别到的题目>”，再输出“解答：<详细步骤与结论>”。" +
                        "数学公式请用 LaTeX 书写。"
                )
            }
            addJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", "data:image/jpeg;base64,$base64") }
            }
        }
        val resp = ApiClient.deepSeek.chat(
            DeepSeekRequest(
                model = "deepseek-v4-flash-vision-exp",
                messages = listOf(DeepSeekMessage("user", content)),
                maxTokens = 4096
            )
        )
        val output = resp.choices.firstOrNull()?.message?.content?.asText()
            ?: throw IllegalStateException("视觉模型返回为空")
        return parseVisionOutput(output)
    }

    /** 文字解答：手动输入的题目 -> 文本模型（v4-pro，推理更强） */
    suspend fun solveText(question: String): String {
        requireKey()
        val resp = ApiClient.deepSeek.chat(
            DeepSeekRequest(
                model = "deepseek-v4-pro",
                messages = listOf(
                    DeepSeekMessage(
                        "system",
                        JsonPrimitive(
                            "你是一名理工科大学解题助手。请给出清晰、分步的解答过程，" +
                                "包含必要的公式推导，数学公式请用 LaTeX 书写（$...$ 或 $$...$$），最后明确给出结论。"
                        )
                    ),
                    DeepSeekMessage("user", JsonPrimitive(question))
                ),
                maxTokens = 4096
            )
        )
        return resp.choices.firstOrNull()?.message?.content?.asText()
            ?: throw IllegalStateException("DeepSeek 返回为空")
    }

    /** 视觉模型输出的解析结果 */
    data class SolveResult(val question: String, val answer: String)

    /** 从视觉模型输出中分离「题目」与「解答」 */
    fun parseVisionOutput(output: String): SolveResult {
        val question = Regex("题目[:：]\\s*(.+)").find(output)?.groupValues?.get(1)?.trim()
        val answer = Regex("解答[:：]([\\s\\S]+)").find(output)?.groupValues?.get(1)?.trim()
        return SolveResult(
            question = question?.takeIf { it.isNotBlank() } ?: output.take(80),
            answer = answer ?: output
        )
    }

    private fun JsonElement.asText(): String = (this as? JsonPrimitive)?.content ?: toString()

    /** 图片压缩为 JPEG 字节（发送前处理，最大边长 maxDim，质量 quality） */
    fun compressImage(file: File, maxDim: Int = 1600, quality: Int = 85): ByteArray {
        val src = ImageDecoder.createSource(file)
        val bitmap = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val scale = maxOf(info.size.width, info.size.height).toFloat() / maxDim
            if (scale > 1f) {
                decoder.setTargetSize((info.size.width / scale).toInt(), (info.size.height / scale).toInt())
            }
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
