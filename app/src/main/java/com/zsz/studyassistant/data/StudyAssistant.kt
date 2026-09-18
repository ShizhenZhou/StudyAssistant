package com.zsz.studyassistant.data

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ResponseBody

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
    fun solvePrompt(categories: List<String>, tags: List<String>): String {
        val catHint = if (categories.isEmpty()) "（当前没有任何分类）" else categories.joinToString("、")
        val tagHint = if (tags.isEmpty()) "无" else tags.joinToString("、")
        return "请识别图片中的理工科题目并给出详细分步解答。请**严格按固定格式**输出，每项单独一行：" +
            "① 题目：<识别到的题目>；② 分类：<所属科目>；③ 知识点：<知识点1、知识点2、知识点3>；④ 解答：<详细步骤与结论>。" +
            "（顺序固定：先写①②③三行，再写④的正文）" +
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

    /**
     * 文字解题（图文提问的纯文字路径）：与搜题同样的输出格式（题目：/解答：/分类：/知识点：），
     * 这样错题本保存时也能自动预选科目与标签。
     */
    fun textSolveMessage(text: String, categories: List<String> = emptyList(), tags: List<String> = emptyList()): DeepSeekMessage {
        val catHint = if (categories.isEmpty()) "（当前没有任何分类）" else categories.joinToString("、")
        val tagHint = if (tags.isEmpty()) "无" else tags.joinToString("、")
        return DeepSeekMessage(
            "user",
            JsonPrimitive(
                "请解答下面这道理工科题目并给出详细分步解答。请**严格按固定格式**输出，每项单独一行：\n" +
                    "① 题目：$text（照抄即可）；② 分类：<所属科目>；③ 知识点：<知识点1、知识点2、知识点3>；④ 解答：<详细步骤与结论>（顺序固定）\n" +
                    "先输出一行“解答：<详细步骤与结论>”，再另起一行输出“分类：<所属科目>”。" +
                    "已知分类：$catHint。若属于其中某一个，请直接用该分类名，不要新造；都不符合才给出新的简短科目名。" +
                    "再另起一行输出“知识点：<核心知识点1、知识点2、知识点3>”，最多 5 个、用中文顿号分隔。已知知识点标签：$tagHint。" +
                    "数学公式请用 LaTeX 书写。"
            )
        )
    }

    fun textUserMessage(text: String): DeepSeekMessage =
        DeepSeekMessage("user", JsonPrimitive(text))

    /**
     * 【分类兜底 C】只问一次「科目 + 知识点」。
     * 关键：把**已有分类/标签反向喂给 AI**，并要求它**从列表里选一个原样输出**（封闭集选择，
     * 命中率远高于让它自由命名）；只有确实都不合适才输出 `新：<简短科目名>`。
     * 返回 (分类, 知识点列表)；失败返回 (null, emptyList())。
     */
    suspend fun classifyQuestion(
        question: String,
        categories: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        timeoutMs: Long = 8000
    ): Pair<String?, List<String>> {
        if (question.isBlank()) return null to emptyList()
        return runCatching {
            requireKey()
            val prompt = buildString {
                append("你是学科分类助手。判断下面这道理工科题目属于哪个科目、涉及哪些知识点。\n")
                append("题目：").append(question.take(600)).append("\n")
                append("已知分类（**必须优先从中选一个并原样输出**）：")
                append(if (categories.isEmpty()) "（暂无）" else categories.joinToString("、"))
                append("。只有确实都不合适，才输出 新：<简短科目名>\n")
                append("已知知识点标签（能对应上的优先使用，不要新造）：")
                append(if (tags.isEmpty()) "（暂无）" else tags.joinToString("、")).append("\n")
                append("只输出 JSON，不要任何解释：{\"分类\":\"...\",\"知识点\":[\"...\",\"...\"]}（知识点最多 5 个）")
            }
            val resp = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                ApiClient.deepSeek.chat(
                    DeepSeekRequest(
                        model = MODEL_TEXT,
                        messages = listOf(DeepSeekMessage("user", JsonPrimitive(prompt))),
                        maxTokens = 200,
                        temperature = 0.0
                    )
                )
            } ?: return@runCatching null to emptyList()
            lastUsage = resp.usage
            val txt = resp.choices.firstOrNull()?.message?.content?.asText()
                ?: return@runCatching null to emptyList()
            val s = txt.indexOf('{')
            val e = txt.lastIndexOf('}')
            if (s < 0 || e <= s) return@runCatching null to emptyList()
            val root = Json.parseToJsonElement(txt.substring(s, e + 1)).jsonObject
            val cat = root["分类"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.removePrefix("新：")?.removePrefix("新:")?.trim()
                ?.takeIf { it.isNotBlank() }
            val tgs = root["知识点"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                ?.filter { it.isNotBlank() }?.take(5) ?: emptyList()
            cat to tgs
        }.getOrDefault(null to emptyList())
    }

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

    fun systemMessage(lang: AiLang = AiLang.DEFAULT): DeepSeekMessage = DeepSeekMessage(
        "system",
        JsonPrimitive(
            "你是一名理工科大学解题助手。请给出清晰、分步的解答过程，包含必要的公式推导，" +
                "数学公式请用 LaTeX 书写（$...$ 或 $$...$$），最后明确给出结论。" +
                "\n" + languageInstruction(lang)
        )
    )

    /** 只含「回答语言」的系统消息：拍题 / 直接提问 / 追问等主链路用（不加人设，尽量少改变原有回答风格） */
    fun languageSystemMessage(lang: AiLang): DeepSeekMessage =
        DeepSeekMessage("system", JsonPrimitive(languageInstruction(lang)))

    /** 最近一次接口调用返回的 token 用量（调用方负责持久化统计） */
    @Volatile
    var lastUsage: DeepSeekUsage? = null
        private set

    /** 通用调用：发送一组消息，返回助手回复文本 */
    // [已停用 2026-09-16] 非流式旧实现，保留备查（当前全部走流式 streamCall）
//     suspend fun chatOnce(model: String, messages: List<DeepSeekMessage>): String {
//         requireKey()
//         val resp = ApiClient.deepSeek.chat(
//             DeepSeekRequest(model = model, messages = messages, maxTokens = 4096)
//         )
//         lastUsage = resp.usage
//         return resp.choices.firstOrNull()?.message?.content?.asText()
//             ?: throw IllegalStateException("DeepSeek 返回为空")
//     }

    private val streamJson = Json { ignoreUnknownKeys = true }

    /** 正在进行的流式响应体（供「中止生成」立即中断阻塞读取用） */
    @Volatile
    private var activeStreamBody: ResponseBody? = null

    /** 立即中止当前流式请求：关闭响应体 → 阻塞中的 readUtf8Line() 会立刻抛错返回 */
    fun cancelActiveStream() {
        runCatching { activeStreamBody?.close() }
        activeStreamBody = null
    }

    /**
     * 流式调用：逐块回调增量文本，返回拼接后的完整文本（供落库/展示）。
     *
     * 实现要点：
     * - 走 [ApiClient.deepSeekStream]（无整体 callTimeout），手写 SSE 逐行解析，**不引新依赖**；
     * - 只认 `data:` 行，`[DONE]` 结束；解析失败的分片直接跳过（服务端偶发心跳/空行不应中断整段）；
     * - 最后一片带 `usage` 时写入 [lastUsage]，保证「API 管理」用量统计不断档。
     *
     * @param onDelta 每收到一段增量调用一次（调用方负责节流，避免频繁重渲染）
     */
    suspend fun chatStream(
        model: String,
        messages: List<DeepSeekMessage>,
        onDelta: suspend (String) -> Unit
    ): String {
        requireKey()
        val body = ApiClient.deepSeekStream.chatStream(
            DeepSeekRequest(
                model = model,
                messages = messages,
                maxTokens = 4096,
                stream = true,
                streamOptions = StreamOptions(includeUsage = true)
            )
        )
        val sb = StringBuilder()
        var usage: DeepSeekUsage? = null
        activeStreamBody = body
        try {
            body.use { b ->
                val src = b.source()
                while (true) {
                    // 支持「中止生成」：协程被取消时在这里抛出，且关闭响应体后阻塞读也会立刻失败
                    currentCoroutineContext().ensureActive()
                    val line = src.readUtf8Line() ?: break
                    if (line.isEmpty() || !line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") break
                    val chunk = runCatching { streamJson.decodeFromString<StreamChunk>(payload) }.getOrNull() ?: continue
                    chunk.usage?.let { usage = it }
                    val delta = chunk.choices.firstOrNull()?.delta?.content
                    if (!delta.isNullOrEmpty()) {
                        sb.append(delta)
                        onDelta(delta)
                    }
                }
            }
        } finally {
            activeStreamBody = null
        }
        lastUsage = usage
        return sb.toString().ifBlank { throw IllegalStateException("DeepSeek 返回为空") }
    }

    /** 批改提示词（流式/非流式共用） */
    private fun gradePrompt(answerBytes: ByteArray?, lang: AiLang, catHint: String = "（无）", tagHint: String = "无"): String =
        (if (answerBytes != null)
            "你是一名批改老师。图片中是{题目}和{学生的手写作答}。" +
                "请批改：①判断作答是否正确；②若不正确，指出错在哪一步、为什么错；" +
                "③给出正确的解题过程，并针对错误点做针对性讲解。用 LaTeX 写公式，先输出「结论：」再输出「讲解：」。"
        else
            "请识别图片中的题目，并给出完整、分步的解答过程，用 LaTeX 写公式。") +
            // 补上分类/知识点：错题本保存时要自动预选科目与标签（批改页同样需要）
            "\n解答最后另起一行输出“分类：<所属科目>”。已知分类：$catHint。属于其中某一类就直接用该名称，不要新造。" +
            "\n再另起一行输出“知识点：<核心知识点1、知识点2、知识点3>”，最多 5 个、用中文顿号分隔。可用标签：$tagHint。" +
            "\n" + languageInstruction(lang)

    /**
     * 批改的 user 消息（文字提示 + 题目图 + 可选作答图）。
     * 批改页复用解题界面时用它构造首轮请求；追问走常规 buildMessages。
     */
    fun gradeUserMessage(
        questionBytes: ByteArray,
        answerBytes: ByteArray?,
        lang: AiLang = AiLang.DEFAULT,
        categories: List<String> = emptyList(),
        tags: List<String> = emptyList()
    ): DeepSeekMessage {
        val parts = buildJsonArray {
            addJsonObject {
                put("type", "text")
                put("text", gradePrompt(answerBytes, lang, if (categories.isEmpty()) "（无）" else categories.joinToString("、"), if (tags.isEmpty()) "无" else tags.joinToString("、")))
            }
            addJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", "data:image/jpeg;base64," + Base64.encodeToString(questionBytes, Base64.NO_WRAP)) }
            }
            if (answerBytes != null) {
                addJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") { put("url", "data:image/jpeg;base64," + Base64.encodeToString(answerBytes, Base64.NO_WRAP)) }
                }
            }
        }
        return DeepSeekMessage("user", parts)
    }

    /** 批改：单张(仅题目)或两张(题目+手写答案)，AI 判断正误、指出错误步骤、针对性讲解 */
    suspend fun gradeWithImages(questionBytes: ByteArray, answerBytes: ByteArray?, lang: AiLang = AiLang.DEFAULT): String {
        requireKey()
        val resp = ApiClient.deepSeek.chat(
            DeepSeekRequest(
                model = MODEL_VISION,
                messages = listOf(DeepSeekMessage("user", gradeUserMessage(questionBytes, answerBytes, lang).content)),
                maxTokens = 4096
            )
        )
        lastUsage = resp.usage
        return resp.choices.firstOrNull()?.message?.content?.asText()
            ?: throw IllegalStateException("批改返回为空")
    }

    /** 从视觉模型输出中分离「题目」「解答」与推测的「分类」 */
    fun parseVisionOutput(output: String): SolveResult {
        val question = Regex("题目[:：]\\s*(.+)").find(output)?.groupValues?.get(1)?.trim()
        // 容错：模型可能写成"科目/类别/分类名/所属科目"等
        val category = Regex("(?:分类|科目|类别|分类名|所属科目)[:：]\\s*(.+)").find(output)?.groupValues?.get(1)?.trim()
        // 知识点：按 、 / ， / 逗号 拆分，最多 5 个
        val tags = Regex("(?:知识点|考点|标签)[:：]\\s*(.+)").find(output)?.groupValues?.get(1)
            ?.split('、', '，', ',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.take(5)
            ?: emptyList()
        // 解答：取"解答："之后，去掉末尾的"分类：/知识点："整块
        var answer = Regex("解答[:：]([\\s\\S]+)").find(output)?.groupValues?.get(1)?.trim() ?: output
        answer = answer.replace(Regex("\\n*\\s*(?:分类|科目|类别|分类名|所属科目|知识点|考点|标签)[:：].*$", RegexOption.DOT_MATCHES_ALL), "").trim()
        return SolveResult(
            question = question?.takeIf { it.isNotBlank() } ?: output.take(80),
            answer = answer,
            category = category?.takeIf { it.isNotBlank() },
            tags = tags
        )
    }

    private fun JsonElement.asText(): String = (this as? JsonPrimitive)?.content ?: toString()

    /**
     * 让视觉模型标出**题目区域**（相对坐标 0~1）。
     * 超时（默认 2.6s）、解析失败或坐标不合法都返回 null —— 调用方据此回退到本地检测结果 / 默认框。
     */
    suspend fun detectQuestionBoxesAi(
        imageBytes: ByteArray,
        timeoutMs: Long = 500
    ): List<ImageAutoCrop.NormRect>? {
        requireKey()
        val b64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val prompt = "你是图像分析助手。找出图中**每一道题目**的正文区域（包含题干文字与题图、包含选项；" +
            "不要包含页眉页脚、\"提交\"按钮、手写笔记或已作答内容）。" +
            "只输出 JSON，不要任何解释：{\"boxes\":[{\"x\":0.05,\"y\":0.35,\"w\":0.9,\"h\":0.12}]}。" +
            "坐标是相对整张图的 0~1 比例值：x/y 为左上角，w/h 为宽高。"
        val parts = buildJsonArray {
            addJsonObject {
                put("type", "text")
                put("text", prompt)
            }
            addJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", "data:image/jpeg;base64,$b64") }
            }
        }
        val resp = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            ApiClient.deepSeek.chat(
                DeepSeekRequest(
                    model = MODEL_VISION,
                    messages = listOf(DeepSeekMessage("user", parts)),
                    maxTokens = 300,
                    temperature = 0.0
                )
            )
        } ?: return null
        lastUsage = resp.usage
        val txt = resp.choices.firstOrNull()?.message?.content?.asText() ?: return null
        val s = txt.indexOf('{')
        val e = txt.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return runCatching {
            val root = Json.parseToJsonElement(txt.substring(s, e + 1)).jsonObject
            val arr = root["boxes"]?.jsonArray ?: return null
            arr.mapNotNull { el ->
                val o = el.jsonObject
                val x = o["x"]?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null
                val y = o["y"]?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null
                val w = o["w"]?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null
                val h = o["h"]?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null
                if (w <= 0.02f || h <= 0.02f) return@mapNotNull null
                ImageAutoCrop.NormRect(
                    x.coerceIn(0f, 0.99f),
                    y.coerceIn(0f, 0.99f),
                    w.coerceIn(0.03f, 1f),
                    h.coerceIn(0.03f, 1f)
                )
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** 同类题结果：AI 出的题目 + 完整解答 */
    data class SimilarResult(val question: String, val answer: String)

    /** 出题提示词（流式/非流式共用） */
    private fun similarPrompt(question: Question): String =
        "请根据下面这道题，出一道同知识点、同类题型、难度相近的“近似题”，并自行给出完整、正确的解答（务必确保题目可解）。" +
            "原题：${question.text}\n\n请先输出一行“题目：<新题>”，再输出“解答：<完整步骤与结论>”。数学公式用 LaTeX。"

    /** 解析出题结果（流式/非流式共用） */
    private fun parseSimilar(out: String): SimilarResult {
        val q = Regex("题目[:：]\\s*(.+)").find(out)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() } ?: out.take(120)
        val a = Regex("解答[:：]([\\s\\S]+)").find(out)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() } ?: out
        return SimilarResult(q, a)
    }

    /**
     * 只取「题目：」之后、「解答：」之前的正文。
     * 流式出题时用它决定界面显示什么——**答案部分绝不显示**（仍由页面点「查看答案」再展开）。
     */
    fun similarQuestionPortion(acc: String): String {
        val i = listOf(acc.indexOf("解答："), acc.indexOf("解答:")).filter { it >= 0 }.minOrNull() ?: acc.length
        val head = acc.substring(0, i)
        val qi = listOf(head.indexOf("题目："), head.indexOf("题目:")).filter { it >= 0 }.minOrNull()
        return (if (qi != null) head.substring(qi + 3) else head).trim()
    }

    /** 根据错题出一道同知识点、同类题型、难度相近的近似题，并自备完整解答（确保可解） */
    // [已停用 2026-09-16] 非流式旧实现，保留备查（当前全部走流式 streamCall）
//     suspend fun generateSimilarQuestion(question: Question, lang: AiLang = AiLang.DEFAULT): SimilarResult {
//         requireKey()
//         val msgs = listOf(
//             systemMessage(lang),
//             DeepSeekMessage("user", JsonPrimitive(similarPrompt(question)))
//         )
//         val resp = ApiClient.deepSeek.chat(
//             DeepSeekRequest(model = MODEL_TEXT, messages = msgs, maxTokens = 4096)
//         )
//         lastUsage = resp.usage
//         val out = resp.choices.firstOrNull()?.message?.content?.asText()
//             ?: throw IllegalStateException("出题返回为空")
//         return parseSimilar(out)
//     }

    /**
     * 流式出题：整体文本边生成边回调（调用方用 [similarQuestionPortion] 只显示题目部分，答案不外显）。
     */
    suspend fun generateSimilarQuestionStream(
        question: Question,
        lang: AiLang = AiLang.DEFAULT,
        onDelta: suspend (String) -> Unit
    ): SimilarResult {
        requireKey()
        val msgs = listOf(
            systemMessage(lang),
            DeepSeekMessage("user", JsonPrimitive(similarPrompt(question)))
        )
        val out = chatStream(MODEL_TEXT, msgs, onDelta)
        return parseSimilar(out)
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
