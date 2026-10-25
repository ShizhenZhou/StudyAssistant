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
    const val MODEL_TEXT = "deepseek-flash"   // 官方仅 flash / v4-pro；flash 默认开启思考模式并支持图像

    fun requireKey() {
        if (KeyManager.getApiKey().isBlank()) {
            throw IllegalStateException("尚未配置 DeepSeek API Key\n请到首页 ⚙️ 设置 里填写")
        }
    }

    data class SolveResult(val question: String, val answer: String, val category: String? = null, val tags: List<String> = emptyList())

    /**
     * 搜题指令（单图 / 多图 / 图文提问带图共用）。
     *
     * ★ 2026-10-13 改写（用户要求「看看有没有冗余或自相矛盾」）：
     *   · 去掉自相矛盾：原来写「**每行开头都必须带对应序号**」又说「④ 解答：<…可以多行>」→
     *     模型给解答的每一行都加 ④（项目里 `stripLeakedSectionMarks` 就是在给这句擦屁股）。现在明确
     *     仅禁止模板圈码泄漏；题目本身的小题编号必须保留。
     *   · 空列表时不再出现悬空句（原来固定拼「若题目属于其中某一个…」，列表为空时这句没有指代对象）。
     *   · 借用了 `classifyQuestion` 里那句治「重复分类」的措辞（沾边就必须选、宁可粗一点）。
     *   · 去掉文案里的 `**`：那等于在教模型输出 markdown 加粗（也顺带减少误加粗）。
     */
    /**
     * 「已知分类 / 已知知识点」两行（空列表时给**不悬空**的说法）。
     * solvePrompt 与文字解题共用，避免两份拷贝再漂移。
     */
    private fun solveKnownHint(categories: List<String>, tags: List<String>): String {
        val catLine = if (categories.isEmpty())
            "已知分类：暂无。请给出一个 2~6 字的常见学科名作为「② 分类」。"
        else
            "已知分类：${categories.joinToString("、")}。" +
                "只要有一个与本题沾边（近义、上下位、范围更大或更小都算）就必须原样选它，宁可粗一点也不要新建；" +
                "全都不沾边，才写一个新的简短科目名。"
        val tagLine = if (tags.isEmpty())
            "已知知识点：暂无。请给出新知识点，最多 5 个、用「、」分隔。"
        else
            "已知知识点：${tags.joinToString("、")}。能对应上的优先原样沿用；都不符合才给新的，最多 5 个、用「、」分隔。"
        return catLine + "\n" + tagLine
    }

    /** 解题/批改共用；模板节号不应影响原题编号，也不限制正常的推导步骤编号。 */
    private fun answerLayoutInstruction(): String =
        "保留题目原有的所有小题编号（如（1）（2）、(a)(b)、①②），题干转写、结论和详细过程均按原编号逐一对应，不合并或漏答。" +
            "模板的①②③④只标记题目、分类、知识点、解答这几个字段，不得重复加在正文每一行；详细推导可以使用步骤编号。\n" +
            "完整解答先写「结论」段，按原小题编号简要列出结果；随后空一行，单独输出一行 Markdown 分隔线 ---，" +
            "再空一行，写「详细过程」段，按原小题编号逐题推导。批改时结论说明正误和错误位置，详细过程给出正确解法与讲解。" +
            "分隔线不要放进公式或代码块；标题使用回答语言。简短追问、概念解释或只问某一步时直接回答，不强制重复完整结构。\n"

    fun solvePrompt(categories: List<String>, tags: List<String>): String {
        return "请识别图片中的题目并给出详细的分步解答。所有公式用 LaTeX（行内 $...$、独立成行 $$...$$），" +
            "上下标、分式、积分、矩阵、希腊字母要写对。\n" +
            "按下面模板输出，每项只写一次、各占一行；只有「④ 解答」可以多行，不要在解答中重复抄题干：\n" +
            "① 题目：<逐字照录题干，不改写、不省略>\n" +
            "② 分类：<所属科目>\n" +
            "③ 知识点：<知识点1、知识点2、知识点3>\n" +
            "④ 解答：<先给结论，再写详细步骤>\n" +
            answerLayoutInstruction() + solveKnownHint(categories, tags)
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
        // ★ 2026-10-13：与 solvePrompt 统一口径（去 `**`、消掉"每行都必须带序号"与"解答可多行"的矛盾、
        //   空列表不再出现悬空句）。这里保留「① 题目：$text」是因为解析链路要靠它取题干。
        return DeepSeekMessage(
            "user",
            JsonPrimitive(
                "请解答下面这道题目并给出详细的分步解答。所有公式用 LaTeX（行内 $...$、独立成行 $$...$$）。\n" +
                    "按下面模板输出，每项只写一次、各占一行；只有「④ 解答」可以多行：\n" +
                    "① 题目：$text\n" +
                    "② 分类：<所属科目>\n" +
                    "③ 知识点：<知识点1、知识点2、知识点3>\n" +
                    "④ 解答：<先给结论，再写详细步骤>\n" +
                    answerLayoutInstruction() + solveKnownHint(categories, tags)
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
        images: List<ByteArray> = emptyList(),
        needCategory: Boolean = true,
        needTags: Boolean = true,
        timeoutMs: Long = 8000,
    ): Pair<String?, List<String>> {
        if (question.isBlank() && images.isEmpty()) return null to emptyList()
        return runCatching {
            requireKey()
            val prompt = buildString {
                append("你是学科分类助手。判断下面这道理工科题目属于哪个科目、涉及哪些知识点。\n")
                append("题目：").append(question.take(600)).append("\n")
                append("已知分类（必须优先从中选一个并原样输出）：")
                append(if (categories.isEmpty()) "（暂无）" else categories.joinToString("、"))
                append("。只要有一个已知分类与本题沾边（近义、上下位、范围更大或更小都算）就必须选它，宁可粗一点也不要新建；只有全都不沾边，才输出 新：<简短科目名>\n")
                append("已知知识点标签（能对应上的优先使用，不要新造）：")
                append(if (tags.isEmpty()) "（暂无）" else tags.joinToString("、")).append("\n")
                append("只输出 JSON，不要任何解释：{\"分类\":\"...\",\"知识点\":[\"...\",\"...\"]}（知识点最多 5 个）")
            }
            // ★ 有图就把图一起发给**视觉模型**（文字+图片一并考虑，避免只看文字漏判科目）
            val msg = if (images.isNotEmpty()) {
                userMessageWithImages(
                    prompt + "\n（请结合我附上的图片一起判断）",
                    images.take(3)
                )
            } else {
                DeepSeekMessage("user", JsonPrimitive(prompt))
            }
            val resp = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                ApiClient.deepSeek.chat(
                    DeepSeekRequest(
                        model = if (images.isNotEmpty()) MODEL_VISION else MODEL_TEXT,
                        messages = listOf(DeepSeekMessage("system", JsonPrimitive("")), msg).drop(1),
                        maxTokens = 2048,
                        thinking = ThinkingOption("disabled"),   // 分类是简单判定，别花思考 token
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
            // ★ 键名同义容错：模型可能写 分类/科目/类别/学科，知识点/考点/标签/tags
            fun pick(vararg keys: String) = keys.firstNotNullOfOrNull { k -> root[k] }
            val catEl = pick("分类", "科目", "类别", "学科", "subject", "category")
            val tagEl = pick("知识点", "考点", "标签", "tags", "knowledge")
            val cat = catEl?.jsonPrimitive?.contentOrNull?.trim()
                ?.removePrefix("新：")?.removePrefix("新:")?.trim()
                ?.takeIf { it.isNotBlank() }
            val tgs = (tagEl as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                ?.filter { it.isNotBlank() }?.take(5)
                ?: tagEl?.jsonPrimitive?.contentOrNull
                    ?.split('、', '，', ',')?.map { it.trim() }?.filter { it.isNotBlank() }?.take(5)
                ?: emptyList()
            (if (needCategory) cat else null) to (if (needTags) tgs else emptyList())
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

    /**
     * 同类题出题用的"人设"系统消息。
     * @param customPrompt 用户自定义要求（设置 → AI 管理）；2026-10-13 起同类题链路也会带上它
     *   （此前同类题只发人设、既没有自定义要求也没有分类概念）。
     */
    fun systemMessage(lang: AiLang = AiLang.DEFAULT, customPrompt: String = ""): DeepSeekMessage = DeepSeekMessage(
        "system",
        JsonPrimitive(
            "你是一名理工科大学解题助手。请给出清晰、分步的解答过程，包含必要的公式推导，" +
                "数学公式请用 LaTeX 书写（$...$ 或 $$...$$）。" +
                "\n" + answerLayoutInstruction() +
                "\n" + languageInstruction(lang) +
                if (customPrompt.isBlank()) "" else "\n\n用户自定义要求（请务必遵守）：\n$customPrompt"
        )
    )

    /**
     * 系统消息：回答语言（+ 可选的用户自定义要求）。
     *
     * ★ 2026-10-13 修复：用户的「自定义要求」（设置 → AI 管理）此前**定义了 customPromptMessage() 却从来没被调用**，
     *   等于用户填的要求根本没发给模型。现在把它并进这一条 system 消息里
     *   （合成一条而不是发两条 system：更保险，避免有的网关只认第一条 system）。
     *
     * @param lang 回答语言
     * @param customPrompt 用户自定义要求原文（空串 = 没设置）
     */
    fun languageSystemMessage(lang: AiLang, customPrompt: String = ""): DeepSeekMessage {
        val langLine = answerLayoutInstruction() + languageInstruction(lang)
        val text = if (customPrompt.isBlank()) langLine
        else "$langLine\n\n用户自定义要求（请务必遵守，与上面的固定格式冲突时以本条为准）：\n$customPrompt"
        return DeepSeekMessage("system", JsonPrimitive(text))
    }

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
     * - 忽略心跳/空行，读取 finish_reason；非正常结束保留部分内容并由用户决定续写。
     * - 最后一片带 `usage` 时写入 [lastUsage]，保证「API 管理」用量统计不断档。
     *
     * @param onDelta 每收到一段增量调用一次（调用方负责节流，避免频繁重渲染）
     */
    suspend fun chatStream(
        model: String,
        messages: List<DeepSeekMessage>,
        onDelta: suspend (String) -> Unit,
        /** 思考模式增量回调（官方 reasoning_content；思考模式关闭时不会触发） */
        onReasoning: (suspend (String) -> Unit)? = null,
        /** 是否开启思考模式（设置 → AI 配置 → **解题模式**） */
        thinkingEnabled: Boolean = true
    ): String {
        requireKey()
        val body = ApiClient.deepSeekStream.chatStream(
            DeepSeekRequest(
                model = model,
                messages = messages,
                maxTokens = 4096,
                stream = true,
                streamOptions = StreamOptions(includeUsage = true),
                // 思考模式：仅"关闭"时显式传 disabled（开启用服务端默认，避免多传字段被拒 422）
                thinking = if (!thinkingEnabled) ThinkingOption("disabled") else null
            )
        )
        val sb = StringBuilder()
        var usage: DeepSeekUsage? = null
        val completion = StreamCompletion()
        lastUsage = null
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
                    val chunk = completion.accept(payload)
                    if (completion.done) break
                    if (chunk == null) continue
                    chunk.usage?.let { usage = it }
                    val d = chunk.choices.firstOrNull()?.delta
                    // ★ 推理模型的"思考流"：单独回调，且**不并入**正文（避免污染答案与解析）
                    d?.reasoningContent?.takeIf { it.isNotEmpty() }?.let { r -> onReasoning?.invoke(r) }
                    val delta = d?.content
                    if (!delta.isNullOrEmpty()) {
                        sb.append(delta)
                        onDelta(delta)
                    }
                }
            }
        } finally {
            activeStreamBody = null
            lastUsage = usage
        }
        completion.requireComplete()
        return sb.toString().ifBlank { throw IllegalStateException("DeepSeek 返回为空") }
    }

    /**
     * 批改提示词（流式/非流式共用）。
     * ⚠️ 与拍题统一成**同一套「序号 + 标签」格式**，原因（2026-09-25 踩坑）：
     *   旧文案写「请批改：①判断作答是否正确；②若不正确…；③给出正确的解题过程…」——
     *   这里的 ①②③ 是**给模型的指令编号**，模型会把它当成**段落标签照抄进答案**，
     *   于是答案里出现「① …② …③ …」这种莫名其妙的序号（与拍题里"④ 泄漏"同一类问题）；
     *   而且批改没有「解答：」标记 → `parseVisionOutput` 取不到正文，只能整段返回，清洗也不一致。
     *   现在统一：① 分类 / ② 知识点 / ③ 解答（正文里再写「结论：」「讲解：」），
     *   指令本身不再编号，避免被照抄。
     */
    private fun gradePrompt(answerBytes: ByteArray?, lang: AiLang, categories: List<String> = emptyList(), tags: List<String> = emptyList()): String {
        // 带手写作答（真批改）：不要求回显题干，直接 ① 分类 / ② 知识点 / ③ 解答
        val fmtWithAnswer =
            "① 分类：<所属科目>\n" +
            "② 知识点：<知识点1、知识点2、知识点3>\n" +
            "③ 解答：<结论、分隔线、详细过程，按下方排版要求输出>"
        // 只拍了题目（无作答）：与拍题一致，含 ① 题目
        val fmtNoAnswer =
            "① 题目：<忠实转写的题干>\n" +
            "② 分类：<所属科目>\n" +
            "③ 知识点：<知识点1、知识点2、知识点3>\n" +
            "④ 解答：<详细步骤与结论，可多行>"
        return (if (answerBytes != null)
            "你是一名批改老师。图片中是{题目}和{学生的手写作答}。" +
                "请判断作答是否正确；若不正确，指出错在哪一步、为什么错；并给出正确的解题过程与针对性讲解。用 LaTeX 写公式。"
        else
            "请识别图片中的题目，并给出完整、分步的解答过程，用 LaTeX 写公式。") +
            // ★ 2026-10-13：原句「每行开头都必须带对应序号」与「解答可多行」自相矛盾（模型会给解答每一行都加序号，
            //   项目里的 stripLeakedSectionMarks 就是给这句擦屁股的），且去掉了文案里的 `**`。
            "\n按下面模板输出，每项只写一次、各占一行，开头带对应序号；只有「解答」可以多行：\n" +
            (if (answerBytes != null) fmtWithAnswer else fmtNoAnswer) + "\n" +
            answerLayoutInstruction() + solveKnownHint(categories, tags) + "\n" +
            languageInstruction(lang)
    }

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
                put("text", gradePrompt(answerBytes, lang, categories, tags))
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

    /**
     * 批改的 user 消息（**纯文字题干** + 手写作答图）。
     * B6 重做模式用：复习的题可能没有原图（直接提问/图文提问存的题），
     * 这时把题干当文字给出，图片只放"我的作答"。
     */
    fun gradeTextUserMessage(
        questionText: String,
        answerBytes: ByteArray,
        lang: AiLang = AiLang.DEFAULT,
        categories: List<String> = emptyList(),
        tags: List<String> = emptyList()
    ): DeepSeekMessage {
        val parts = buildJsonArray {
            addJsonObject {
                put("type", "text")
                put(
                    "text",
                    "题目：$questionText\n\n图片中是学生的手写作答。\n" +
                        gradePrompt(answerBytes, lang, categories, tags)
                )
            }
            addJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", "data:image/jpeg;base64," + Base64.encodeToString(answerBytes, Base64.NO_WRAP)) }
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

    /** 行首的序号 / 项目符号前缀（模型常写成「① 题目：…」「2. 分类：…」「- 知识点：…」） */
    private const val META_LEAD = "[\\s\\u2460-\\u2473\\d.、,，)）\\-*•·]*"
    /**
     * 元信息「标签 + 冒号」本体，容忍「核心知识点/难点」这类带修饰语、用 / 、 并列的写法。
     */
    private const val META_LABELS =
        "(?:核心|主要|本题|考察|考查)?\\s*" +
        "(?:题目|分类|科目|类别|分类名|所属科目|知识点|考点|标签|难点)" +
        "(?:[/／、和及](?:知识点|考点|标签|难点))*\\s*[:：]"

    /**
     * 元信息行：题目/分类/知识点…（模型偶尔把题干整行抄回答案里）。
     * ⚠️ 必须容忍**序号前缀**：提示词要求写「① 题目：…② 分类：…」，但模型经常只给第一项加序号
     * （`① 题目：…`）、其余不加 —— 只认"行首即标签"会让带序号的题干行逃过剔除
     * （用户 2026-09-25 截图实测：答案区开头又出现「① 题目：介绍一下高斯公式…」）。
     */
    private val META_LINE = Regex("^$META_LEAD$META_LABELS")

    /** 只匹配"开头那一个"标签（用于模型把题干与解答写在同一行/同一段时，去掉标签但保住正文） */
    private val META_PREFIX = Regex("^$META_LEAD$META_LABELS\\s*")

    /** 圈码①-⑳ 出现在行首 */
    private val CIRC_HEAD = Regex("^\\s*([\\u2460-\\u2473])")
    /** 圈码后面紧跟数字分点（如 `④ 1. 条件：…`）→ 那个圈码一定是泄漏的节标签 */
    private val CIRC_THEN_NUM = Regex("^\\s*[\\u2460-\\u2473]\\s*(?=\\d+\\s*[.、)）])")

    /** Only strip an explicitly identified answer-section mark before a separate numeric step.
     * Repeated circled subquestion numbers in conclusions/derivations are legitimate content.
     */
    private fun stripLeakedSectionMarks(text: String, sectionMark: String?): String {
        if (sectionMark == null) return text
        return text.lines().joinToString("\n") { line ->
            val mark = CIRC_HEAD.find(line)?.groupValues?.get(1)
            if (mark == sectionMark && CIRC_THEN_NUM.containsMatchIn(line))
                CIRC_HEAD.replaceFirst(line, "").trimStart() else line
        }
    }

    private val ANSWER_SECTION_MARK = Regex("(?m)^\\s*([\\u2460-\\u2473])\\s*解答[:：]")

    /**
     * 清洗回答里**回显的元信息行**（"答案里又出现题目"的根因）与**泄漏的节序号**。
     * 只按行首标记剔整行 / 剔行首圈码，不做 `.*$` 式截断，因此对追问/口语化回答也安全。
     */
    fun stripMetaLines(output: String, sectionMark: String? = null): String {
        val kept = output.replace(Regex("<思考>[\\s\\S]*?</思考>"), "")
            .lines()
            .filterNot { META_LINE.containsMatchIn(it) }
            .joinToString("\n")
            .trim()
        return stripLeakedSectionMarks(kept, sectionMark ?: ANSWER_SECTION_MARK.find(output)?.groupValues?.get(1))
    }

    /** 从视觉模型输出中分离「题目」「解答」与推测的「分类」 */
    fun parseVisionOutput(output: String): SolveResult {
        // 路线 A：若正文里混进了 <思考> 块，先剥掉（避免污染题目/解答）
        val output = output.replace(Regex("<思考>[\\s\\S]*?</思考>"), "").trim()
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
        // 解答：取"解答："之后
        var answer = Regex("解答[:：]([\\s\\S]+)").find(output)?.groupValues?.get(1)?.trim() ?: output
        // 🚨 这里原本还有一条 `\\n*\\s*(?:分类|科目|…|知识点|考点|标签)[:：].*$`（DOT_MATCHES_ALL）的
        //    "砍掉末尾元信息整块"，**已删除**，它是「答案生成完就消失」和「答案里又出现题目」的共同根因：
        //    · 模型**没写「解答：」**时（很常见），上面一行会让 answer = 整段回复（含开头的"题目：/分类：/知识点："），
        //      于是那条正则从"分类："一路 DOT_MATCHES_ALL 砍到文末 → **把真正的解答整段删掉** → 气泡变空 =
        //      用户看到的"正式答案莫名其妙消失"；
        //    · 反之若没被砍空，回显的"题目：…"就会留在答案里 = 之前的"答案里又出现题目"。
        //    现在统一改成**按行**剔元信息（stripMetaLines），只删那些标签行本身，绝不截断正文。
        answer = stripMetaLines(answer, ANSWER_SECTION_MARK.find(output)?.groupValues?.get(1))
        // 兜底①：整段都被当成元信息行剔掉了（模型把题干与解答写在同一行/同一段）→
        //   只去掉开头那一个「① 题目：」标签，**保住正文**（用户截图里的高斯公式那题就是这种写法）
        if (answer.isBlank()) answer = output.trim().replace(META_PREFIX, "").trim()
        // 兜底②：万一连正文都取不到，退回原始输出，绝不显示空气泡
        if (answer.isBlank()) answer = output.trim()
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
        val prompt = "你是图像分析助手。找出图中每一道题目的正文区域（包含题干文字与题图、包含选项；" +
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
