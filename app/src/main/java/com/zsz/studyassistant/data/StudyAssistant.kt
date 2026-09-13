package com.zsz.studyassistant.data

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 核心业务逻辑：DeepSeek 视觉/文本模型 + 对话流 + 图片预处理
 * API Key 由 KeyManager（Keystore 解密）运行时提供
 */
object StudyAssistant {

    // V4.1-Flash：原生多模态，文字/图片通用。
    // （旧 deepseek-v4-flash-vision-exp 已退役、deepseek-v4-pro 于 2026/09/14 起被路由到 V4.1-Flash）
    const val MODEL_VISION = "deepseek-flash"
    const val MODEL_TEXT = "deepseek-flash"

    fun requireKey() {
        if (KeyManager.getApiKey().isBlank()) {
            throw IllegalStateException("尚未配置 DeepSeek API Key\n请到首页 ⚙️ 设置 里填写")
        }
    }

    data class SolveResult(val question: String, val answer: String, val category: String? = null, val tags: List<String> = emptyList())

    /** 搜题指令（单图 / 多图共用） */
    private fun solvePrompt(categories: List<String>, tags: List<String>): String {
        val catHint = if (categories.isEmpty()) "（当前没有任何分类）" else categories.joinToString("、")
        val tagHint = if (tags.isEmpty()) "无" else tags.joinToString("、")
        return "请识别图片中的理工科题目并给出详细分步解答。" +
            "先输出一行“题目：<识别到的题目>”，再输出“解答：<详细步骤与结论>”。" +
            "解答最后另起一行输出“分类：<所属科目>”。" +
            "已知分类：$catHint。若题目属于其中某一个，请直接用该分类名作为“分类”，不要新造；" +
            "若都不符合，才给出一个新的简短科目名。" +
            "再在“分类”之后另起一行输出“知识点：<核心知识点1、知识点2、知识点3>”，最多 5 个、用中文顿号分隔。" +
            "已知知识点标签：$tagHint。若题目涉及其中某个，请直接使用该标签名，不要新造；都不符合才给出新的简短标签。" +
            "数学公式请用 LaTeX 书写。"
    }

    /** 视觉模型首条用户消息（文字 + 图片）；带已有分类名，让模型优先归入已有分类 */
    fun visionUserMessage(imageBytes: ByteArray, categories: List<String> = emptyList(), tags: List<String> = emptyList()): DeepSeekMessage {
        val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val content = buildJsonArray {
            addJsonObject {
                put("type", "text")
                put("text", solvePrompt(categories, tags))
            }
            addJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", "data:image/jpeg;base64,$base64") }
            }
        }
        return DeepSeekMessage("user", content)
    }

    /** 搜题（多张图一起作为题目） */
    fun visionUserMessageMulti(images: List<ByteArray>, categories: List<String> = emptyList(), tags: List<String> = emptyList()): DeepSeekMessage =
        userMessageWithImages(solvePrompt(categories, tags), images)

    fun textUserMessage(text: String): DeepSeekMessage =
        DeepSeekMessage("user", JsonPrimitive(text))

    /** 追问等场景：文字 + 1~3 张图 → 视觉模型 user 消息（多图 image_url） */
    fun userMessageWithImages(text: String, images: List<ByteArray>): DeepSeekMessage {
        val content = buildJsonArray {
            addJsonObject {
                put("type", "text")
                put("text", text)
            }
            for (img in images) {
                val base64 = Base64.encodeToString(img, Base64.NO_WRAP)
                addJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") { put("url", "data:image/jpeg;base64,$base64") }
                }
            }
        }
        return DeepSeekMessage("user", content)
    }

    fun systemMessage(): DeepSeekMessage = DeepSeekMessage(
        "system",
        JsonPrimitive(
            "你是一名理工科大学解题助手。请给出清晰、分步的解答过程，包含必要的公式推导，" +
                "数学公式请用 LaTeX 书写（$...$ 或 $$...$$），最后明确给出结论。"
        )
    )

    /** 通用调用：发送一组消息，返回助手回复文本 */
    suspend fun chatOnce(model: String, messages: List<DeepSeekMessage>): String {
        requireKey()
        val resp = ApiClient.deepSeek.chat(
            DeepSeekRequest(model = model, messages = messages, maxTokens = 4096)
        )
        return resp.choices.firstOrNull()?.message?.content?.asText()
            ?: throw IllegalStateException("DeepSeek 返回为空")
    }

    /** 批改：单张(仅题目)或两张(题目+手写答案)，AI 判断正误、指出错误步骤、针对性讲解 */
    suspend fun gradeWithImages(questionBytes: ByteArray, answerBytes: ByteArray?): String {
        requireKey()
        val q = Base64.encodeToString(questionBytes, Base64.NO_WRAP)
        val parts = buildJsonArray {
            addJsonObject {
                put("type", "text")
                put(
                    "text",
                    if (answerBytes != null)
                        "你是一名批改老师。图片中是{题目}和{学生的手写作答}。" +
                            "请批改：①判断作答是否正确；②若不正确，指出错在哪一步、为什么错；" +
                            "③给出正确的解题过程，并针对错误点做针对性讲解。用 LaTeX 写公式，先输出「结论：」再输出「讲解：」。"
                    else
                        "请识别图片中的题目，并给出完整、分步的解答过程，用 LaTeX 写公式。"
                )
            }
            addJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", "data:image/jpeg;base64,$q") }
            }
            if (answerBytes != null) {
                val a = Base64.encodeToString(answerBytes, Base64.NO_WRAP)
                addJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") { put("url", "data:image/jpeg;base64,$a") }
                }
            }
        }
        val resp = ApiClient.deepSeek.chat(
            DeepSeekRequest(model = MODEL_VISION, messages = listOf(DeepSeekMessage("user", parts)), maxTokens = 4096)
        )
        return resp.choices.firstOrNull()?.message?.content?.asText()
            ?: throw IllegalStateException("批改返回为空")
    }

    /** 从视觉模型输出中分离「题目」「解答」与推测的「分类」 */
    fun parseVisionOutput(output: String): SolveResult {
        val question = Regex("题目[:：]\\s*(.+)").find(output)?.groupValues?.get(1)?.trim()
        val category = Regex("分类[:：]\\s*(.+)").find(output)?.groupValues?.get(1)?.trim()
        // 知识点：按 、 / ， / 逗号 拆分，最多 5 个
        val tags = Regex("知识点[:：]\\s*(.+)").find(output)?.groupValues?.get(1)
            ?.split('、', '，', ',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.take(5)
            ?: emptyList()
        // 解答：取"解答："之后，去掉末尾的"分类：/知识点："整块
        var answer = Regex("解答[:：]([\\s\\S]+)").find(output)?.groupValues?.get(1)?.trim() ?: output
        answer = answer.replace(Regex("\\n*\\s*(?:分类|知识点)[:：].*$", RegexOption.DOT_MATCHES_ALL), "").trim()
        return SolveResult(
            question = question?.takeIf { it.isNotBlank() } ?: output.take(80),
            answer = answer,
            category = category?.takeIf { it.isNotBlank() },
            tags = tags
        )
    }

    private fun JsonElement.asText(): String = (this as? JsonPrimitive)?.content ?: toString()

    /** 同类题结果：AI 出的题目 + 完整解答 */
    data class SimilarResult(val question: String, val answer: String)

    /** 根据错题出一道同知识点、同类题型、难度相近的近似题，并自备完整解答（确保可解） */
    suspend fun generateSimilarQuestion(question: Question): SimilarResult {
        requireKey()
        val content = "请根据下面这道题，出一道同知识点、同类题型、难度相近的“近似题”，并自行给出完整、正确的解答（务必确保题目可解）。" +
            "原题：${question.text}\n\n请先输出一行“题目：<新题>”，再输出“解答：<完整步骤与结论>”。数学公式用 LaTeX。"
        val msgs = listOf(
            systemMessage(),
            DeepSeekMessage("user", JsonPrimitive(content))
        )
        val resp = ApiClient.deepSeek.chat(
            DeepSeekRequest(model = MODEL_TEXT, messages = msgs, maxTokens = 4096)
        )
        val out = resp.choices.firstOrNull()?.message?.content?.asText()
            ?: throw IllegalStateException("出题返回为空")
        val q = Regex("题目[:：]\\s*(.+)").find(out)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() } ?: out.take(120)
        val a = Regex("解答[:：]([\\s\\S]+)").find(out)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() } ?: out
        return SimilarResult(q, a)
    }

    /** 图片压缩为 JPEG 字节（发送前处理，最大边长 maxDim，质量 quality） */
    fun compressImage(file: File, maxDim: Int = 1280, quality: Int = 80): ByteArray {
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
