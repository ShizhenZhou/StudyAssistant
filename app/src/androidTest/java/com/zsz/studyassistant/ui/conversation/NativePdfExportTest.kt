package com.zsz.studyassistant.ui

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.content.ContentValues
import android.provider.MediaStore
import android.util.Base64
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zsz.studyassistant.MainActivity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Optional, synthetic PDF artifact check. Not a CI gate or part of a default device suite. */
@RunWith(AndroidJUnit4::class)
class NativePdfExportTest {
    @Test fun paginatesTablesWithHeadersAndWholeCellFragments() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".integration.pdfcheck") || context.packageName.endsWith(".integration"))
        fun pageTexts(file:File):List<String> = PdfRenderer(
            ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
        ).use { pdf ->
            assertTrue(pdf.pageCount in 2..30)
            val chunks=JSONArray()
            val texts=(0 until pdf.pageCount).map { index -> pdf.openPage(index).use { page ->
                assertEquals(595,page.width);assertEquals(842,page.height)
                val contents=page.textContents.map { it.text }
                chunks.put(JSONArray(contents))
                contents.joinToString("\n")
            } }
            File(context.filesDir,"${file.name}.text.json").writeText(chunks.toString(2))
            texts
        }
        // PDF text extraction may split a word into separate text blocks or
        // insert spacing between glyphs. Match every marker character, allowing
        // only extraction whitespace between them, and never join across pages.
        fun markerCount(pages:List<String>,marker:String):Int {
            val pattern=Regex(marker.map { Regex.escape(it.toString()) }.joinToString("[\\s\\p{Z}]*"))
            return pages.sumOf { pattern.findAll(it).count() }
        }
        val finished=CountDownLatch(1)
        var failure:Throwable?=null
        var started=false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val web=WebView(activity)
                configureConversationWebView(web)
                activity.setContentView(FrameLayout(activity).apply {
                    addView(web,FrameLayout.LayoutParams(360,1))
                })
                web.webViewClient=object:ConversationAssetClient() {
                    override fun onPageFinished(view:WebView?,url:String?) {
                        if(started)return
                        started=true
                        activity.lifecycleScope.launch {
                            try {
                                val rows=(1..75).joinToString("\n") { "| BODY${it.toString().padStart(3,'0')} | 中文 日本語 한국어 ${'$'}\\frac{x_$it+1}{2}${'$'} |" }
                                val huge=(1..160).joinToString(" ") { "CELL${it.toString().padStart(3,'0')} 中文长内容" }
                                val result=renderConversationExport(activity,web,listOf(ChatMsg("assistant",
                                    "| REPEATHEADER | VALUE |\n|---|---|\n$rows\n| $huge | ${'$'}\\frac{a+b}{c+d}${'$'} ROWEND |"
                                )),ConversationExportFormat.PDF,"native-table-pages") { _,_-> }
                                val target=File(context.filesDir,"native-table-pages.pdf")
                                result.files.single().copyTo(target,true)
                                result.directory.deleteRecursively()
                                val pages=pageTexts(target)
                                pages.forEach { assertEquals("Body page must repeat the real header",1,markerCount(listOf(it),"REPEATHEADER")) }
                                for(i in 1..75) assertEquals("BODY$i",1,markerCount(pages,"BODY${i.toString().padStart(3,'0')}"))
                                for(i in 1..160) assertEquals("CELL$i",1,markerCount(pages,"CELL${i.toString().padStart(3,'0')}"))
                                assertEquals("ROWEND",1,markerCount(pages,"ROWEND"))

                                // Native semantic fixtures cover absent headers and headers taller
                                // than a page without relying on unsupported raw HTML in Markdown.
                                fun cell(value:String)=JSONArray().put(JSONObject().put("kind","text")
                                    .put("runs",JSONArray().put(JSONObject().put("text",value))))
                                fun row(a:String,b:String)=JSONArray().put(cell(a)).put(cell(b))
                                suspend fun native(name:String,table:JSONObject):List<String> {
                                    val file=File(context.filesDir,"$name.pdf")
                                    ConversationPdfWriter().use { writer ->
                                        writer.append(JSONObject().put("user",false).put("blocks",JSONArray().put(table)))
                                        file.outputStream().use(writer::writeTo)
                                    }
                                    return pageTexts(file)
                                }
                                val noHeaderRows=JSONArray().put(row("FIRSTDATA","value"))
                                for(i in 1..100) noHeaderRows.put(row("PLAIN$i","ordinary data"))
                                val plain=native("native-table-no-header",JSONObject().put("kind","table").put("rows",noHeaderRows))
                                assertEquals("FIRSTDATA (see native-table-no-header.pdf.text.json)",1,markerCount(plain,"FIRSTDATA"))
                                val header=(1..100).joinToString("\n") { "TALLHEADER${it.toString().padStart(3,'0')}" }
                                val tall=native("native-table-tall-header",JSONObject().put("kind","table")
                                    .put("headerRows",1).put("rows",JSONArray().put(row(header,"heading"))
                                        .put(row("FIRSTBODY","first body after tall header"))))
                                for(i in 1..100) assertEquals("TALLHEADER$i",1,markerCount(tall,"TALLHEADER${i.toString().padStart(3,'0')}"))
                                assertEquals("Final header fragment must stay with first body",1,markerCount(listOf(tall.last()),"TALLHEADER100"))
                                assertEquals("First body must stay with final header fragment",1,markerCount(listOf(tall.last()),"FIRSTBODY"))
                            } catch(t:Throwable) { failure=t }
                            finally { web.destroy();finished.countDown() }
                        }
                    }
                }
                web.loadUrl(CONVERSATION_ASSET_URL)
            }
            assertTrue("Table PDF export timed out",finished.await(120,TimeUnit.SECONDS))
            failure?.let { throw AssertionError("Table PDF export failed",it) }
        }
    }

    @Test fun dialogSavesThroughDocumentsUiWithoutPrintSpooler() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(context.packageName.endsWith(".integration.pdfcheck") || context.packageName.endsWith(".integration"))
        val closed=CountDownLatch(1)
        var feedback=""
        val open=mutableStateOf(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme {
                    if(open.value) ConversationExportDialog(
                        listOf(ChatMsg("user","""已知 ${'$'}V_{GS}=3\,V${'$'}，求阈值。"""),ChatMsg("assistant","""结论：${'$'}V_T=2\,V${'$'}。\n\n\[I=\frac{\beta}{2}(V_{GS}-V_T)^2\]""")),
                        onDismiss={open.value=false;closed.countDown()},onFeedback={feedback=it})
                }
            } }
            fun find(timeout:Long,predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo {
                val end=SystemClock.uptimeMillis()+timeout
                fun walk(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
                    if(predicate(node)) return node
                    for(i in 0 until node.childCount) { node.getChild(i)?.let { walk(it)?.let { found->return found } } }
                    return null
                }
                while(SystemClock.uptimeMillis()<end) {
                    instrumentation.uiAutomation.rootInActiveWindow?.let { walk(it)?.let { found->return found } }
                    SystemClock.sleep(100)
                }
                throw AssertionError("Expected export UI node was not found")
            }
            fun click(node:AccessibilityNodeInfo) {
                var item:AccessibilityNodeInfo?=node
                while(item!=null) { if(item.isClickable) { check(item.performAction(AccessibilityNodeInfo.ACTION_CLICK));return };item=item.parent }
                error("Export UI node not clickable")
            }
            val input=find(15000) { it.className=="android.widget.EditText" }
            check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"Native-PDF-UI-${System.currentTimeMillis()}")
            }))
            click(find(5000) { it.text?.toString()=="导出" })
            val save=find(30000) { node ->
                val pkg=node.packageName?.toString().orEmpty()
                check(!pkg.contains("printspooler")) { "PDF opened Android print UI" }
                pkg.contains("documentsui") && node.text?.toString()?.lowercase() in listOf("save","保存")
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                File(context.filesDir,"pdf-save-picker.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
            }
            click(save)
            assertTrue("Save did not finish",closed.await(30,TimeUnit.SECONDS))
            assertEquals(Strings(emptyMap())["export.success"],feedback)
        }
    }

    @Test fun exportsSelectableMixedContentWithoutViewportCoordinates() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.zsz.studyassistant.integration" || context.packageName=="com.zsz.studyassistant.integration.pdfcheck")
        val finished=CountDownLatch(1)
        var failure: Throwable?=null
        var started=false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val web=WebView(activity)
                configureConversationWebView(web)
                web.setBackgroundColor(Color.WHITE)
                val host=FrameLayout(activity)
                host.addView(web,FrameLayout.LayoutParams(900,200))
                activity.setContentView(host)
                web.webViewClient=object:ConversationAssetClient() {
                    override fun onPageFinished(view:WebView?,url:String?) {
                        if(started)return
                        started=true
                        activity.lifecycleScope.launch {
                            try {
                                val paragraph="""
                                    ### SECTION
                                    结论：已知 ${'$'}V_{GS}=3\,V${'$'}，阈值 ${'$'}V_T=2\,V${'$'}。English text stays selectable.

                                    \[\boxed{I=\frac{\beta}{2}(V_{GS}-V_T)^2}\]

                                    | 项目 | 数值 |
                                    |---|---|
                                    | 中文 / English | ${'$'}x^2${'$'} |
                                    | 电流表达式需要保持在同一单元格内换行 | ${'$'}\frac{1+x}{2}${'$'} |

                                    1. 列表与 **加粗**、*斜体*。
                                    2. 第二项，继续内容。
                                """.trimIndent()
                                val messages=listOf(ChatMsg("user","""题目 BEGIN：求 ${'$'}\beta${'$'}。"""),
                                    ChatMsg("assistant",(1..12).joinToString("\n\n") { paragraph.replace("SECTION","SECTION $it") }+"\n\nEND OF ANSWER"),
                                    ChatMsg("user","请解释上面的公式。",quotedText="""引用 ${'$'}V_T=2\,V${'$'}"""))
                                suspend fun export(input:List<ChatMsg>,name:String,format:ConversationExportFormat=ConversationExportFormat.PDF):ConversationExportResult {
                                    val result=renderConversationExport(activity,web,input,format,name) { _,_-> }
                                    result.files.forEach { file ->
                                        val target=File(context.filesDir,file.name)
                                        file.copyTo(target,true)
                                        assertTrue(target.length()>1000)
                                    }
                                    return result
                                }
                                val proof=export(messages,"native-proof")
                                // Exercise the same ContentResolver write/read/hash path used by SAF.
                                val values=ContentValues().apply {
                                    put(MediaStore.Downloads.DISPLAY_NAME,"native-pdf-check.pdf")
                                    put(MediaStore.Downloads.MIME_TYPE,"application/pdf")
                                    put(MediaStore.Downloads.IS_PENDING,1)
                                }
                                val uri=checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
                                try { saveConversationExport(context,proof,uri) }
                                finally { context.contentResolver.delete(uri,null,null) }
                                proof.directory.deleteRecursively()

                                // Production PDF renderer is hidden and one dp tall. Viewport/font zoom
                                // must not change page layout or the relative size of formulas and text.
                                web.layoutParams=FrameLayout.LayoutParams(360,1)
                                web.alpha=0f
                                web.settings.textZoom=200
                                delay(100)
                                export(messages,"native-narrow").directory.deleteRecursively()
                                web.settings.textZoom=100

                                val bitmap=Bitmap.createBitmap(640,320,Bitmap.Config.ARGB_8888)
                                Canvas(bitmap).apply {
                                    drawColor(Color.rgb(220,235,250))
                                    val pen=Paint().apply { color=Color.BLUE;strokeWidth=4f;textSize=32f }
                                    drawLine(35f,270f,610f,270f,pen);drawLine(35f,270f,35f,25f,pen)
                                    drawLine(35f,250f,600f,45f,pen);drawText("IMAGE MARKER",120f,80f,pen)
                                }
                                val bytes=ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
                                bitmap.recycle()
                                val rows=(1..80).joinToString("\n") { "| ROW $it | 中文 table content ${'$'}x_{$it}^2${'$'} |" }
                                val huge=(1..180).joinToString(" ") { "CELL$it 中文长内容" }
                                val wide="\\["+(1..45).joinToString("+") { "x_{$it}" }+"\\]"
                                val diagram="```svg\n<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"400\" height=\"160\"><path d=\"M10 140 L390 140 M20 150 L20 10 M20 130 L350 30\" stroke=\"blue\" fill=\"none\"/><text x=\"40\" y=\"30\">SVG MARKER</text></svg>\n```"
                                val edges=listOf(
                                    ChatMsg("user","IMAGE START",images=listOf(Base64.encodeToString(bytes,Base64.NO_WRAP))),
                                    ChatMsg("assistant","多语言 日本語 한국어 English\n\n$wide\n\n$diagram\n\n| A | B |\n|---|---|\n$rows\n\n| LONG ROW | value |\n|---|---|\n| $huge | END CELL |\n\nEDGE END"))
                                export(edges,"native-edges").directory.deleteRecursively()

                                // A cancelled export must clean its partial private output.
                                val root=File(context.cacheDir,"conversation-export")
                                val before=root.list()?.toSet().orEmpty()
                                coroutineScope {
                                    val job=launch { renderConversationExport(activity,web,edges,ConversationExportFormat.PDF,"cancelled") { _,_-> } }
                                    yield();job.cancelAndJoin()
                                }
                                assertEquals(before,root.list()?.toSet().orEmpty())

                                web.layoutParams=FrameLayout.LayoutParams(900,1600)
                                web.alpha=1f
                                delay(100)
                                export(messages.take(1),"png-proof",ConversationExportFormat.PNG).directory.deleteRecursively()
                            } catch(t:Throwable) { failure=t }
                            finally { web.destroy();finished.countDown() }
                        }
                    }
                }
                web.loadUrl(CONVERSATION_ASSET_URL)
            }
            assertTrue("PDF export timed out",finished.await(300,TimeUnit.SECONDS))
            failure?.let { throw AssertionError("PDF export failed",it) }
        }
    }
}
