package com.zsz.studyassistant.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** 一条对话消息（用于气泡渲染） */
data class ChatMsg(val role: String, val content: String)

/**
 * 对话正文 WebView：占满给定区域，内部上下滚动，滚动条常驻、手势不被父级拦截。
 * 消息以聊天气泡显示：assistant=灰底，user=浅绿底。KaTeX 排版。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ConversationWebView(messages: List<ChatMsg>, modifier: Modifier = Modifier) {
    val currentMessages by rememberUpdatedState(messages)
    var loaded by remember { mutableStateOf(false) }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
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
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        loaded = true
                        view?.let { renderMessages(it, currentMessages) }
                    }
                }
                loadUrl("file:///android_asset/conversation_render.html")
            }
        },
        update = { v -> if (loaded) renderMessages(v, currentMessages) },
        modifier = modifier
    )
}

private fun renderMessages(v: WebView, messages: List<ChatMsg>) {
    val json = messages.joinToString(",", "[", "]") { m ->
        val c = m.content
            .replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r")
        "{\"role\":\"${m.role}\",\"content\":\"$c\"}"
    }
    v.evaluateJavascript("renderMessages($json);", null)
}
