package com.zsz.studyassistant.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
 * ## 截图方案（经过 4 轮真机踩坑后的最终形态）
 * **首选：把导出页真的显示出来（一层临时 overlay），再用 `PixelCopy` 逐带拷贝屏幕上真实的像素。**
 * 理由（全部是真机实测到的坑）：
 *  1. 离屏 WebView 被 `1×1` 挂着 → 网页按 1px 排版 → 截出"白条"（PDF 变成 11 页全空）；
 *  2. 离屏 WebView 用 `alpha=0` 想隐形 → 软件图层把 alpha 也画进去 → 截出来**整张全透明**；
 *  3. 软件图层下 `web.draw(canvas)` 是**把图层贴在画布原点、忽略调用方 translate** →
 *     "滚动 + 位移一次画进整图"只有第一带落图 → **成品只截到第一屏、下面全空白**；
 *  4. 所以不再赌"离屏 draw 能不能画出来"：**屏幕上看得见，就一定拷得到**。
 *     overlay 里只有 WebView + 一行提示（提示在 WebView 之外，不会被截进图里）。
 *
 * 每一带都要过校验：**必须有"墨迹"**、**相邻两带不能完全相同**（相同 = 滚动没生效）。
 * 任何一条不过 → 重试这一带（最多 [BAND_RETRY] 次），仍不过 → 整次导出判失败
 * —— 宁可明确报失败，也不再给一张"截了一半"的图。
 *
 * 离屏绘制只作为**拿不到 Activity 时**的兜底。
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

    /** A4 @ 150dpi（PDF 分页用） */
    private const val A4_WIDTH = 1240
    private const val A4_HEIGHT = 1754
    private const val MARGIN = 36
    /** 内容高度上限：超过"宽度的 30 倍"基本可断定是按窄宽度排版的（正常一道题最多两三页） */
    private const val MAX_HEIGHT_RATIO = 30f
    /** 每一带最多重试次数 */
    private const val BAND_RETRY = 3

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
        /** 导出过程中显示在页面下方的一行字（例如"正在导出…"），跟随界面语言 */
        val progress: String = "",
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
     * [pageHeight]Px 切成若干 [IntRange]。
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

    /** 渲染并写出；渲染类失败会整体重试一次 */
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
            val retryable = last.error == "内容为空" || last.error == "页面渲染失败" || last.error == "页面布局异常"
            if (!retryable || attempt == 1) return last
            delay(300)
        }
        return last
    }

    private suspend fun exportOnce(
        context: Context,
        payload: Payload,
        widthPx: Int,
        format: Format,
        out: OutputStream
    ): Result = withContext(Dispatchers.Main) {
        val width = widthPx.coerceAtLeast(320)
        val activity = context as? Activity
        try {
            // ① 首选：可见页面 + PixelCopy（屏幕上真实的像素）
            var bmp = if (activity != null) captureVisible(activity, payload, width) else null
            // ② 兜底：离屏渲染（拿不到 Activity；或可见方案失败时再试一条路）
            if (bmp == null) bmp = captureOffscreen(context, payload, width)
            if (bmp == null) return@withContext Result(false, "内容为空")

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
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 首选方案：可见 overlay + PixelCopy
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun captureVisible(
        activity: Activity,
        payload: Payload,
        width: Int
    ): Bitmap? {
        val root = activity.window?.decorView as? ViewGroup ?: return null
        val overlay = FrameLayout(activity)
        var web: WebView? = null
        return try {
            val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            overlay.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            val webWrap = FrameLayout(activity)
            column.addView(webWrap, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            // 提示放在 WebView **之外**（不会被截进图里）
            column.addView(
                TextView(activity).apply {
                    text = payload.progress
                    gravity = Gravity.CENTER
                    setPadding(0, 16, 0, 16)
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            )
            root.addView(overlay, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))

            // 可见 overlay 用默认（硬件）渲染：这才是屏幕上真实呈现的路径
            web = newWebView(activity, software = false)
            webWrap.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            delay(150)   // 等 overlay 完成一次布局（拿到真实视口高度）
            val viewport = webWrap.height.takeIf { it > 200 } ?: 1200
            web.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(viewport, View.MeasureSpec.EXACTLY)
            )
            web.layout(0, 0, width, viewport)

            // 渲染页面并拿到内容高度
            val json = payloadJson(payload)
            val h1 = withTimeoutOrNull(12000) {
                awaitContentHeight(web, json, payload.fontScale)
            } ?: return null
            if (h1 <= 0) return null
            // ★ 图/字体是异步落位的（尤其是 base64 原题图）：**截图前再量一次、取最大值**，
            //   否则位图比真实内容矮 → 导出的图底部被切掉（真机反馈的截断就是这么来的）
            delay(250)
            val contentHeight = maxOf(h1, measureContentHeight(web) ?: h1)
            if (contentHeight > (width * MAX_HEIGHT_RATIO).toInt()) return null
            delay(120)

            // 逐带：JS 滚动 → 等滚动生效 + 重绘 → PixelCopy → 校验 →（不过就重试这一带）
            val full = Bitmap.createBitmap(width, contentHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(full)
            val loc = IntArray(2)
            var y = 0
            var prevFp = 0L
            while (y < contentHeight) {
                val bandH = minOf(viewport, contentHeight - y)
                var okBand = false
                var attempt = 0
                while (!okBand && attempt < BAND_RETRY) {
                    scrollPage(web, y)
                    web.getLocationInWindow(loc)
                    val bandBmp = Bitmap.createBitmap(width, bandH, Bitmap.Config.ARGB_8888)
                    val copied = pixelCopy(
                        activity,
                        android.graphics.Rect(loc[0], loc[1], loc[0] + width, loc[1] + bandH),
                        bandBmp
                    )
                    if (copied && hasInk(bandBmp)) {
                        val fp = fingerprint(bandBmp)
                        // 相邻两带完全相同 = 滚动没生效（会得到"第一屏重复/后面空白"的错图）
                        val dup = y > 0 && fp == prevFp
                        if (!dup) {
                            canvas.drawBitmap(bandBmp, 0f, y.toFloat(), null)
                            prevFp = fp
                            okBand = true
                        }
                    }
                    bandBmp.recycle()
                    attempt++
                    if (!okBand) delay(200)
                }
                if (!okBand) {
                    full.recycle()
                    return null
                }
                y += bandH
            }
            if (hasInk(full)) full else { full.recycle(); null }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { web?.let { w -> (w.parent as? ViewGroup)?.removeView(w) } }
            runCatching { (overlay.parent as? ViewGroup)?.removeView(overlay) }
            runCatching { web?.destroy() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 兜底：离屏渲染（无 Activity 时）
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun captureOffscreen(context: Context, payload: Payload, width: Int): Bitmap? {
        val host = (context as? Activity)?.window?.decorView as? ViewGroup
        var web: WebView? = null
        var attached = false
        return try {
            web = newWebView(context, software = true)
            if (host != null) {
                web.layoutParams = ViewGroup.LayoutParams(width, 1)
                // 隐身只能"移出屏幕"（**不能**用 alpha=0：软件图层会把 alpha 画进去 → 全透明）
                web.translationY = -10000f
                host.addView(web)
                attached = true
            }
            web.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1, View.MeasureSpec.EXACTLY)
            )
            web.layout(0, 0, width, 1)
            delay(60)
            val json = payloadJson(payload)
            val h = withTimeoutOrNull(12000) { awaitContentHeight(web, json, payload.fontScale) } ?: return null
            if (h <= 0) return null
            // 复核一次（图片/字体可能刚落地，高度会变大）→ 取最大值，避免截断
            delay(250)
            val hFinal = maxOf(h, measureContentHeight(web) ?: h)
            if (hFinal > (width * MAX_HEIGHT_RATIO).toInt()) return null
            val height = hFinal.coerceAtMost(30000)
            web.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            web.layout(0, 0, width, height)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            runCatching { web.draw(Canvas(bmp)) }
            if (hasInk(bmp)) return bmp
            bmp.recycle()
            // 兜底二：capturePicture
            runCatching {
                @Suppress("DEPRECATION")
                val pic = web.capturePicture()
                if (pic != null && pic.width > 0) {
                    val b2 = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    pic.draw(Canvas(b2))
                    if (hasInk(b2)) return b2
                    b2.recycle()
                }
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            if (attached) runCatching { host?.removeView(web) }
            runCatching { web?.destroy() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // WebView / 渲染 / 截图工具
    // ─────────────────────────────────────────────────────────────────────

    private fun newWebView(context: Context, software: Boolean): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        // 离屏绘制需要软件渲染（硬件加速的 WebView 往 Canvas 上画经常是空白）；
        // 可见 overlay 走 PixelCopy 时必须是**默认（硬件）渲染** —— 那是屏幕上真实呈现的路径。
        if (software) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(Color.WHITE)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }

    /**
     * 立刻向页面要一次"当前内容高度"（页面的 `dshContentHeight()` 会把页脚也算进去）。
     * 用于截图前复核 —— 图片/字体异步落位后高度会变大，取最大值才不会截断。
     */
    private suspend fun measureContentHeight(web: WebView): Int? =
        withTimeoutOrNull(1500) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    web.evaluateJavascript("(window.dshContentHeight ? dshContentHeight() : 0)") { v ->
                        val n = v?.trim()?.trim('"')?.toFloatOrNull()?.toInt()
                        if (cont.isActive) cont.resume(n)
                    }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }

    /** 加载 → 注入 payload → 等 JS 回调内容高度（页面里会等图片、KaTeX 与字体就绪） */
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
        main.postDelayed({ finish(null) }, 12500)
        web.loadUrl("file:///android_asset/export_render.html")
    }

    /**
     * 把页面滚到 [y]：**页面级 JS 滚动 + View 级滚动都发一次**，再轮询 `scrollY` 等它真的到位，
     * 最后留一点时间让这一帧画上去。
     * （只 scrollTo 不轮询的话，滚动可能还没生效就去拷屏 → 拷到上一带的内容或空白。）
     */
    private suspend fun scrollPage(web: WebView, y: Int) {
        runCatching { web.evaluateJavascript("window.scrollTo(0, $y);", null) }
        runCatching { web.scrollTo(0, y) }
        val deadline = SystemClock.uptimeMillis() + 700
        while (SystemClock.uptimeMillis() < deadline) {
            delay(40)
            val sy = runCatching { web.scrollY }.getOrDefault(y)
            if (kotlin.math.abs(sy - y) <= 2) break
        }
        delay(90)
    }

    /** PixelCopy（API 26+）：把窗口里某块矩形区域拷进 Bitmap */
    private suspend fun pixelCopy(activity: Activity, src: android.graphics.Rect, dest: Bitmap): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                android.view.PixelCopy.request(
                    activity.window, src, dest,
                    { result -> if (cont.isActive) cont.resume(result == android.view.PixelCopy.SUCCESS) },
                    Handler(Looper.getMainLooper())
                )
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(false)
            }
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

    /** 一带位图的"指纹"：稀疏采样 ~64 像素合成一个长整数（判断"两带是否完全相同"） */
    private fun fingerprint(b: Bitmap): Long {
        var acc = 1125899906842597L
        val stepX = (b.width / 8).coerceAtLeast(1)
        val stepY = (b.height / 8).coerceAtLeast(1)
        var x = 0
        while (x < b.width) {
            var y = 0
            while (y < b.height) {
                acc = acc * 31 + b.getPixel(x, y)
                y += stepY
            }
            x += stepX
        }
        return acc
    }

    /** 兜底：把长图按 A4 切片贴进 PDF */
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
