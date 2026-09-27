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
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * B7 导出：把**一道题**（原图 + 题干 + 解答 + 分类/知识点）渲染成 **PNG 长图** 或 **PDF**。
 *
 * 为什么用 WebView：解答里全是 LaTeX，只有 WebView + KaTeX 能排版正确。
 *
 * ## 踩过的两个坑（2026-09-27 用户实测反馈：PNG 全白、PDF 全黑且 11 页）
 * 1. 原来把 WebView 以 **1×1** 挂到窗口上 → 父布局的测量把宽度定成 1px → 网页按 1 像素宽排版，
 *    `scrollHeight` 变成上万像素 → 截出来是"1px 宽的白色细条"，贴进 A4 后每页几乎空白
 *    （查看器把透明页显示成黑）。
 *    ✅ 现在：挂上去时就用**真实宽度**（`LayoutParams(widthPx, 1)`）+ 立刻手动 measure/layout 成该宽度，
 *    并等一小会儿再加载页面，保证 JS 量高度时宽度是对的；再加"高度/宽度比异常就判失败"的兜底。
 * 2. 硬件加速的 WebView 往 `Canvas` 上画经常是空的 → 必须 `setLayerType(LAYER_TYPE_SOFTWARE)`。
 *    截完还会检查**是否真的画上了"墨迹"**（不是"有没有像素"，否则白底也会被当成成功）。
 *
 * ## PDF 的做法
 * 渲染成位图后按 A4（150dpi：1240×1754）切片贴页；调用方按 A4 内容宽度（1168px）传 `widthPx`，
 * 文字≈1:1 落纸，清晰度够用。曾试过 WebView 的 PrintDocumentAdapter 出矢量 PDF，
 * 但它的 Layout/Write 回调构造器在当前 compileSdk 下是包内可见，Kotlin 无法匿名继承，故放弃。
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

    /** 导出结果 */
    data class Result(val ok: Boolean, val error: String? = null)

    /** A4 @ 150dpi（PDF 兜底路径用） */
    private const val A4_WIDTH = 1240
    private const val A4_HEIGHT = 1754
    private const val MARGIN = 36
    /** 内容高度上限：超过"宽度的 30 倍"基本可断定是按窄宽度排版的（正常一道题最多两三页） */
    private const val MAX_HEIGHT_RATIO = 30f

    /** 导出内容（题干/解答/可选原图/分类标签） */
    data class Payload(
        val question: String,
        val answer: String,
        /** 原题图（base64 JPEG；没有就 null） */
        val imageBase64: String? = null,
        val category: String? = null,
        val tags: List<String> = emptyList(),
        /** 导出**始终用浅色**：长图/PDF 是要发人/打印的，深底既费墨又难看（与 App 主题无关） */
        val dark: Boolean = false,
        val fontScale: Float = 1f,
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
     * [pageHeight]Px 切成若干 [IntRange]。仅作打印失败时的兜底。
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

    /** 原图 bytes → base64（没有就 null） */
    fun imageBase64(bytes: ByteArray?): String? =
        bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

    // ─────────────────────────────────────────────────────────────────────
    // 对外唯一入口
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 渲染并写出：PNG = 离屏 WebView 截图；PDF = 同一张长图按 A4 切片。
     * 空白/布局异常会**自动重试一次**（渲染偶发未完成），仍失败才回报失败 —— 绝不存一张空白图给用户。
     */
    suspend fun export(
        context: Context,
        payload: Payload,
        widthPx: Int,
        format: Format,
        out: OutputStream
    ): Result {
        var last = Result(false, null)
        repeat(2) { attempt ->
            last = exportOnce(context, payload, widthPx, format, out)
            if (last.ok) return last
            // 只有"渲染类"失败值得重试；写文件失败重试也没用
            val retryable = last.error == "内容为空" || last.error == "页面渲染失败" || last.error == "页面布局异常"
            if (!retryable || attempt == 1) return last
            delay(250)
        }
        return last
    }

    /** 一次完整的导出尝试（内部全在主线程操作 WebView） */
    private suspend fun exportOnce(
        context: Context,
        payload: Payload,
        widthPx: Int,
        format: Format,
        out: OutputStream
    ): Result = withContext(Dispatchers.Main) {
        val activity = context as? Activity
        val host = activity?.window?.decorView as? ViewGroup
        var web: WebView? = null
        var attached = false
        try {
            val width = widthPx.coerceAtLeast(320)
            // ① 建一个"按正确宽度布局"的离屏 WebView
            //    ⚠️ 挂上去时必须给**真实宽度**：1×1 会让网页按 1px 排版（白 PNG / 11 页 PDF 的根因）
            web = newWebView(context)
            if (host != null) {
                web.layoutParams = ViewGroup.LayoutParams(width, 1)
                // 视觉上藏起来：透明 + 移出屏幕。直接 draw(Canvas) 不受这两个属性影响，所以不影响截图内容
                web.alpha = 0f
                web.translationY = -10000f
                host.addView(web)
                attached = true
            }
            web.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1, View.MeasureSpec.EXACTLY)
            )
            web.layout(0, 0, width, 1)
            delay(40)   // 让 WebView 内部按这个宽度完成一次布局

            // ② 加载 → 渲染 → 等 JS 回报内容高度
            val json = payloadJson(payload)
            val height = withTimeoutOrNull(11000) {
                awaitContentHeight(web, json, payload.fontScale)
            }
            if (height == null || height <= 0) return@withContext Result(false, "页面渲染失败")
            // 异常高 = 按窄宽度排版的典型症状 → 判失败（提示重试），绝不存一条白条
            if (height > (width * MAX_HEIGHT_RATIO).toInt()) return@withContext Result(false, "页面布局异常")

            // ③ 截图 → 写文件（PNG 直接压；PDF 按 A4 切片贴页）
            val bmp = captureBitmap(web, width, height)
                ?: return@withContext Result(false, "内容为空")
            val done = withContext(Dispatchers.IO) {
                runCatching {
                    if (format == Format.PNG) {
                        out.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } else {
                        writePdfFromBitmap(bmp, out)
                    }
                    true
                }.getOrDefault(false)
            }
            bmp.recycle()
            if (!done) Result(false, "写入失败") else Result(true)
        } catch (e: Exception) {
            Result(false, e.message ?: e.javaClass.simpleName)
        } finally {
            if (attached) runCatching { host?.removeView(web) }
            runCatching { web?.destroy() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // WebView 与截图
    // ─────────────────────────────────────────────────────────────────────

    private fun newWebView(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        // ★ 必须软件渲染：硬件加速的 WebView 往 Canvas 上画经常是空白
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        // 不透明白底：避免 PNG 出现透明像素（分享/打印时透明会被查看器显示成黑）
        setBackgroundColor(Color.WHITE)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }

    /** 加载 → 注入 payload → 等 JS 回调内容高度（页面里会等 KaTeX 与字体就绪） */
    private suspend fun awaitContentHeight(
        web: WebView,
        json: String,
        fontScale: Float
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
                view?.evaluateJavascript("renderExport($json, false, $fontScale);", null)
            }
        }
        main.postDelayed({ finish(null) }, 11500)
        web.loadUrl("file:///android_asset/export_render.html")
    }

    /** 整页画进 Bitmap；画完检查"有没有墨迹"，空白返回 null */
    private fun captureBitmap(web: WebView, width: Int, height: Int): Bitmap? {
        val h = height.coerceIn(1, 30000)
        web.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        web.layout(0, 0, width, h)
        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        try {
            web.draw(Canvas(bmp))
        } catch (e: Exception) {
            bmp.recycle()
            return null
        }
        return if (hasInk(bmp)) bmp else { bmp.recycle(); null }
    }

    /**
     * "有没有画上东西"检查：找**既不是全透明、也不是接近纯白**的像素。
     * （之前只看 alpha → "1px 宽的白色条"被当成成功 → 用户拿到一张白图）
     */
    private fun hasInk(bmp: Bitmap): Boolean {
        var ink = 0
        var x = 0
        while (x < bmp.width) {
            var y = 0
            while (y < bmp.height) {
                val p = bmp.getPixel(x, y)
                if (Color.alpha(p) > 16 &&
                    (Color.red(p) < 245 || Color.green(p) < 245 || Color.blue(p) < 245)
                ) {
                    ink++
                    if (ink >= 40) return true
                }
                y += 3
            }
            x += 3
        }
        return false
    }

    /** 兜底：把长图按 A4 切片贴进 PDF（打印路径失败时用） */
    private fun writePdfFromBitmap(bitmap: Bitmap, out: OutputStream) {
        val doc = PdfDocument()
        try {
            val availW = A4_WIDTH - MARGIN * 2
            val scale = availW.toFloat() / bitmap.width.toFloat()
            val availH = A4_HEIGHT - MARGIN * 2
            val pageSrcH = (availH / scale).toInt().coerceAtLeast(1)
            pageSlices(bitmap.height, pageSrcH).forEachIndexed { i, r ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, i + 1).create())
                val src = android.graphics.Rect(0, r.first, bitmap.width, r.last + 1)
                val hPx = ((r.last + 1 - r.first) * scale).toInt()
                val dst = android.graphics.Rect(MARGIN, MARGIN, MARGIN + availW, MARGIN + hPx)
                page.canvas.drawBitmap(bitmap, src, dst, null)
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally {
            runCatching { doc.close() }
            runCatching { out.close() }
        }
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
}
