package com.zsz.studyassistant.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import org.json.JSONObject

/** A4, with a selectable prose layer over the separately rendered formulas and decorations. */
internal class ConversationPdfWriter : AutoCloseable {
    private val document = PdfDocument()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val fonts = mutableMapOf<Pair<Boolean, Int>, Typeface>()

    fun append(graphics: Bitmap, snapshot: JSONObject, number: Int, total: Int) {
        val width = snapshot.getDouble("width").toFloat()
        val height = snapshot.getDouble("height").toFloat()
        check(width.isFinite() && height.isFinite() && width > 0f && height > 0f)
        val runs = snapshot.getJSONArray("runs")
        val clip = snapshot.getJSONObject("clip")
        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create())
        try {
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            canvas.save()
            // Graphics, glyph positions and font sizes share ONE viewport-to-paper transform.
            // Do not fit the bitmap to the paper separately from the selectable text layer.
            canvas.scale(595f / width, 842f / height)
            canvas.drawBitmap(graphics, null, RectF(0f, 0f, width, height), null)
            canvas.clipRect(clip.getDouble("left").toFloat(), clip.getDouble("top").toFloat(),
                clip.getDouble("right").toFloat(), clip.getDouble("bottom").toFloat())
            for (i in 0 until runs.length()) {
                val run = runs.getJSONObject(i)
                val text = run.getString("text")
                val style = (if (run.getBoolean("bold")) Typeface.BOLD else 0) or
                    (if (run.getBoolean("italic")) Typeface.ITALIC else 0)
                val key = run.getBoolean("mono") to style
                paint.typeface = fonts.getOrPut(key) { Typeface.create(if (key.first) "monospace" else "sans-serif", style) }
                paint.textSize = run.getDouble("size").toFloat()
                paint.textScaleX = 1f
                paint.color = cssTextColor(run.getString("color"))
                val measured = paint.measureText(text)
                if (measured > 0f) {
                    // Keep each DOM line's horizontal extent; font fallback must not reflow the PDF.
                    paint.textScaleX = run.getDouble("width").toFloat() / measured
                    canvas.drawText(text, run.getDouble("x").toFloat(), run.getDouble("y").toFloat(), paint)
                }
            }
            canvas.restore()
            paint.typeface = Typeface.DEFAULT
            paint.textScaleX = 1f
            paint.textSize = 8.25f
            paint.color = Color.GRAY
            val label = "$number / $total"
            canvas.drawText(label, (595f - paint.measureText(label)) / 2f, 831f, paint)
        } finally { document.finishPage(page) }
    }

    fun writeTo(output: java.io.OutputStream) = document.writeTo(output)
    override fun close() = document.close()

    private fun cssTextColor(value: String): Int {
        val parts = value.substringAfter('(').substringBefore(')').split(',').map { it.trim().toFloatOrNull() }
        if (parts.size !in 3..4 || parts.any { it == null }) return Color.BLACK
        return Color.argb(((parts.getOrNull(3) ?: 1f) * 255).toInt().coerceIn(0, 255),
            parts[0]!!.toInt().coerceIn(0, 255), parts[1]!!.toInt().coerceIn(0, 255), parts[2]!!.toInt().coerceIn(0, 255))
    }
}
