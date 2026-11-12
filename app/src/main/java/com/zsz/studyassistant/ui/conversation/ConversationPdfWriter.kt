package com.zsz.studyassistant.ui

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.ReplacementSpan
import android.util.Base64
import android.util.LruCache
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import kotlin.math.*

/** One layout in paper units for text, inline formulas and frames. No screen coordinates. */
internal class ConversationPdfWriter : AutoCloseable {
    private companion object {
        const val U = 4f
        const val W = 595f * U
        const val H = 842f * U
        const val M = 24f * U
        const val PAD = 10f * U
        const val BOTTOM = H - 30f * U
        const val FONT = 12f * U
    }
    private val document = PdfDocument()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = FONT; color = Color.rgb(25,25,25) }
    private val images = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) { oldValue.recycle() }
    }
    private data class Frame(val top: Float, var bottom: Float, val user: Boolean)
    private val frames = mutableListOf<Frame>()
    private val operations = mutableListOf<(Canvas) -> Unit>()
    private var y = M
    private var pageCount = 0
    private var user = false
    private var frame: Frame? = null

    private fun bitmap(data: String): Bitmap {
        images.get(data)?.let { return it }
        require(data.startsWith("data:image/png;base64,"))
        val bytes = Base64.decode(data.substringAfter(','), Base64.DEFAULT)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth in 1..4096 && options.outHeight in 1..4096 && options.outWidth.toLong() * options.outHeight <= 4_000_000)
        val result = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        images.put(data, result)
        return result
    }

    private inner class FormulaSpan(val asset: JSONObject, val w: Float, val h: Float, val baseline: Float) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            fm?.let { it.ascent = -ceil(baseline).toInt(); it.top = it.ascent; it.descent = ceil(h-baseline).toInt(); it.bottom = it.descent }
            return ceil(w).toInt()
        }
        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            canvas.drawBitmap(bitmap(asset.getString("data")), null, RectF(x,y-baseline,x+w,y-baseline+h), this@ConversationPdfWriter.paint)
        }
    }

    private fun paragraph(block: JSONObject, width: Float): StaticLayout {
        val value = SpannableStringBuilder()
        val runs = block.getJSONArray("runs")
        for (i in 0 until runs.length()) {
            val run = runs.getJSONObject(i)
            val start = value.length
            val image = run.optJSONObject("image")
            if (image != null) {
                val scale = min(FONT/16f, (width-2f)/image.getDouble("width").toFloat())
                val w = image.getDouble("width").toFloat()*scale
                val h = image.getDouble("height").toFloat()*scale
                require(h < BOTTOM-M-2*PAD)
                value.append('\uFFFC')
                value.setSpan(FormulaSpan(image,w,h,image.getDouble("baseline").toFloat()*scale),start,value.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                value.append(run.optString("text"))
                val style = (if(run.optBoolean("bold")) Typeface.BOLD else 0) or (if(run.optBoolean("italic")) Typeface.ITALIC else 0)
                if(style != 0) value.setSpan(StyleSpan(style),start,value.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if(run.optBoolean("mono")) value.setSpan(TypefaceSpan("monospace"),start,value.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if(run.has("size")) value.setSpan(AbsoluteSizeSpan((run.getDouble("size")*U).roundToInt()),start,value.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return StaticLayout.Builder.obtain(value,0,value.length,textPaint,max(1,width.toInt()))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true).setLineSpacing(3f*U,1f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY).build()
    }

    private fun beginFrame() { frame = Frame(y,y,user).also(frames::add); y += PAD }
    private fun finishPage() {
        if(operations.isEmpty()) return
        check(++pageCount <= 200) { "PDF page limit" }
        frame?.bottom = min(BOTTOM,y+PAD)
        val page = document.startPage(PdfDocument.PageInfo.Builder(595,842,pageCount).create())
        try {
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE); canvas.save(); canvas.scale(1f/U,1f/U)
            for(f in frames) {
                val rect = RectF(M,f.top,W-M,max(f.top+PAD,f.bottom))
                paint.style = Paint.Style.FILL; paint.color = if(f.user) Color.rgb(225,242,223) else Color.rgb(245,245,245)
                canvas.drawRoundRect(rect,6f*U,6f*U,paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = .6f*U; paint.color = if(f.user) Color.rgb(140,178,138) else Color.rgb(175,175,175)
                canvas.drawRoundRect(rect,6f*U,6f*U,paint)
            }
            paint.style = Paint.Style.FILL
            operations.forEach { it(canvas) }
            paint.color=Color.GRAY;paint.textSize=9f*U;paint.typeface=Typeface.DEFAULT
            val label=pageCount.toString();canvas.drawText(label,(W-paint.measureText(label))/2,H-13f*U,paint)
            canvas.restore()
        } finally { document.finishPage(page) }
        operations.clear();frames.clear();frame=null;y=M
    }
    private fun reserve(height: Float): Float {
        require(height <= BOTTOM-M-2*PAD)
        if(y+height+PAD > BOTTOM) { finishPage();beginFrame() }
        val at=y;y+=height;frame?.bottom=y+PAD;return at
    }
    private fun background(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, quoted: Boolean) {
        if(quoted) {paint.color=Color.rgb(226,232,238);canvas.drawRect(left-4f*U,top,left+width+4f*U,top+height,paint)}
    }
    private suspend fun text(block: JSONObject, left: Float, width: Float, quoted: Boolean) {
        val layout=paragraph(block,width)
        for(line in 0 until layout.lineCount) {
            currentCoroutineContext().ensureActive()
            val top=layout.getLineTop(line);val bottom=layout.getLineBottom(line);val height=(bottom-top).toFloat()
            val at=reserve(height)
            operations.add { canvas ->
                background(canvas,left,at,width,height,quoted)
                canvas.save();canvas.clipRect(left,at,left+width,at+height);canvas.translate(left,at-top);layout.draw(canvas);canvas.restore()
            }
        }
        y=min(BOTTOM-PAD,y+3f*U)
    }
    private fun graphic(asset: JSONObject, left: Float, width: Float, formula: Boolean, quoted: Boolean) {
        val sourceWidth=asset.getDouble("width").toFloat();val sourceHeight=asset.getDouble("height").toFloat()
        require(sourceWidth>0 && sourceHeight>0)
        val scale=min(if(formula) FONT/16 else width/sourceWidth,min(width/sourceWidth,(BOTTOM-M-2*PAD-8f*U)/sourceHeight))
        val w=sourceWidth*scale;val h=sourceHeight*scale;val at=reserve(h+8f*U)
        operations.add { canvas ->
            background(canvas,left,at,width,h+8f*U,quoted)
            val x=left+(width-w)/2
            canvas.drawBitmap(bitmap(asset.getString("data")),null,RectF(x,at+4f*U,x+w,at+4f*U+h),paint)
        }
    }
    private suspend fun table(rows: JSONArray, left: Float, width: Float, quoted: Boolean) {
        val columns=(0 until rows.length()).maxOfOrNull { rows.getJSONArray(it).length() } ?: return
        require(columns in 1..40)
        val cellWidth=width/columns;val padding=4f*U
        for(r in 0 until rows.length()) {
            val row=rows.getJSONArray(r)
            val cells=(0 until columns).map { c ->
                val combined=JSONArray()
                fun append(items: JSONArray) {
                    for(i in 0 until items.length()) {
                        val item=items.getJSONObject(i)
                        when(item.getString("kind")) {
                            "text" -> {val runs=item.getJSONArray("runs");for(j in 0 until runs.length()) combined.put(runs.get(j));combined.put(JSONObject().put("text","\n"))}
                            "math","image" -> combined.put(JSONObject().put("image",item.getJSONObject("image").apply { if(!has("baseline")) put("baseline",getDouble("height")) }))
                            "quote","indent" -> append(item.getJSONArray("blocks"))
                        }
                    }
                }
                append(row.optJSONArray(c) ?: JSONArray())
                if (combined.length() > 0 && combined.getJSONObject(combined.length()-1).optString("text") == "\n") {
                    combined.remove(combined.length()-1)
                }
                paragraph(JSONObject().put("runs",combined),max(U,cellWidth-2*padding))
            }
            val rowHeight=(cells.maxOfOrNull { it.height } ?: 0)+2*padding
            if(rowHeight <= BOTTOM-M-2*PAD) {
                val at=reserve(rowHeight)
                cells.forEachIndexed { c,layout ->
                    val x=left+c*cellWidth
                    operations.add { canvas ->
                        background(canvas,x,at,cellWidth,rowHeight,quoted)
                        paint.style=Paint.Style.STROKE;paint.strokeWidth=.4f*U;paint.color=Color.LTGRAY
                        canvas.drawRect(x,at,x+cellWidth,at+rowHeight,paint);paint.style=Paint.Style.FILL
                        canvas.save();canvas.translate(x+padding,at+padding);layout.draw(canvas);canvas.restore()
                    }
                }
                continue
            }
            val offsets=IntArray(columns)
            while(offsets.indices.any { offsets[it]<cells[it].lineCount }) {
                currentCoroutineContext().ensureActive()
                val heights=cells.mapIndexed { c,l -> if(offsets[c]<l.lineCount) (l.getLineBottom(offsets[c])-l.getLineTop(offsets[c])).toFloat() else 0f }
                val height=(heights.maxOrNull()?:FONT)+padding*2;val at=reserve(height)
                for(c in 0 until columns) {
                    val layout=cells[c];val line=offsets[c];val x=left+c*cellWidth
                    operations.add { canvas ->
                        background(canvas,x,at,cellWidth,height,quoted)
                        paint.style=Paint.Style.STROKE;paint.strokeWidth=.4f*U;paint.color=Color.LTGRAY
                        canvas.drawRect(x,at,x+cellWidth,at+height,paint);paint.style=Paint.Style.FILL
                        if(line<layout.lineCount) {
                            canvas.save();canvas.clipRect(x+padding,at+padding,x+cellWidth-padding,at+height-padding)
                            canvas.translate(x+padding,at+padding-layout.getLineTop(line));layout.draw(canvas);canvas.restore()
                        }
                    }
                    if(line<layout.lineCount) offsets[c]++
                }
            }
        }
        y=min(BOTTOM-PAD,y+4f*U)
    }
    private suspend fun blocks(items: JSONArray, left: Float, width: Float, quoted: Boolean=false) {
        for(i in 0 until items.length()) {
            currentCoroutineContext().ensureActive()
            val block=items.getJSONObject(i)
            when(block.getString("kind")) {
                "text" -> text(block,left,width,quoted)
                "math","image" -> graphic(block.getJSONObject("image"),left,width,block.getString("kind")=="math",quoted)
                "table" -> table(block.getJSONArray("rows"),left,width,quoted)
                "quote","indent" -> blocks(block.getJSONArray("blocks"),left+8f*U,max(U,width-16f*U),quoted||block.getString("kind")=="quote")
                "rule" -> {val at=reserve(12f*U);operations.add { canvas ->paint.color=Color.LTGRAY;paint.strokeWidth=.5f*U;canvas.drawLine(left,at+6f*U,left+width,at+6f*U,paint)}}
            }
        }
    }
    suspend fun append(message: JSONObject) {
        currentCoroutineContext().ensureActive()
        user=message.getBoolean("user")
        if(y+2*PAD+FONT > BOTTOM) finishPage()
        beginFrame()
        blocks(message.getJSONArray("blocks"),M+PAD,W-2*(M+PAD))
        y+=PAD;frame?.bottom=y;frame=null;y+=8f*U
    }
    fun writeTo(output: OutputStream) { finishPage();check(pageCount>0);document.writeTo(output) }
    override fun close() { images.evictAll();document.close() }
}
