package com.zsz.studyassistant.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import com.zsz.studyassistant.data.*

/** Explicit API 36 media checks: artificial pixels only, no MainActivity, DB, keys or network. */
@RunWith(AndroidJUnit4::class)
class MediaViewerIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val green = Color.rgb(30, 170, 85)
    private val blue = Color.rgb(40, 100, 220)

    @Test fun pdfSourceHighlightsWrappedAndRotatedTextAndSupportsPinch() {
        check(context.packageName == "com.zsz.studyassistant.integration")
        val work = File(context.cacheDir, "pdf-preview-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val query = "Exact source sentence wrapped over two lines"
        try {
            val original = File(work, "source.pdf")
            val pdf = android.graphics.pdf.PdfDocument()
            try {
                repeat(3) { index ->
                    val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                    val paint = android.graphics.Paint().apply { textSize = 18f }
                    page.canvas.drawText("Exact source sentence", 40f, 90f, paint)
                    page.canvas.drawText("wrapped over two lines", 40f, 115f, paint)
                    page.canvas.drawText("Do not highlight this line", 40f, 150f, paint)
                    pdf.finishPage(page)
                }
                original.outputStream().use(pdf::writeTo)
            } finally { pdf.close() }
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
            val rotated = File(work, "rotated.pdf")
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(original).use { pdf ->
                pdf.getPage(1).rotation = 90
                pdf.getPage(2).rotation = 270
                pdf.save(rotated)
            }
            val metadata = ConversationDocument(DocumentStore.hash(rotated), "preview.pdf", rotated.length())
            var visible: PdfSourcePreview? = null
            repeat(3) { index ->
                val preview = PdfSourcePreview.load(rotated, PdfSourceMatch(metadata, index), query) {}
                assertEquals("two separate line bounds, page $index", 2, preview.highlights.size)
                // Each highlighted rectangle must cover actual glyphs rather than blank page space.
                for (rect in preview.highlights) {
                    var ink = 0
                    for (y in rect.top.toInt() until rect.bottom.toInt())
                        for (x in rect.left.toInt() until rect.right.toInt())
                            if (Color.red(preview.bitmap.getPixel(x, y)) < 100) ink++
                    assertTrue("bounds overlap text, page $index", ink > 100)
                }
                val marked = preview.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = android.graphics.Canvas(marked)
                val paint = android.graphics.Paint().apply { color = 0x66FFB300 }
                preview.highlights.forEach { canvas.drawRect(it, paint) }
                File(context.getExternalFilesDir(null), "pdf-source-page-$index.png").outputStream().use {
                    marked.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                marked.recycle()
                if (index == 0) visible = preview else preview.bitmap.recycle()
            }
            // A case-insensitive platform search must never highlight a different exact excerpt.
            val absent = PdfSourcePreview.load(rotated, PdfSourceMatch(metadata, 0), query.lowercase()) {}
            assertTrue(absent.highlights.isEmpty())
            absent.bitmap.recycle()
            ActivityScenario.launch(UpdatePageTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    CompositionLocalProvider(LocalStrings provides stringsFor(UiLang.EN), LocalUiLang provides UiLang.EN) {
                        MaterialTheme { PdfSourcePage(requireNotNull(visible), "Synthetic PDF page", Modifier.fillMaxSize()) }
                    }
                } }
                waitFor("100%")
                val page = bounds(waitFor("Synthetic PDF page"))
                pinchOut(page)
                awaitCondition("PDF pinch enlarges") { nodes("100%").isEmpty() }
                swipe(page, left = true)
                screenshot("ui-pdf-source-zoomed.png")
                tap(bounds(waitFor("Fit page")))
                waitFor("100%")
                tap(bounds(waitFor("Zoom in")))
                waitFor("150%")
                tap(bounds(waitFor("Zoom out")))
                waitFor("100%")
                screenshot("ui-pdf-source-fit.png")
            }
        } finally { work.deleteRecursively() }
    }

    @Test fun nativeGalleryOpensTappedPageSwipesAndKeepsZoomedPage() {
        check(context.packageName == "com.zsz.studyassistant.integration")
        val images = listOf(Color.rgb(220, 55, 65), green, blue).map(::solidImage)
        ActivityScenario.launch(UpdatePageTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalStrings provides stringsFor(UiLang.EN), LocalUiLang provides UiLang.EN) {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            ConversationInputBar(value = "", onValueChange = {}, images = images,
                                onRemoveImage = {}, onPickImages = {}, onSend = {}, busy = false,
                                hint = "Synthetic media fixture", sendLabel = "Send", imageDesc = "Fixture image",
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            } }
            awaitCondition("Three artificial thumbnails") { nodes("Fixture image").size == 3 }
            tap(bounds(nodes("Fixture image")[1]))
            waitFor("2 / 3")
            awaitCenterColor(green)
            SystemClock.sleep(400) // The dialog's first composition can precede its opening animation.
            screenshot("ui-media-native-second.png")

            val screen = screenBounds()
            swipe(screen, left = true)
            waitFor("3 / 3")
            awaitCenterColor(blue)
            SystemClock.sleep(400) // A visible blue centre does not yet mean the pager has settled.
            screenshot("ui-media-native-third.png")
            swipe(screen, left = false)
            waitFor("2 / 3")
            awaitCenterColor(green)

            pinchOut(screen)
            awaitCondition("Native pinch must enlarge") { displayedZoom() > 1.2f }
            swipe(screen, left = true)
            // Let an incorrect page-settle finish before checking: a transient count is insufficient.
            SystemClock.sleep(400)
            assertTrue("A zoomed drag must keep the second page", nodes("2 / 3").isNotEmpty())
            assertTrue("A drag must retain the zoom", displayedZoom() > 1.2f)
            swipe(screen, left = false)
            SystemClock.sleep(400)
            assertTrue("Reverse zoomed drag must also keep the page", nodes("2 / 3").isNotEmpty())
            screenshot("ui-media-native-zoomed.png")
            tap(bounds(waitFor("Close")))
            awaitCondition("Gallery closed") { nodes("2 / 3").isEmpty() && nodes("Fixture image").size == 3 }
        }
    }

    @Test fun svgViewerOpensZoomsClosesAndHandlesBack() {
        check(context.packageName == "com.zsz.studyassistant.integration")
        val diagram = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 200"><title>Artificial triangle</title><rect width="320" height="200" fill="#ffffff"/><path d="M40 170L160 30L280 170Z" fill="#dceeff" stroke="#2466aa" stroke-width="4"/><text x="160" y="125" text-anchor="middle" font-size="22">A = bh / 2</text></svg>"""
        val messages = listOf(ChatMsg("assistant", "Synthetic geometry\n\n```svg\n$diagram\n```", messageKey = "media-svg-fixture"))
        ActivityScenario.launch(UpdatePageTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalStrings provides stringsFor(UiLang.EN), LocalUiLang provides UiLang.EN) {
                    MaterialTheme { ConversationWebView(messages, Modifier.fillMaxSize()) }
                }
            } }
            var web: WebView? = null
            awaitCondition("Conversation WebView") {
                scenario.onActivity { web = findWebView(it.window.decorView) }
                web != null
            }
            val renderer = requireNotNull(web)
            awaitCondition("Sanitized diagram image decoded") {
                evaluate(renderer, "!!document.querySelector('.ai-diagram img') && document.querySelector('.ai-diagram img').naturalWidth > 0") == "true"
            }
            tapDom(renderer, ".ai-diagram img")
            awaitCondition("SVG overlay open") { evaluate(renderer, "!!document.querySelector('[role=dialog]')") == "true" }
            assertEquals("true", evaluate(renderer, "document.getElementById('out').inert && !document.querySelector('[role=dialog] svg,[role=dialog] script')"))
            tapDom(renderer, "[aria-label='Zoom in']")
            assertEquals("true", evaluate(renderer, "document.querySelector('[role=dialog] img').style.transform.includes('scale(1.5)')"))
            screenshot("ui-media-svg-zoomed.png", renderer)
            tapDom(renderer, "[aria-label='Close']")
            awaitCondition("SVG close button") { evaluate(renderer, "!document.querySelector('[role=dialog]') && !document.getElementById('out').inert") == "true" }

            tapDom(renderer, ".ai-diagram img")
            awaitCondition("SVG reopened") { evaluate(renderer, "!!document.querySelector('[role=dialog]')") == "true" }
            // Check the native BackHandler, not just its JavaScript close function.
            instrumentation.waitForIdleSync()
            SystemClock.sleep(150)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitCondition("Native back closes SVG without leaving the host") {
                evaluate(renderer, "!document.querySelector('[role=dialog]') && !!document.querySelector('.ai-diagram img')") == "true"
            }
            scenario.onActivity { activity ->
                assertFalse("Back must retain the media host", activity.isFinishing || activity.isDestroyed)
                assertTrue("Conversation must remain attached", renderer.isAttachedToWindow)
            }
            screenshot("ui-media-svg-closed.png", renderer)
        }
    }

    private fun solidImage(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        } finally { bitmap.recycle() }
    }

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(80)
        }
        screenshot("ui-media-failure.png")
        error(description)
    }

    private fun nodes(text: String): List<AccessibilityNodeInfo> = allNodes().filter {
        it.text?.toString() == text || it.contentDescription?.toString() == text
    }

    private fun allNodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser) add(node)
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        if (root?.packageName?.toString() == context.packageName) visit(root)
    }

    private fun waitFor(text: String): AccessibilityNodeInfo {
        awaitCondition("Missing media UI: $text") { nodes(text).isNotEmpty() }
        return nodes(text).first()
    }

    private fun displayedZoom(): Float = allNodes().mapNotNull {
        val text = it.text?.toString() ?: return@mapNotNull null
        if (!text.endsWith("×")) null else text.dropLast(1).replace(',', '.').toFloatOrNull()
    }.maxOrNull() ?: 0f

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun screenBounds() = Rect().also { instrumentation.uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
    private fun tap(rect: Rect) {
        val down = SystemClock.uptimeMillis()
        touch(down, MotionEvent.ACTION_DOWN, listOf(PointF(rect.exactCenterX(), rect.exactCenterY())))
        SystemClock.sleep(50)
        touch(down, MotionEvent.ACTION_UP, listOf(PointF(rect.exactCenterX(), rect.exactCenterY())))
        instrumentation.waitForIdleSync()
    }

    private fun swipe(rect: Rect, left: Boolean) {
        val start = rect.left + rect.width() * (if (left) .8f else .2f)
        val end = rect.left + rect.width() * (if (left) .2f else .8f)
        val y = rect.top + rect.height() * .45f
        val down = SystemClock.uptimeMillis()
        touch(down, MotionEvent.ACTION_DOWN, listOf(PointF(start, y)))
        for (step in 1..20) {
            SystemClock.sleep(16)
            touch(down, MotionEvent.ACTION_MOVE, listOf(PointF(start + (end - start) * step / 20f, y)))
        }
        touch(down, MotionEvent.ACTION_UP, listOf(PointF(end, y)))
        instrumentation.waitForIdleSync()
    }

    private fun pinchOut(rect: Rect) {
        val cx = rect.exactCenterX(); val cy = rect.top + rect.height() * .45f
        fun points(fraction: Float) = listOf(PointF(cx - rect.width() * fraction, cy), PointF(cx + rect.width() * fraction, cy))
        val down = SystemClock.uptimeMillis()
        touch(down, MotionEvent.ACTION_DOWN, points(.10f).take(1))
        touch(down, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), points(.10f))
        for (step in 1..20) {
            SystemClock.sleep(16)
            touch(down, MotionEvent.ACTION_MOVE, points(.10f + .18f * step / 20f))
        }
        touch(down, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), points(.28f))
        touch(down, MotionEvent.ACTION_UP, points(.28f).take(1))
        instrumentation.waitForIdleSync()
    }

    private fun touch(down: Long, action: Int, points: List<PointF>) {
        val properties = Array(points.size) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coordinates = points.map { point -> MotionEvent.PointerCoords().apply { x = point.x; y = point.y; pressure = 1f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, points.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { assertTrue("Touch injection rejected", instrumentation.uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }

    private fun awaitCenterColor(expected: Int) {
        awaitCondition("Visible gallery page color must match the selected image") {
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return@awaitCondition false
            val pixels = if (bitmap.config == Bitmap.Config.HARDWARE) requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) else bitmap
            try {
                val pixel = pixels.getPixel(pixels.width / 2, pixels.height / 2)
                kotlin.math.abs(Color.red(pixel) - Color.red(expected)) < 12 &&
                    kotlin.math.abs(Color.green(pixel) - Color.green(expected)) < 12 &&
                    kotlin.math.abs(Color.blue(pixel) - Color.blue(expected)) < 12
            } finally { if (pixels !== bitmap) pixels.recycle(); bitmap.recycle() }
        }
    }

    private fun screenshot(name: String, web: WebView? = null) {
        if (web != null) {
            val drawn = CountDownLatch(1)
            instrumentation.runOnMainSync {
                web.postVisualStateCallback(SystemClock.uptimeMillis(), object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        // The committed DOM is ready for the next draw. Wait through that frame
                        // before UiAutomation captures the compositor's screen buffer.
                        web.invalidate()
                        web.postOnAnimation { web.postOnAnimation { drawn.countDown() } }
                    }
                })
            }
            assertTrue("WebView screenshot frame timed out", drawn.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
        }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun evaluate(web: WebView, script: String): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue("JavaScript evaluation timed out", latch.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun tapDom(web: WebView, selector: String) {
        val expression = "(function(){var r=document.querySelector(" + org.json.JSONObject.quote(selector) +
            ").getBoundingClientRect();return [r.left,r.top,r.width,r.height,window.innerWidth];})()"
        val rect = JSONArray(requireNotNull(evaluate(web, expression)))
        val location = IntArray(2)
        var width = 0
        instrumentation.runOnMainSync { web.getLocationOnScreen(location); width = web.width }
        val ratio = width / rect.getDouble(4)
        val left = location[0] + rect.getDouble(0) * ratio
        val top = location[1] + rect.getDouble(1) * ratio
        tap(Rect(left.toInt(), top.toInt(), (left + rect.getDouble(2) * ratio).toInt(), (top + rect.getDouble(3) * ratio).toInt()))
    }
}
