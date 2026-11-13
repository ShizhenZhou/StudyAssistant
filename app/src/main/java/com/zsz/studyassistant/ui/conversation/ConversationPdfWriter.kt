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
    private val assets = HashMap<String, JSONObject>()
    private fun resolve(asset: JSONObject): JSONObject =
        if (asset.has("ref")) checkNotNull(assets[asset.getString("ref")]) { "Missing PDF formula" } else asset

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
            val image = run.optJSONObject("image")?.let(::resolve)
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
        val resolved = resolve(asset)
        val sourceWidth=resolved.getDouble("width").toFloat();val sourceHeight=resolved.getDouble("height").toFloat()
        require(sourceWidth>0 && sourceHeight>0)
        val scale=min(if(formula) FONT/16 else width/sourceWidth,min(width/sourceWidth,(BOTTOM-M-2*PAD-8f*U)/sourceHeight))
        val w=sourceWidth*scale;val h=sourceHeight*scale;val at=reserve(h+8f*U)
        operations.add { canvas ->
            background(canvas,left,at,width,h+8f*U,quoted)
            val x=left+(width-w)/2
            canvas.drawBitmap(bitmap(resolved.getString("data")),null,RectF(x,at+4f*U,x+w,at+4f*U+h),paint)
        }
    }
    private suspend fun table(rows: JSONArray, headerRows: Int, left: Float, width: Float, quoted: Boolean) {
        val columns=(0 until rows.length()).maxOfOrNull { rows.getJSONArray(it).length() } ?: return
        require(columns in 1..40)
        val cellWidth=width/columns;val padding=4f*U
        val pageHeight=BOTTOM-M-2*PAD
        fun layoutRow(r:Int):List<StaticLayout> {
            val row=rows.getJSONArray(r)
            return (0 until columns).map { c ->
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
        }
        fun remainingHeight(cells:List<StaticLayout>,offsets:IntArray)=
            cells.indices.maxOf { c -> val l=cells[c];if(offsets[c]<l.lineCount) l.getLineBottom(l.lineCount-1)-l.getLineTop(offsets[c]) else 0 }.toFloat()+2*padding
        fun minimumHeight(cells:List<StaticLayout>,offsets:IntArray)=
            cells.indices.maxOf { c -> val l=cells[c];if(offsets[c]<l.lineCount) l.getLineBottom(offsets[c])-l.getLineTop(offsets[c]) else 0 }.toFloat()+2*padding

        // One frame per cell/page fragment, with only complete StaticLayout lines
        // inside it. FormulaSpan contributes to line height, so formulas stay intact.
        fun drawFragment(cells:List<StaticLayout>,offsets:IntArray,budget:Float) {
            val starts=offsets.copyOf()
            val tops=IntArray(columns) { c -> cells[c].getLineTop(starts[c]) }
            val heights=IntArray(columns)
            for(c in cells.indices) {
                val layout=cells[c]
                while(offsets[c]<layout.lineCount && layout.getLineBottom(offsets[c])-tops[c]<=budget-2*padding) offsets[c]++
                if(offsets[c]>starts[c]) heights[c]=layout.getLineBottom(offsets[c]-1)-tops[c]
            }
            check(cells.indices.any { offsets[it]>starts[it] }) { "PDF table cannot fit a line" }
            val height=heights.max().toFloat()+2*padding
            val at=reserve(height)
            for(c in cells.indices) {
                val layout=cells[c];val x=left+c*cellWidth
                operations.add { canvas ->
                    background(canvas,x,at,cellWidth,height,quoted)
                    paint.style=Paint.Style.STROKE;paint.strokeWidth=.4f*U;paint.color=Color.LTGRAY
                    canvas.drawRect(x,at,x+cellWidth,at+height,paint);paint.style=Paint.Style.FILL
                    if(heights[c]>0) {
                        canvas.save();canvas.clipRect(x+padding,at+padding,x+cellWidth-padding,at+padding+heights[c])
                        canvas.translate(x+padding,at+padding-tops[c]);layout.draw(canvas);canvas.restore()
                    }
                }
            }
        }
        val headerCount=headerRows.coerceIn(0,rows.length())
        val headers=(0 until headerCount).map(::layoutRow)
        val headerHeight=headers.sumOf { remainingHeight(it,IntArray(columns)).toDouble() }.toFloat()
        fun repeatHeight(minimum:Float)=if(headerCount<rows.length() && headerHeight+minimum<=pageHeight) headerHeight else 0f
        fun nextPage(minimum:Float,repeat:Boolean) {
            require(minimum<=pageHeight) { "PDF table line exceeds page height" }
            finishPage();beginFrame()
            if(repeat && repeatHeight(minimum)>0f) {
                headers.forEach { drawFragment(it,IntArray(columns),remainingHeight(it,IntArray(columns))) }
            }
        }
        suspend fun drawRow(cells:List<StaticLayout>,repeat:Boolean,keepAfter:Float=0f,keepStart:Boolean=false) {
            val offsets=IntArray(columns)
            var firstFragment=true
            while(cells.indices.any { offsets[it]<cells[it].lineCount }) {
                currentCoroutineContext().ensureActive()
                val minimum=minimumHeight(cells,offsets)
                require(minimum<=pageHeight) { "PDF table line exceeds page height" }
                val freshHeight=pageHeight-if(repeat) repeatHeight(minimum) else 0f
                val remaining=remainingHeight(cells,offsets)
                // Keep a fitting row whole and the final header fragment with the
                // first body line. Oversized headers are rendered once, never looped.
                val keep=if(minimum+keepAfter<=freshHeight) keepAfter else 0f
                var available=BOTTOM-PAD-y
                if(available<minimum || (!(keepStart && firstFragment) && remaining+keep<=freshHeight && available<remaining+keep)) {
                    nextPage(minimum,repeat);available=BOTTOM-PAD-y
                }
                val budget=if(remaining<=available && remaining+keep>available && minimum+keep<=available) available-keep else available
                drawFragment(cells,offsets,budget)
                firstFragment=false
                if(cells.indices.any { offsets[it]<cells[it].lineCount }) nextPage(minimumHeight(cells,offsets),repeat)
            }
        }
        val firstBody=if(headerCount<rows.length()) layoutRow(headerCount) else null
        val firstMinimum=firstBody?.let { minimumHeight(it,IntArray(columns)) } ?: 0f
        if(headerCount>0 && headerHeight+firstMinimum<=pageHeight) {
            val firstHeight=firstBody?.let { remainingHeight(it,IntArray(columns)) } ?: 0f
            val together=headerHeight+if(headerHeight+firstHeight<=pageHeight) firstHeight else firstMinimum
            if(y+together+PAD>BOTTOM) nextPage(firstMinimum,false)
        }
        headers.forEachIndexed { index,cells -> drawRow(cells,false,if(index==headers.lastIndex) firstMinimum else 0f) }
        for(r in headerCount until rows.length()) drawRow(if(r==headerCount) firstBody!! else layoutRow(r),true,
            keepStart=r==headerCount && headerCount>0)
        y=min(BOTTOM-PAD,y+4f*U)
    }
    private suspend fun blocks(items: JSONArray, left: Float, width: Float, quoted: Boolean=false) {
        for(i in 0 until items.length()) {
            currentCoroutineContext().ensureActive()
            val block=items.getJSONObject(i)
            when(block.getString("kind")) {
                "text" -> text(block,left,width,quoted)
                "math","image" -> graphic(block.getJSONObject("image"),left,width,block.getString("kind")=="math",quoted)
                "table" -> table(block.getJSONArray("rows"),block.optInt("headerRows"),left,width,quoted)
                "quote","indent" -> blocks(block.getJSONArray("blocks"),left+8f*U,max(U,width-16f*U),quoted||block.getString("kind")=="quote")
                "rule" -> {val at=reserve(12f*U);operations.add { canvas ->paint.color=Color.LTGRAY;paint.strokeWidth=.5f*U;canvas.drawLine(left,at+6f*U,left+width,at+6f*U,paint)}}
            }
        }
    }
    suspend fun append(message: JSONObject) {
        currentCoroutineContext().ensureActive()
        message.optJSONArray("assets")?.let { incoming ->
            require(assets.size + incoming.length() <= 1000)
            for (index in 0 until incoming.length()) {
                val asset = incoming.getJSONObject(index)
                val id = asset.getString("id")
                require(!assets.containsKey(id)) { "Duplicate PDF formula" }
                assets[id] = asset
            }
        }
        user=message.getBoolean("user")
        if(y+2*PAD+FONT > BOTTOM) finishPage()
        beginFrame()
        blocks(message.getJSONArray("blocks"),M+PAD,W-2*(M+PAD))
        y+=PAD;frame?.bottom=y;frame=null;y+=8f*U
    }
    fun writeTo(output: OutputStream) { finishPage();check(pageCount>0);document.writeTo(output) }
    override fun close() { operations.clear();assets.clear();images.evictAll();document.close() }
}
