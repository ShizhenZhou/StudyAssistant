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
        /** 导出过程中短暂显示在页面下方的一行字（例如"正在导出…"），跟随界面语言 */
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
     * 空白/布局异常会**自动重试一次**（换一种挂载方式），仍失败才回报失败 —— 绝不存一张空白图给用户。
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
            last = exportOnce(context, payload, widthPx, format, out, attach = attempt == 0)
            if (last.ok) return last
            // 只有"渲染类"失败值得重试（换挂载方式再试一次）；写文件失败重试也没用
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
        out: OutputStream,
        /** true = 挂到窗口上（拿 window token，最稳）；false = 完全离屏（换一种可能性） */
        attach: Boolean
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
            if (attach && host != null) {
                web.layoutParams = ViewGroup.LayoutParams(width, 1)
                // ⚠️ 隐身**只能靠移出屏幕，绝不能设 alpha=0**：软件图层（截图必需）的绘制会带上 alpha，
                //    alpha=0 → 截出来整张全透明 → 被"墨迹检查"判成空白 → 每次都提示导出失败（踩过）。
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
            //    先试"离屏绘制"（快、不闪屏）；画不出来（用户实测的"内容为空"）就退到
            //    "把导出页真的显示出来 + PixelCopy 从窗口拷贝"——那是屏幕上真实画出来的像素，最可靠。
            val band = screenBandHeight(context)
            var bmp = captureBitmap(web, width, height, band)
            if (bmp == null && activity != null) {
                bmp = captureWithPixelCopy(activity, web, width, height, band, payload.progress)
            }
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

    /**
     * **兜底截屏**：把导出页真的显示出来（铺一层临时 overlay），用 `PixelCopy` 从窗口逐带拷贝。
     *
     * 为什么需要它：`view.draw(Canvas)` 在某些机型/WebView 版本上就是画不出东西（用户实测"内容为空"），
     * 而 PixelCopy 拷的是**屏幕上真实呈现的像素**，只要它看得见就一定拷得到。
     * overlay 里只放 WebView + 底部一行提示（提示在 WebView 之外，因此不会被截进图里）。
     */
    private suspend fun captureWithPixelCopy(
        activity: Activity,
        web: WebView,
        width: Int,
        contentHeight: Int,
        bandHeight: Int,
        progress: String
    ): Bitmap? {
        val root = activity.window?.decorView as? ViewGroup ?: return null
        val overlay = FrameLayout(activity)
        return try {
            // ① 铺满全屏的临时 overlay：上面是导出页，下面一行提示
            root.addView(overlay, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            overlay.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            val webWrap = FrameLayout(activity)
            column.addView(webWrap, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            val tip = TextView(activity).apply {
                text = progress
                gravity = Gravity.CENTER
                setPadding(0, 12, 0, 12)
            }
            column.addView(tip, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            // ② 把 WebView 挪进 overlay（可见、无位移），按"可见视口"布局
            (web.parent as? ViewGroup)?.removeView(web)
            web.translationY = 0f
            web.alpha = 1f
            // ★ 这里必须**恢复成正常（硬件）渲染**：PixelCopy 拷的是屏幕上真实的呈现，
            //   而"软件图层"路径本身就是刚才画不出东西的那条路（见 captureBitmap 的注释）。
            //   屏上正常渲染是 App 里已经验证过没问题的（对话区 WebView 就是这么显示的）。
            web.setLayerType(View.LAYER_TYPE_NONE, null)
            webWrap.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            delay(300)   // 等 overlay 完成一次布局 + 网页按新渲染方式重绘（拿到真实视口高度）
            val viewport = webWrap.height.takeIf { it > 200 } ?: bandHeight
            web.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(viewport, View.MeasureSpec.EXACTLY)
            )
            web.layout(0, 0, width, viewport)
            delay(60)

            // ③ 逐带滚动 + PixelCopy，拼成整图
            val full = Bitmap.createBitmap(width, contentHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(full)
            val loc = IntArray(2)
            var y = 0
            var tailInk = true
            while (y < contentHeight) {
                scrollWeb(web, y)               // 等这一帧真的画上去（View 滚动 + 页面 JS 滚动都发一次）
                web.getLocationInWindow(loc)
                val bandH = minOf(viewport, contentHeight - y)
                val bandBmp = Bitmap.createBitmap(width, bandH, Bitmap.Config.ARGB_8888)
                val copied = pixelCopy(
                    activity,
                    android.graphics.Rect(loc[0], loc[1], loc[0] + width, loc[1] + bandH),
                    bandBmp
                )
                if (copied) canvas.drawBitmap(bandBmp, 0f, y.toFloat(), null)
                tailInk = copied && hasInk(bandBmp)   // 最后一带也要有内容，防"只截到第一屏"
                bandBmp.recycle()
                if (!copied) break
                y += bandH
            }
            scrollWeb(web, 0)
            if (hasInk(full) && tailInk) full else { full.recycle(); null }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { (web.parent as? ViewGroup)?.removeView(web) }
            runCatching { (overlay.parent as? ViewGroup)?.removeView(overlay) }
        }
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

    /** 分带截图的带高：不超过屏幕高度，也不超过 1600px（图层小、内存稳） */
    private fun screenBandHeight(context: Context): Int {
        val screenH = context.resources.displayMetrics.heightPixels
        return screenH.coerceIn(800, 1600)
    }

    /**
     * 把网页滚到 [y]：**View 级滚动 + 页面级 JS 滚动都做一遍**。
     * WebView 有时只认其中一种（尤其刚改过 layout/渲染方式之后），两个都发一次最省心。
     */
    private suspend fun scrollWeb(web: WebView, y: Int) {
        runCatching { web.scrollTo(0, y) }
        runCatching { web.evaluateJavascript("window.scrollTo(0, $y);", null) }
        delay(120)   // 等这一帧真正画上去（滚动后重绘是异步的）
    }

    /**
     * 把整页画进 Bitmap；每画完一种策略都检查"有没有墨迹"，全空白返回 null。
     *
     * 策略顺序（按成功率）：
     *  ① **分带绘制**：把视口缩到一个"带高"（≤1600px），逐带 `scrollTo` + `draw` 拼进整图。
     *     这样软件图层只需"屏宽 × 带高"（几 MB），不会像整页图层那样（20MB+）分配失败 → 什么都不画。
     *     ⚠️ 用户实测"内容为空"就是整页图层画不出来的表现（网页高度正常，但 draw 出来全空）。
     *  ② 整页高度直接 `draw`（老写法，保留作为兼容）。
     *  ③ `capturePicture()`（软件渲染的 WebView 把整页画进 Picture）。
     */
    private suspend fun captureBitmap(web: WebView, width: Int, height: Int, bandHeight: Int): Bitmap? {
        val h = height.coerceIn(1, 30000)
        // 先按整页高度布局一次：内容完整、可滚动（后面再按需要缩小视口）
        web.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        web.layout(0, 0, width, h)

        // ① 分带绘制
        // ⚠️ 关键教训（用户实测"只导出第一屏、下面全空白"）：软件图层下 `web.draw(canvas)` 是把
        //    **图层位图贴在画布原点**、**不理会我在画布上做的 translate** —— 所以不能"translate 后一次画进整图"
        //    （只有第一带会落在图上，其余全空）。正确做法：每带**单独画进一张带高的位图**，再由我把它
        //    `drawBitmap(band, 0, y)` 摆到整图的对应位置。
        run {
            val band = bandHeight.coerceIn(400, h)
            web.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(band, View.MeasureSpec.EXACTLY)
            )
            web.layout(0, 0, width, band)
            val full = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(full)
            var y = 0
            var tailInk = true
            var firstFp = 0L
            var duplicated = false
            var idx = 0
            while (y < h) {
                val bandH = minOf(band, h - y)
                scrollWeb(web, y)
                val bandBmp = Bitmap.createBitmap(width, bandH, Bitmap.Config.ARGB_8888)
                runCatching { web.draw(Canvas(bandBmp)) }
                canvas.drawBitmap(bandBmp, 0f, y.toFloat(), null)
                tailInk = hasInk(bandBmp)          // 最后一带也要有内容（页面底部有一行来源小字）
                val fp = fingerprint(bandBmp)
                if (idx == 0) firstFp = fp
                // 第 2 带与第 1 带**逐像素指纹相同** → 滚动没生效（会导出"第一屏重复/后面空白"）→ 判失败
                else if (idx == 1 && fp == firstFp) duplicated = true
                bandBmp.recycle()
                idx++
                y += bandH
            }
            scrollWeb(web, 0)
            // 只有"整图有墨迹 **且** 最后一带也有墨迹 **且** 没出现"滚动无效"才算成功
            if (hasInk(full) && tailInk && !duplicated) return full
            full.recycle()
        }

        // ② 整页高度直接 draw
        run {
            web.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            web.layout(0, 0, width, h)
            val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
            runCatching { web.draw(Canvas(bmp)) }
            if (hasInk(bmp)) return bmp
            bmp.recycle()
        }

        // ③ capturePicture 兜底
        runCatching {
            @Suppress("DEPRECATION")
            val pic = web.capturePicture()
            if (pic != null && pic.width > 0 && pic.height > 0) {
                val b2 = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                pic.draw(Canvas(b2))
                if (hasInk(b2)) return b2
                b2.recycle()
            }
        }
        return null
    }

    /**
     * 一带位图的"指纹"：稀疏采样 ~64 个像素合成一个长整数。
     * 用途：判断"滚动了但画面没变"（两带指纹完全相同）—— 那说明滚动/重绘没生效，
     * 直接判这次截图失败，免得导出"第一屏重复 + 后面空白"的错图。
     */
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
