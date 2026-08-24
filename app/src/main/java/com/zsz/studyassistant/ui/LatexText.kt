package com.zsz.studyassistant.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 用 WebView + KaTeX(auto-render) 渲染含 LaTeX 的内容，自动测量高度。
 * 支持 $$..$$、\[..\]、\(..\)、$..$、\begin{align} 等常见分隔符。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LatexText(content: String, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val currentContent by rememberUpdatedState(content)
    var height by remember { mutableStateOf(with(density) { 28.dp }) }
    var loaded by remember { mutableStateOf(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    AndroidView(
        factory = { ctx ->
            NoScrollWebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = true
                // 禁止 WebView 自身滚动，交给外层 LazyColumn 滚动
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = android.view.View.OVER_SCROLL_NEVER
                setBackgroundColor(Color.TRANSPARENT)
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onHeightChange(h: Int) {
                        if (h > 0) mainHandler.post {
                            // 取最大值，避免字面加载后高度回缩
                            val newH = with(density) { h.toFloat().toDp() }
                            if (newH > height) height = newH
                        }
                    }
                }, "Android")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        loaded = true
                        view?.let { renderLaTeX(it, currentContent) }
                    }
                }
                loadUrl("file:///android_asset/latex_render.html")
            }
        },
        update = { v -> if (loaded) renderLaTeX(v, currentContent) },
        modifier = modifier.height(height)
    )
}

private fun renderLaTeX(v: WebView, content: String) {
    val escaped = content
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
    v.evaluateJavascript("loadText(\"$escaped\");", null)
}
