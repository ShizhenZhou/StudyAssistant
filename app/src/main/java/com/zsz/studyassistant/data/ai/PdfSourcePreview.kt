package com.zsz.studyassistant.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import java.io.File
import kotlinx.coroutines.CancellationException

/** One rendered page, with bounds expressed in bitmap pixels. No persistent text or image cache. */
internal data class PdfSourcePreview(val bitmap: Bitmap, val highlights: List<RectF>) {
    companion object {
        fun load(file: File, match: PdfSourceMatch, query: String, check: () -> Unit): PdfSourcePreview {
            check()
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try { PdfRenderer(fd) } catch (e: Exception) { fd.close(); throw e }
            return renderer.use { pdf ->
                if (match.pageIndex !in PdfSourceSearch.originalPages(match.document, pdf.pageCount))
                    throw DocumentProblem("document.invalidSelection")
                pdf.openPage(match.pageIndex).use { page ->
                    // At most 2048 × 3072 pixels, sufficient for magnification without retaining
                    // full-resolution bitmaps for every search result.
                    val scale = minOf(2048f / page.width.coerceAtLeast(1), 3072f / page.height.coerceAtLeast(1))
                    val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        check()
                        val bounds = if (Build.VERSION.SDK_INT >= 35) try {
                            findBounds(page, query, check)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { emptyList() } else emptyList()
                        val sx = bitmap.width.toFloat() / page.width
                        val sy = bitmap.height.toFloat() / page.height
                        check()
                        PdfSourcePreview(bitmap, bounds.map { RectF(it.left * sx, it.top * sy, it.right * sx, it.bottom * sy) })
                    } catch (e: Exception) { bitmap.recycle(); throw e }
                }
            }
        }

        @RequiresApi(35)
        private fun findBounds(page: PdfRenderer.Page, query: String, check: () -> Unit): List<RectF> {
            val needle = PdfSourceSearch.query(query)
            val term = query.trim().replace(Regex("[\\s\\p{Z}]+"), " ")
            val matches = page.searchText(term)
            if (matches.size > 100) return emptyList()
            val bounds = mutableListOf<RectF>()
            for (match in matches) {
                check()
                // Native search may ignore case. Confirm the full selected text before using its
                // bounds; allow line separators in the character stream, but no partial matches.
                var end = match.textStartIndex + needle.codePointCount(0, needle.length)
                var exact = false
                for (attempt in 0 until 8) {
                    check()
                    val selected = page.selectContent(SelectionBoundary(match.textStartIndex), SelectionBoundary(end)) ?: break
                    val text = PdfSourceSearch.normalize(selected.selectedTextContents.joinToString("") { it.text })
                    if (text == needle) { exact = true; break }
                    if (text.length >= needle.length || !needle.startsWith(text)) break
                    end += (needle.length - text.length).coerceAtLeast(1)
                }
                if (!exact) continue
                for (rect in match.bounds) {
                    if (!listOf(rect.left, rect.top, rect.right, rect.bottom).all { it.isFinite() } ||
                        rect.isEmpty || rect.left < 0f || rect.top < 0f ||
                        rect.right > page.width || rect.bottom > page.height) return emptyList()
                    bounds += RectF(rect)
                    if (bounds.size > 1000) return emptyList()
                }
            }
            return bounds
        }
    }
}
