package com.zsz.studyassistant.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * B7 导出：把**一道题**（原图 + 题干 + 解答 + 分类/知识点）渲染成 **PNG 长图** 或 **PDF**。
 *
 * 实现思路（为什么用 WebView）：解答里全是 LaTeX，只有 WebView + KaTeX 能排版正确，
 * 原生 Canvas/StaticLayout 会把 `$...$` 原样画出来。所以这里用一个**离屏 WebView**：
 *   ① 加载 `export_render.html`（与对话区共用 KaTeX 资源）
 *   ② 注入 payload → 页面渲染完成并把内容高度回调给原生
 *   ③ 原生按「屏宽 × 内容高度」measure/layout 这个 WebView，再 `draw(Canvas)` 到一张 Bitmap
 *   ④ PNG 直接压缩；PDF 用 [pageSlices] 把长图切成 A4 分页
 *
 * 为什么不挂在界面上：导出是瞬时动作，界面里不该出现一个"隐形的网页"；
 * 但 WebView 需要 window token 才能正常渲染，所以会把它以 1×1 的尺寸临时挂到 Activity 上，
 * 截完立刻移除（见 [capture]）。
 */
object QuestionExporter {

    /** 导出格式：对话框里让用户选 */
    enum class Format(val id: String, val mime: String, val ext: String) {
        PNG("png", "image/png", "png"),
        PDF("pdf", "application/pdf", "pdf");

        companion object {
            val DEFAULT = PNG
            fun fromId(id: String?): Format = entries.firstOrNull { it.id == id } ?: DEFAULT
        }
    }

    /** A4 @ 150dpi（PdfDocument 用 pt 坐标系，这里按 150dpi 换算成像素，保证清晰度） */
    private const val A4_WIDTH = 1240
    private const val A4_HEIGHT = 1754
    /** 页面内边距与页脚说明 */
    private const val MARGIN = 36

    /** 导出内容（题干/解答/可选原图/分类标签） */
    data class Payload(
        val question: String,
        val answer: String,
        /** 原题图（base64 JPEG；没有就 null） */
        val imageBase64: String? = null,
        val category: String? = null,
        val tags: List<String> = emptyList(),
        val dark: Boolean = false,
        val fontScale: Float = 1f,
        /** 页面里显示的按钮/字段名（跟随界面语言） */
        val labelQuestion: String = "题目",
        val labelAnswer: String = "解答",
        val labelCategory: String = "分类",
        val labelTags: String = "知识点",
        val footer: String = ""
    )

    /** 文件名：`题目_20260926-2210.png`（题干太长时截断，非法字符替换掉） */
    fun fileName(format: Format, question: String, at: Long = System.currentTimeMillis()): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(at))
        var base = question.trim().replace(Regex("\\s+"), "_")
        base = base.replace(Regex("[\\\\/:*?\"<>|]"), "")
        if (base.length > 18) base = base.take(18)
        if (base.isBlank()) base = "StudyAssistant"
        return "${base}_$ts.${format.ext}"
    }

    /**
     * PDF 分页切片（纯函数，便于测试）：把一张高 [contentHeight]Px 的长图按每页可用高度
     * [pageHeight]Px 切成若干 [IntRange]（每页在原图里的 y 区间）。
     */
    fun pageSlices(contentHeight: Int, pageHeight: Int): List<IntRange> {
        if (contentHeight <= 0 || pageHeight <= 0) return emptyList()
        val out = mutableListOf<IntRange>()
        var y = 0
        while (y < contentHeight) {
            val end = minOf(y + pageHeight, contentHeight) - 1
            out += y..end
            y += pageHeight
        }
        return out
    }

    /**
     * 离屏渲染 + 截图（必须在主线程调用 WebView 相关方法，内部已切到 Main）。
     * @param widthPx 截图宽度（一般用屏幕宽度）
     * @return 渲染好的 Bitmap；失败返回 null
     */
    suspend fun capture(context: Context, payload: Payload, widthPx: Int): Bitmap? =
        withContext(Dispatchers.Main) {
            val activity = context as? Activity
            val web = WebView(context)
            web.settings.javaScriptEnabled = true
            web.setBackgroundColor(Color.TRANSPARENT)
            web.isVerticalScrollBarEnabled = false
            // 1×1 挂到窗口上：WebView 需要 window token 才会真正渲染（否则可能截出空白）
            val host = activity?.window?.decorView as? ViewGroup
            var attached = false
            try {
                if (host != null) {
                    web.layoutParams = ViewGroup.LayoutParams(1, 1)
                    web.alpha = 0f
                    host.addView(web)
                    attached = true
                }
                val height = withTimeoutOrNull(8000) { awaitRendered(context, web, payload, widthPx) }
                    ?: return@withContext null
                if (height <= 0) return@withContext null
                // ② 按「屏宽 × 内容高度」布局，然后整页画进 Bitmap
                val h = height.coerceAtMost(20000)   // 保护：极端长图（>20000px）截断，避免 OOM
                web.measure(
                    View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                )
                web.layout(0, 0, widthPx, h)
                val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                web.draw(Canvas(bmp))
                if (isBlank(bmp)) null else bmp
            } catch (e: Exception) {
                null
            } finally {
                if (attached) runCatching { host?.removeView(web) }
                runCatching { web.destroy() }
            }
        }

    /** 全透明（= 什么都没画出来）视为失败，避免把一张空白图存给用户 */
    private fun isBlank(bmp: Bitmap): Boolean {
        var checked = 0
        var y = 0
        while (y < bmp.height && checked < 400) {
            var x = 0
            while (x < bmp.width && checked < 400) {
                if (Color.alpha(bmp.getPixel(x, y)) != 0) return false
                x += 37
                checked++
            }
            y += 37
        }
        return true
    }

    /** 加载页面 → 注入 payload → 等 JS 回调内容高度 */
    private suspend fun awaitRendered(
        context: Context,
        web: WebView,
        payload: Payload,
        widthPx: Int
    ): Int? = suspendCancellableCoroutine { cont ->
        val main = Handler(Looper.getMainLooper())
        var done = false
        fun finish(h: Int?) {
            if (done) return
            done = true
            if (cont.isActive) cont.resume(h)
        }
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun onReady(height: Int) = main.post { finish(height) }
        }, "AndroidExport")
        web.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val json = payloadJson(payload)
                val dark = payload.dark
                val scale = payload.fontScale
                view?.evaluateJavascript("renderExport($json, $dark, $scale);", null)
            }
        }
        // 兜底：万一 JS 没回调（资源缺失等），8 秒后按失败处理
        main.postDelayed({ finish(null) }, 8500)
        val w = widthPx.coerceAtLeast(320)
        web.loadUrl("file:///android_asset/export_render.html")
        // 让 WebView 先按这个宽度布局，页面里的百分比/换行才准确
        web.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1, View.MeasureSpec.AT_MOST)
        )
    }

    /** payload → JSON（手写，避免引入额外依赖；字段都要转义） */
    private fun payloadJson(p: Payload): String {
        fun s(t: String?) = "\"" + (t ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r") + "\""
        val tags = p.tags.joinToString(",") { s(it) }
        return buildString {
            append("{")
            append("\"question\":").append(s(p.question)).append(",")
            append("\"answer\":").append(s(p.answer)).append(",")
            append("\"imageBase64\":").append(if (p.imageBase64.isNullOrBlank()) "null" else s(p.imageBase64)).append(",")
            append("\"category\":").append(if (p.category.isNullOrBlank()) "null" else s(p.category)).append(",")
            append("\"tags\":[").append(tags).append("],")
            append("\"labelQuestion\":").append(s(p.labelQuestion)).append(",")
            append("\"labelAnswer\":").append(s(p.labelAnswer)).append(",")
            append("\"labelCategory\":").append(s(p.labelCategory)).append(",")
            append("\"labelTags\":").append(s(p.labelTags)).append(",")
            append("\"footer\":").append(s(p.footer))
            append("}")
        }
    }

    /** 原图 bytes → base64（没有就 null） */
    fun imageBase64(bytes: ByteArray?): String? =
        bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

    /** 写 PNG */
    suspend fun writePng(bitmap: Bitmap, out: OutputStream) = withContext(Dispatchers.IO) {
        out.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * 写 PDF：把长图按 A4 切页贴进去（页宽等比缩放，保持清晰与版式）。
     * 采用 150dpi 的页面尺寸，内容两边留 [MARGIN] 白边。
     */
    suspend fun writePdf(bitmap: Bitmap, out: OutputStream) = withContext(Dispatchers.IO) {
        val doc = PdfDocument()
        try {
            val availW = A4_WIDTH - MARGIN * 2
            val scale = availW.toFloat() / bitmap.width.toFloat()
            val availH = A4_HEIGHT - MARGIN * 2
            // 每页能容纳"原图"多高（按缩放比例换算回原图坐标）
            val pageSrcH = (availH / scale).toInt().coerceAtLeast(1)
            val slices = pageSlices(bitmap.height, pageSrcH)
            slices.forEachIndexed { i, r ->
                val page = doc.startPage(
                    PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, i + 1).create()
                )
                val src = android.graphics.Rect(0, r.first, bitmap.width, r.last + 1)
                val hPx = ((r.last + 1 - r.first) * scale).toInt()
                val dst = android.graphics.Rect(MARGIN, MARGIN, MARGIN + availW, MARGIN + hPx)
                page.canvas.drawBitmap(bitmap, src, dst, null)
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally {
            runCatching { doc.close() }
            out.use { }
        }
    }
}
