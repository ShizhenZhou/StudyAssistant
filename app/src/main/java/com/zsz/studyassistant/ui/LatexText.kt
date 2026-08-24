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
 * 用 WebView + KaTeX 渲染含 LaTeX 的内容。
 * 高度 = 内容真实高度（取历次测量最大值，绝不缩小、不设上限）。
 * WebView 不拦截触摸（NoScrollWebView），由外层 LazyColumn 负责整条滚动 —— 答案多长都能完整浏览。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LatexText(content: String, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val currentContent by rememberUpdatedState(content)
    var height by remember { mutableStateOf(with(density) { 60.dp }) }
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
                setBackgroundColor(Color.TRANSPARENT)
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onHeightChange(h: Int) {
                        if (h > 0) mainHandler.post {
                            val nh = with(density) { h.toFloat().toDp() }
                            // 只增不减，避免测量抖动导致内容被裁剪
                            if (nh > height) height = nh
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
