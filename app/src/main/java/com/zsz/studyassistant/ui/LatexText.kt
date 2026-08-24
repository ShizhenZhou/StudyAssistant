package com.zsz.studyassistant.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 用 WebView + KaTeX 渲染含 LaTeX 的内容（自动测量高度）。
 * 公式（$...$ / $$...$$）被渲染为数学排版，其余为普通文本。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LatexText(content: String, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    var height by remember { mutableStateOf(with(density) { 28.dp }) }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                setBackgroundColor(Color.TRANSPARENT)
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onHeightChange(h: Int) {
                        if (h > 0) post { height = with(density) { h.toFloat().toDp() } }
                    }
                }, "Android")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.let { renderLaTeX(it, content) }
                    }
                }
                loadUrl("file:///android_asset/latex_render.html")
            }
        },
        update = { v -> renderLaTeX(v, content) },
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
