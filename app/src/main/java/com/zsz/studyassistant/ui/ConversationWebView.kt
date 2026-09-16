package com.zsz.studyassistant.ui

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** 一条对话消息（用于气泡渲染）；images 为 base64 编码的附图列表 */
data class ChatMsg(val role: String, val content: String, val images: List<String> = emptyList())

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
    /** 每次自增 = 立刻重置到顶部（换题/换会话时用，无动画） */
    resetScrollSignal: Int = 0,
    /** 会话是否已在顶部（用于把按钮在 ↑ / ↓ 之间切换） */
    onAtTopChange: (Boolean) -> Unit = {},
    /** 点击气泡末尾蓝色「继续生成」时回调 */
    onContinue: () -> Unit = {},
    /** 长按某条消息气泡时回调（参数为界面消息下标）→ 用于进入多选界面 */
    onLongPressMessage: (Int) -> Unit = {},
    /** 是否处于多选态：气泡右上角显示选择圆圈，选中的显示红框 */
    selectionMode: Boolean = false,
    /** 多选态下已选中的消息下标 */
    selectedIndices: Set<Int> = emptySet(),
    /** 多选时**不可选中/不可删除**的下标（题干与 AI 首条回复：答案 / 批改结果） */
    lockedIndices: Set<Int> = emptySet(),
    /** 多选态下点击某条气泡 → 切换选中 */
    onToggleSelect: (Int) -> Unit = {}
) {
    val currentMessages by rememberUpdatedState(messages)
    // ⚠️ JS 桥接对象只在 AndroidView 的 factory 里创建一次，会永久捕获当时的 lambda。
    // 若直接调用 onXxx()，用的就是**首次组合**时的那份闭包（其中的 selectedIndices/lockedIndices 等值永远是旧的），
    // 曾导致「受保护消息（题干/AI 首条）长按仍被算作可选中 → 已选 1 条」。
    // 因此所有回调都必须通过 rememberUpdatedState 取最新值。
    val currentOnContinue by rememberUpdatedState(onContinue)
    val currentOnLongPress by rememberUpdatedState(onLongPressMessage)
    val currentOnToggleSelect by rememberUpdatedState(onToggleSelect)
    val currentOnAtTopChange by rememberUpdatedState(onAtTopChange)
    var loaded by remember { mutableStateOf(false) }
    var lastJson by remember { mutableStateOf<String?>(null) }
    var zoomImage by remember { mutableStateOf<String?>(null) }
    var webRef by remember { mutableStateOf<WebView?>(null) }

    /** 多选状态 → JS 载荷（选择/锁定变化也要触发重渲染，故与消息一起进入去重 key） */
    fun selectionJson(): String =
        if (selectionMode) {
            "{\"on\":true,\"selected\":[" + selectedIndices.sorted().joinToString(",") +
                "],\"locked\":[" + lockedIndices.sorted().joinToString(",") + "]}"
        } else {
            "{\"on\":false,\"selected\":[],\"locked\":[]}"
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

    // 换题/换会话：立刻回到顶部（无动画）
    LaunchedEffect(resetScrollSignal) {
        if (resetScrollSignal > 0) {
            webRef?.evaluateJavascript("window.dshScrollReset && window.dshScrollReset();", null)
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    webRef = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = true
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
                        fun openImage(b64: String) {
                            Handler(Looper.getMainLooper()).post { zoomImage = b64 }
                        }

                        /** 气泡末尾的蓝色「继续生成」被点击 */
                        @JavascriptInterface
                        fun continueGeneration() {
                            Handler(Looper.getMainLooper()).post { currentOnContinue() }
                        }

                        /** 长按消息气泡 → 进入原生多选（编辑）界面，并选中被长按的那条 */
                        @JavascriptInterface
                        fun longPressMessage(idx: Int) {
                            Handler(Looper.getMainLooper()).post { currentOnLongPress(idx) }
                        }

                        /** 多选态下点击气泡 → 切换选中 */
                        @JavascriptInterface
                        fun toggleMessage(idx: Int) {
                            Handler(Looper.getMainLooper()).post { currentOnToggleSelect(idx) }
                        }

                        /** 网页上报「是否已滚动到顶部」→ 按钮在 ↑ / ↓ 之间切换 */
                        @JavascriptInterface
                        fun onAtTop(atTop: Boolean) {
                            Handler(Looper.getMainLooper()).post { currentOnAtTopChange(atTop) }
                        }

                        @JavascriptInterface
                        fun onHeightChange(h: Int) {
                            // 占位：网页会尝试回调高度，这里无需处理
                        }
                    }, "Android")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            loaded = true
                            val json = buildMessagesJson(currentMessages)
                            val sel = selectionJson()
                            lastJson = json + "|" + sel
                            view?.evaluateJavascript("renderMessages($json, $sel);", null)
                        }
                    }
                    loadUrl("file:///android_asset/conversation_render.html")
                }
            },
            update = { v ->
                // 内容未变化时跳过重渲染，减少 WebView 开销（多选状态也进 key：圆圈/红框要即时刷新）
                if (loaded) {
                    val json = buildMessagesJson(currentMessages)
                    val sel = selectionJson()
                    val key = json + "|" + sel
                    if (key != lastJson) {
                        lastJson = key
                        v.evaluateJavascript("renderMessages($json, $sel);", null)
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        zoomImage?.let { b64 -> ImageZoomView(b64) { zoomImage = null } }
    }
}

/** 全屏图片查看：双指缩放（1~6 倍）+ 拖动；✕ 或返回键关闭 */
@Composable
private fun ImageZoomView(b64: String, onDismiss: () -> Unit) {
    val bitmap = remember(b64) {
        runCatching {
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    BackHandler { onDismiss() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color(0xF2000000))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale > 1f) offset + pan else Offset.Zero
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        ),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text("⚠️", color = androidx.compose.ui.graphics.Color.White, fontSize = 32.sp)
            }
            Text(
                "✕",
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 26.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .clickable { onDismiss() }
            )
            Text(
                "${"%.1f".format(scale)}×",   // 实时显示缩放比
                color = androidx.compose.ui.graphics.Color(0x99FFFFFF),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
            )
        }
    }
}

private fun buildMessagesJson(messages: List<ChatMsg>): String =
    messages.joinToString(",", "[", "]") { m ->
        val c = m.content
            .replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r")
        val imgs = m.images.joinToString(",", "[", "]") { b -> "\"$b\"" }
        "{\"role\":\"${m.role}\",\"content\":\"$c\",\"images\":$imgs}"
    }
