package com.zsz.studyassistant.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** 一条对话消息（用于气泡渲染）；images 为 base64 编码的附图列表 */
@kotlinx.serialization.Serializable
data class ChatMsg(val role: String, val content: String, val images: List<String> = emptyList(), val thinking: String? = null, val thinkingOpen: Boolean = false, val thinkingDone: Boolean = false, val quotedText: String? = null, val messageKey: String? = null, val quoteAnchor: com.zsz.studyassistant.QuoteAnchor? = null,
    val sources: List<com.zsz.studyassistant.data.AiSource> = emptyList(), val sourcesLabel: String = "",
    val documents: List<com.zsz.studyassistant.data.ConversationDocument> = emptyList(),
    val showImagesInline: Boolean = false) {
    internal fun sourceText(scope: String): String? = when (scope) {
        "body" -> content
        "thinking" -> thinking
        "quote" -> quotedText
        else -> null
    }
    private val hashes by lazy(LazyThreadSafetyMode.NONE) {
        listOf("body", "thinking", "quote").associateWith { scope ->
            sourceText(scope)?.let { text -> java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } }
        }
    }
    internal fun sourceHash(scope: String): String? = hashes[scope]
}

/**
 * 对话正文 WebView：占满给定区域，内部上下滚动，滚动条常驻、手势不被父级拦截。
 * 消息以聊天气泡显示：assistant=灰底，user=浅绿底。KaTeX 排版。
 * 点击气泡里的图片 → 全屏放大（支持双指缩放/拖动，返回键或 ✕ 关闭）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ConversationWebView(
    messages: List<ChatMsg>,
    modifier: Modifier = Modifier,
    /** 每次自增 = 请求「回到顶部」（由界面上的 ↑ 按钮触发；WebView 自己管滚动，只能走 JS） */
    scrollTopSignal: Int = 0,
    /** 每次自增 = 请求「滚动到底部」（在顶部时按钮变为 ↓） */
    scrollBottomSignal: Int = 0,
    returnQuoteSignal: Int = 0,
    clearQuoteHighlightSignal: Int = 0,
    onQuoteReturnAvailable: (Boolean) -> Unit = {},
    /** 每次自增 = 立刻重置到顶部（换题/换会话时用，无动画） */
    resetScrollSignal: Int = 0,
    /** 每次自增 = 只关闭「自动跟随」，**不改变滚动位置**（展开答案时保持画面不动） */
    noFollowSignal: Int = 0,
    /** 会话是否已在顶部（用于把按钮在 ↑ / ↓ 之间切换） */
    onAtTopChange: (Boolean) -> Unit = {},
    /** 会话内容是否超过一屏（false → 界面隐藏 ↑/↓ 按钮） */
    onScrollableChange: (Boolean) -> Unit = {},
    /** 点击气泡末尾蓝色「继续生成」时回调 */
    onContinue: () -> Unit = {},
    /** 长按某条消息气泡时回调（参数为界面消息下标）→ 用于进入多选界面 */
    onLongPressMessage: (Int) -> Unit = {},
    /** Available only where a follow-up composer can receive a selection. */
    onQuoteText: ((QuoteSelection) -> Unit)? = null,
    onBookmarkText: ((QuoteSelection) -> Unit)? = null,
    onFindPdfText: ((QuoteSelection) -> Unit)? = null,
    bookmarkJump: com.zsz.studyassistant.ConversationBookmark? = null,
    bookmarkJumpSignal: Int = 0,
    notebookMatch: AppNav.NotebookMatch? = null,
    notebookMatchText: String? = null,
    notebookMatchIndex: Int = -1,
    onNotebookMatch: (index: Int, count: Int) -> Unit = { _, _ -> },
    /** 是否处于多选态：气泡右上角显示选择圆圈，选中的显示红框 */
    selectionMode: Boolean = false,
    /** 多选态下已选中的消息下标 */
    selectedIndices: Set<Int> = emptySet(),
    /** 多选时**不可选中/不可删除**的下标（题干与 AI 首条回复：答案 / 批改结果） */
    lockedIndices: Set<Int> = emptySet(),
    /** 多选态下点击某条气泡 → 切换选中 */
    onToggleSelect: (Int) -> Unit = {},
    /** 当前是否深色主题：气泡/正文/思考块配色由网页内 CSS 变量切换（见 conversation_render.html） */
    dark: Boolean = false,
    /** 界面字号档位（C14）：只缩放网页里的文字（1 = 标准），与 App 字号设置保持一致 */
    fontScale: Float = 1f,
    /**
     * 思考区展开/折叠的**全局记忆**（用户 2026-10-07 要求）：
     * `"auto"` = 没手动切过（生成中展开、历史折叠，保持原观感）；`"open"` / `"closed"` = 按用户最后一次操作显示，
     * **跨题目生效**（见 `data/CapturePrefs.thinkExpandPref`）。
     */
    thinkPref: String = "auto",
    /** 用户在网页里点了「展开 / 折叠思考区」→ 回调最新状态（true = 展开），由界面写进偏好 */
    onThinkToggled: (Boolean) -> Unit = {}
) {
    // 深色与否同样必须取「最新值」：onPageFinished 是在 factory 里注册的回调，
    // 只会捕获首次组合时的那份 dark（与上面回调闭包是同一类坑）。
    val currentDark by rememberUpdatedState(dark)
    val currentScale by rememberUpdatedState(fontScale)
    val currentThinkPref by rememberUpdatedState(thinkPref)
    val currentOnThinkToggled by rememberUpdatedState(onThinkToggled)
    // ⚠️ JS 桥接对象只在 AndroidView 的 factory 里创建一次，会永久捕获当时的 lambda。
    // 若直接调用 onXxx()，用的就是**首次组合**时的那份闭包（其中的 selectedIndices/lockedIndices 等值永远是旧的），
    // 曾导致「受保护消息（题干/AI 首条）长按仍被算作可选中 → 已选 1 条」。
    // 因此所有回调都必须通过 rememberUpdatedState 取最新值。
    val currentMessages by rememberUpdatedState(messages)
    val currentOnContinue by rememberUpdatedState(onContinue)
    val currentOnLongPress by rememberUpdatedState(onLongPressMessage)
    val currentOnQuote by rememberUpdatedState(onQuoteText)
    val currentOnBookmark by rememberUpdatedState(onBookmarkText)
    val currentMatch by rememberUpdatedState(notebookMatch)
    val currentMatchIndex by rememberUpdatedState(notebookMatchIndex)
    val currentOnMatch by rememberUpdatedState(onNotebookMatch)
    val currentOnFindPdf by rememberUpdatedState(onFindPdfText)
    val currentOnQuoteReturn by rememberUpdatedState(onQuoteReturnAvailable)
    val currentOnToggleSelect by rememberUpdatedState(onToggleSelect)
    val currentOnAtTopChange by rememberUpdatedState(onAtTopChange)
    val currentOnScrollableChange by rememberUpdatedState(onScrollableChange)
    val currentSelectionMode by rememberUpdatedState(selectionMode)
    var rendererGeneration by remember { mutableStateOf(0) }
    var rendererFailed by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    val imageStore = remember { ConversationImageStore() }
    val payload = remember { ConversationPayload(imageStore) }
    var imageGallery by remember { mutableStateOf<ImageGallery?>(null) }
    var diagramOpen by remember { mutableStateOf(false) }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    val bridgeHandler = remember { Handler(Looper.getMainLooper()) }

    /** 多选状态 → JS 载荷（选择/锁定变化也要触发重渲染，故与消息一起进入去重 key） */
    fun selectionJson(): String =
        if (selectionMode) {
            "{\"on\":true,\"selected\":[" + selectedIndices.sorted().joinToString(",") +
                "],\"locked\":[" + lockedIndices.sorted().joinToString(",") + "]}"
        } else {
            "{\"on\":false,\"selected\":[],\"locked\":[]}"
        }

    // 思考块的文案按当前界面语言注入 WebView（繁體由 Strings 内部 s2t 自动转换）
    // ⚠️ 必须进「去重 key」：否则切换界面语言时 JSON 没变 → 不重渲染 → 思考块文案还是旧语言
    val s = LocalStrings.current
    val mediaLabels = mediaViewerLabels()
    val currentQuoteMissing by rememberUpdatedState(s["solve.quoteUnavailable"])
    fun thinkLabelsJson(): String {
        fun esc(t: String) = t
            .replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r")
        return "{\"title\":\"" + esc(s["think.title"]) + "\"" +
            ",\"thinking\":\"" + esc(s["think.state.thinking"]) + "\"" +
            ",\"collapse\":\"" + esc(s["think.state.collapse"]) + "\"" +
            ",\"expand\":\"" + esc(s["think.state.expand"]) + "\"" +
            ",\"fold\":\"" + esc(s["think.fold"]) + "\"" +
            ",\"unfold\":\"" + esc(s["think.unfold"]) + "\"" +
            ",\"viewImages\":\"" + esc(s["conversation.viewImages"]) + "\"" +
            ",\"mediaClose\":\"" + esc(mediaLabels.close) + "\"" +
            ",\"mediaZoomIn\":\"" + esc(mediaLabels.zoomIn) + "\"" +
            ",\"mediaZoomOut\":\"" + esc(mediaLabels.zoomOut) + "\"" +
            ",\"mediaPreview\":\"" + esc(mediaLabels.preview) + "\"}"
    }

    BackHandler(enabled = diagramOpen) {
        webRef?.evaluateJavascript("window.dshCloseDiagram && window.dshCloseDiagram();", null)
        diagramOpen = false
    }

    LaunchedEffect(selectionMode) {
        if (selectionMode) {
            imageGallery = null
            diagramOpen = false
            webRef?.evaluateJavascript("window.dshCloseDiagram && window.dshCloseDiagram();", null)
        }
    }

    // 回到顶部：同时暂停流式跟随，避免又被自动拉回底部
    LaunchedEffect(scrollTopSignal) {
        if (scrollTopSignal > 0) {
            webRef?.evaluateJavascript("window.dshScrollToTop && window.dshScrollToTop();", null)
        }
    }

    // 滚到底部（在顶部时按钮显示为 ↓）
    LaunchedEffect(scrollBottomSignal) {
        if (scrollBottomSignal > 0) {
            webRef?.evaluateJavascript("window.dshScrollToBottom && window.dshScrollToBottom();", null)
        }
    }

    LaunchedEffect(clearQuoteHighlightSignal) {
        if (clearQuoteHighlightSignal > 0) webRef?.evaluateJavascript("window.dshClearQuoteJump && window.dshClearQuoteJump();", null)
    }

    LaunchedEffect(returnQuoteSignal) {
        if (returnQuoteSignal > 0) webRef?.evaluateJavascript("window.dshReturnToQuote && window.dshReturnToQuote();", null)
    }

    LaunchedEffect(bookmarkJumpSignal) {
        if (bookmarkJumpSignal > 0) bookmarkJump?.let { bookmark ->
            val a = bookmark.anchor
            val payload = JSONObject().put("text", bookmark.text).put("anchor", JSONObject()
                .put("messageKey", a.messageKey).put("scope", a.scope).put("start", a.start)
                .put("end", a.end).put("sourceHash", a.sourceHash).put("visualMath", a.visualMath))
            webRef?.evaluateJavascript("window.dshJumpBookmark && window.dshJumpBookmark($payload);", null)
        }
    }

    val notebookRequest = remember(notebookMatch, notebookMatchText, messages) {
        notebookMatch?.let { notebookSearchRequest(messages, it, notebookMatchText) }
    }
    DisposableEffect(webRef, notebookMatch) {
        val view = webRef
        onDispose {
            if (view != null && view === webRef && notebookMatch != null) {
                view.evaluateJavascript("window.dshEndNotebookSearch && dshEndNotebookSearch()", null)
            }
        }
    }
    // onPageFinished queues the initial render first. Reapply the exact hit when the WebView is recreated.
    LaunchedEffect(loaded, webRef, notebookRequest, notebookMatchIndex) {
        val view = webRef ?: return@LaunchedEffect
        if (!loaded || notebookMatch == null) return@LaunchedEffect
        view.evaluateJavascript("dshNotebookMatch($notebookRequest, $notebookMatchIndex)") { raw ->
            if (webRef === view && currentMatch == notebookMatch && currentMatchIndex == notebookMatchIndex) {
                val result = runCatching { JSONArray(raw) }.getOrNull() ?: return@evaluateJavascript
                currentOnMatch(result.getInt(0), result.getInt(1))
            }
        }
    }

    // 换题/换会话：立刻回到顶部（无动画）
    LaunchedEffect(resetScrollSignal) {
        if (resetScrollSignal > 0) {
            imageGallery = null
            diagramOpen = false
            webRef?.evaluateJavascript("window.dshCloseDiagram && window.dshCloseDiagram(); window.dshScrollReset && window.dshScrollReset();", null)
        }
    }

    // 展开答案等场景：只关闭「自动跟随」，滚动位置保持不动
    LaunchedEffect(noFollowSignal) {
        if (noFollowSignal > 0) {
            webRef?.evaluateJavascript("window.dshNoFollow && window.dshNoFollow();", null)
        }
    }

    Box(modifier) {
        if (rendererFailed) {
            androidx.compose.material3.TextButton(onClick = { rendererFailed = false; rendererGeneration++ },
                modifier = Modifier.align(Alignment.Center)) { Text(s["solve.reloadConversation"]) }
        } else androidx.compose.runtime.key(rendererGeneration) {
        AndroidView(
            factory = { ctx ->
                ResetOnFocusWebView(ctx).apply {
                    webRef = this
                    val bridgeView = this
                    quoteLabel = s["solve.quoteAction"]
                    selectionHint = s["solve.quoteSelectionHint"]
                    quoteEnabled = onQuoteText != null && !selectionMode
                    bookmarkLabel = s["bookmark.action"]
                    findPdfLabel = s["pdfSource.find"]
                    bookmarkEnabled = onBookmarkText != null && !selectionMode
                    findPdfEnabled = onFindPdfText != null && !selectionMode
                    fun dispatchSelection(idx: Int, text: String, source: String, selected: JSONObject, callback: ((QuoteSelection) -> Unit)?) {
                        // A menu callback must not quote a replaced message or a released WebView.
                        val message = currentMessages.getOrNull(idx)
                        val scope = selected.optString("scope")
                        val hash = selected.optString("sourceHash")
                        if (webRef === bridgeView && message != null && !message.messageKey.isNullOrBlank() &&
                            selected.optString("messageKey") == message.messageKey && message.content == source &&
                            message.sourceHash(scope) == hash) {
                            val start = selected.optInt("start", -1)
                            val end = selected.optInt("end", -1)
                            val anchor = message.messageKey?.takeIf { start >= 0 && end > start }?.let {
                                com.zsz.studyassistant.QuoteAnchor(it, scope, start, end, hash, selected.optBoolean("visualMath"))
                            }
                            callback?.invoke(QuoteSelection(text, anchor))
                        }
                    }
                    onQuote = { idx, text, source, selected -> dispatchSelection(idx, text, source, selected, currentOnQuote) }
                    onBookmark = { idx, text, source, selected -> dispatchSelection(idx, text, source, selected, currentOnBookmark) }
                    onFindPdf = { idx, text, source, selected -> dispatchSelection(idx, text, source, selected, currentOnFindPdf) }
                    configureConversationWebView(this)
                    setBackgroundColor(Color.TRANSPARENT)
                    isVerticalScrollBarEnabled = true
                    isScrollbarFadingEnabled = false   // 滚动条常驻
                    setOnTouchListener { _, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        false
                    }
                    // 网页里的图片点击 → 交给 Kotlin 打开全屏查看（JS 线程回调，切回主线程改状态）
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun openSource(message: Int, source: Int) {
                            bridgeHandler.post {
                                if (webRef !== bridgeView) return@post
                                val item = currentMessages.getOrNull(message)?.sources?.getOrNull(source) ?: return@post
                                val url = com.zsz.studyassistant.data.safeSourceUrl(item.url) ?: return@post
                                runCatching { bridgeView.context.startActivity(android.content.Intent(
                                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                    .addCategory(android.content.Intent.CATEGORY_BROWSABLE)) }
                            }
                        }
                        @JavascriptInterface
                        fun quoteReturnAvailable(available: Boolean) {
                            bridgeHandler.post { if (webRef === bridgeView) currentOnQuoteReturn(available) }
                        }

                        @JavascriptInterface
                        fun quoteUnavailable() {
                            bridgeHandler.post {
                                if (webRef === bridgeView) android.widget.Toast.makeText(
                                    bridgeView.context, currentQuoteMissing, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }

                        @JavascriptInterface
                        fun openImage(message: Int, index: Int, url: String) {
                            bridgeHandler.post {
                                if (webRef !== bridgeView || currentSelectionMode) return@post
                                val batch = currentMessages.getOrNull(message)?.images ?: return@post
                                // Bind the tapped opaque handle to this exact message/index, rejecting stale DOM.
                                val selected = batch.getOrNull(index) ?: return@post
                                if (selected != imageStore.original(url)) return@post
                                imageGallery = ImageGallery(batch.map { GalleryImage(base64 = it) }, index)
                            }
                        }

                        @JavascriptInterface
                        fun diagramViewerChanged(open: Boolean) {
                            bridgeHandler.post { if (webRef === bridgeView) diagramOpen = open }
                        }

                        /** 气泡末尾的蓝色「继续生成」被点击 */
                        @JavascriptInterface
                        fun continueGeneration() {
                            bridgeHandler.post { if (webRef === bridgeView) currentOnContinue() }
                        }

                        /** 长按消息气泡 → 进入原生多选（编辑）界面，并选中被长按的那条 */
                        @JavascriptInterface
                        fun longPressMessage(idx: Int) {
                            bridgeHandler.post { if (webRef === bridgeView) currentOnLongPress(idx) }
                        }

                        /** 多选态下点击气泡 → 切换选中 */
                        @JavascriptInterface
                        fun toggleMessage(idx: Int) {
                            bridgeHandler.post { if (webRef === bridgeView) currentOnToggleSelect(idx) }
                        }

                        /** 用户点了「展开 / 折叠思考区」→ 记进全局偏好（所有题目共用） */
                        @JavascriptInterface
                        fun thinkToggled(open: Boolean) {
                            bridgeHandler.post { if (webRef === bridgeView) currentOnThinkToggled(open) }
                        }

                        /** 网页上报「是否已滚动到顶部」→ 按钮在 ↑ / ↓ 之间切换 */
                        @JavascriptInterface
                        fun onAtTop(atTop: Boolean) {
                            bridgeHandler.post { if (webRef === bridgeView) currentOnAtTopChange(atTop) }
                        }

                        /** 网页上报「内容是否超过一屏」→ false 时界面隐藏 ↑/↓ 按钮 */
                        @JavascriptInterface
                        fun onScrollable(scrollable: Boolean) {
                            bridgeHandler.post { if (webRef === bridgeView) currentOnScrollableChange(scrollable) }
                        }

                        @JavascriptInterface
                        fun onHeightChange(h: Int) {
                            // 占位：网页会尝试回调高度，这里无需处理
                        }
                    }, "Android")
                    webViewClient = object : ConversationAssetClient(imageStore) {
                        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                            if (webRef === view) {
                                webRef = null
                                diagramOpen = false
                                loaded = false
                                payload.reset()
                                bridgeHandler.removeCallbacksAndMessages(null)
                                currentOnQuoteReturn(false)
                                // One automatic rebuild; repeated renderer failures offer an explicit retry.
                                if (rendererGeneration == 0) rendererGeneration++ else rendererFailed = true
                            }
                            bridgeView.release(rendererGone = true)
                            return true
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (view !== webRef || url != CONVERSATION_ASSET_URL) return
                            loaded = true
                            payload.reset()
                            payload.javascript(currentMessages, selectionJson(), thinkLabelsJson(),
                                currentDark, currentScale, currentThinkPref)?.let { view?.evaluateJavascript(it, null) }
                        }
                    }
                    loadUrl(CONVERSATION_ASSET_URL)
                }
            },
            update = { v ->
                if (!v.released) {
                v.quoteLabel = s["solve.quoteAction"]
                v.selectionHint = s["solve.quoteSelectionHint"]
                v.quoteEnabled = onQuoteText != null && !selectionMode
                v.bookmarkEnabled = onBookmarkText != null && !selectionMode
                v.findPdfEnabled = onFindPdfText != null && !selectionMode
                v.bookmarkLabel = s["bookmark.action"]
                v.findPdfLabel = s["pdfSource.find"]
                if (loaded) {
                    payload.javascript(currentMessages, selectionJson(), thinkLabelsJson(),
                        currentDark, currentScale, currentThinkPref)?.let { v.evaluateJavascript(it, null) }
                }
                }
            },
            onRelease = { view ->
                if (webRef === view) {
                    currentOnQuoteReturn(false)
                    webRef = null
                    loaded = false
                    payload.reset()
                    bridgeHandler.removeCallbacksAndMessages(null)
                }
                view.release()
            },
            modifier = Modifier.fillMaxSize()
        )
        }

        imageGallery?.let { ImageGalleryDialog(it) { imageGallery = null } }
    }
}

private fun notebookSearchRequest(messages: List<ChatMsg>, target: AppNav.NotebookMatch, saved: String?): String {
    val hit = saved?.indexOf(target.query, ignoreCase = true) ?: -1
    if (saved == null || hit < 0) return "null"
    val role = if (target.answer) "assistant" else "user"
    val candidates = messages.indices.filter { messages[it].role == role }
    val priority = if (target.answer) candidates.reversed() else candidates
    var source = priority.firstOrNull { messages[it].content.contains(saved) }
    // A similar-exercise answer can index several consecutive replies (including follow-up questions).
    // Locate the first stored occurrence in the original message sequence.
    if (source == null && target.answer) {
        var cursor = 0
        for (index in messages.indices.dropWhile { messages[it].role != "assistant" }) {
            val body = messages[index].content
            if (body.isEmpty()) continue
            val start = saved.indexOf(body, cursor)
            if (start < 0) continue
            if (hit >= start && hit + target.query.length <= start + body.length) {
                source = index
                break
            }
            cursor = start + body.length
            if (cursor > hit) break
        }
    }
    // Older photo conversations may only contain the recognized question in the notebook row.
    return JSONObject().put("query", target.query).put("preferred", source ?: priority.firstOrNull() ?: 0)
        .put("supplemental", saved.takeIf { source == null })
        .toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
}

/** Keep immutable prefix messages on the JS side; no repeated Base64 serialization while streaming. */
internal class ConversationPayload(private val images: ConversationImageStore? = null) {
    private var previous: List<ChatMsg> = emptyList()
    private var options: String? = null
    private var revision = 0L
    fun reset() { previous = emptyList(); options = null; images?.clear() }
    fun javascript(messages: List<ChatMsg>, selection: String, labels: String,
                   dark: Boolean, scale: Float, thinkPref: String): String? {
        var prefix = 0
        while (prefix < previous.size && prefix < messages.size && previous[prefix] == messages[prefix]) prefix++
        val args = "$selection, $labels, $dark, $scale, ${JSONObject.quote(thinkPref)}"
        if (prefix == previous.size && prefix == messages.size && options == args) return null
        val changed = (images?.messages(messages) ?: messages).drop(prefix)
        val tail = buildMessagesJson(changed, List(changed.size) { (++revision).toString() })
        previous = messages.toList()
        options = args
        return "patchMessages($prefix, $tail, $args);"
    }
}

/**
 * 对话区专用 WebView：**窗口重新拿到焦点时复位手势状态**。
 *
 * 为什么需要（2026-10-07 用户反馈，荣耀 Magic7 / MagicOS）：
 *   点手机的「灵动胶囊」（系统悬浮胶囊）之后，再轻点屏幕，**偶发**被当成"长按气泡"→ 直接进入多选/选中态。
 *   成因是这类系统悬浮窗会**抢走一次触摸**：WebView 只收到了 ACTION_DOWN，随后的 UP/CANCEL 被系统吃掉，
 *   于是 ① Chromium 内部把那根"悬着的手指"still-down 状态留着（下一次轻点的 downTime 一算就超长按时限）；
 *   ② 网页里 450ms 的长按定时器也悬着，恢复后一触发就派发 longPressMessage。
 *   这里在窗口重新聚焦时补一个 ACTION_CANCEL 并通知网页复位（`window.dshResetGestures()`），两头一起清干净。
 */
private class ResetOnFocusWebView(context: android.content.Context) : ConversationTextSelection(context) {
    var released = false
        private set
    fun release(rendererGone: Boolean = false) {
        if (released) return
        released = true
        quoteEnabled = false
        onQuote = { _, _, _, _ -> }
        onBookmark = { _, _, _, _ -> }
        onFindPdf = { _, _, _, _ -> }
        setOnTouchListener(null)
        if (!rendererGone) { removeJavascriptInterface("Android"); stopLoading() }
        (parent as? android.view.ViewGroup)?.removeView(this)
        destroy()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (released) return
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) return
        val now = android.os.SystemClock.uptimeMillis()
        runCatching {
            // 补一个「取消」事件：没有前面那根手指的 UP，Chromium 会一直认为它在按着
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        // 网页侧也要清：长按定时器 / 「手指按住」标记 / 思考块点击候选
        runCatching { evaluateJavascript("window.dshResetGestures && window.dshResetGestures();", null) }
    }
}

internal fun buildMessagesJson(messages: List<ChatMsg>, revisions: List<String>? = null): String = JSONArray().apply {
    messages.forEachIndexed { index, m ->
        put(JSONObject().put("role", m.role).put("content", m.content).put("images", JSONArray(m.images))
            .put("showImagesInline", m.showImagesInline)
            .put("renderRevision", revisions?.get(index))
            .put("thinking", m.thinking ?: JSONObject.NULL).put("thinkingOpen", m.thinkingOpen)
            .put("thinkingDone", m.thinkingDone).put("quotedText", m.quotedText ?: JSONObject.NULL)
            .put("sourcesLabel", m.sourcesLabel)
            .put("documents", JSONArray().apply { m.documents.forEach { document ->
                put(JSONObject().put("name", document.name).put("size", document.size))
            } })
            .put("sources", JSONArray().apply { m.sources.forEach { source ->
                put(JSONObject().put("title", source.title).put("url", source.url))
            } })
            .put("messageKey", m.messageKey ?: JSONObject.NULL)
            .put("hashes", JSONObject().apply {
                listOf("body", "thinking", "quote").forEach { scope -> put(scope, m.sourceHash(scope) ?: JSONObject.NULL) }
            })
            .put("quoteAnchor", m.quoteAnchor?.let { anchor -> JSONObject()
                .put("messageKey", anchor.messageKey).put("scope", anchor.scope)
                .put("start", anchor.start).put("end", anchor.end).put("sourceHash", anchor.sourceHash)
                .put("visualMath", anchor.visualMath)
            } ?: JSONObject.NULL))
    }
}.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

internal const val CONVERSATION_ASSET_URL = "file:///android_asset/conversation_render.html"

/** Asset URLs remain available with file access disabled; private files/providers are never needed. */
@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
internal fun configureConversationWebView(view: WebView) {
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        blockNetworkLoads = true
    }
}

internal open class ConversationAssetClient(private val images: ConversationImageStore? = null) : WebViewClient() {
    private val assets = Regex("file:///android_asset/(conversation_render\\.html|quote_navigation\\.js|svg_diagrams\\.js|media_viewer\\.js|katex/(katex\\.min\\.(js|css)|fonts/KaTeX_[A-Za-z0-9-]+\\.woff2))")
    private fun resource(url: String): WebResourceResponse? = images?.response(url) ?: if (assets.matches(url)) null else
        WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), java.io.ByteArrayInputStream(byteArrayOf()))

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? = resource(request.url.toString())
    @Suppress("DEPRECATION")
    override fun shouldInterceptRequest(view: WebView?, url: String): WebResourceResponse? = resource(url)
    // This renderer has no web navigation. Its images and controls use the local bridge.
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean = true
    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String): Boolean = true
}
