package com.zsz.studyassistant.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.Closeable

/** Worker-thread operations. No screenshot conversion: imported pages keep their original text. */
internal object PdfDocumentSelection {
    fun pageCount(file: File): Int = renderer(file).use { pdf ->
        pdf.pageCount.also { if (it !in 1..1000) throw DocumentProblem("document.pdfPagesLimit") }
    }

    fun thumbnail(file: File, index: Int): Bitmap = renderer(file).use { pdf -> render(pdf, index) }

    /** One dialog owns one session. Every open/render/close runs on IO and shares this monitor. */
    class Preview : Closeable {
        private var pdf: PdfRenderer? = null
        private var closed = false
        private val cache = linkedMapOf<Int, Bitmap>()

        @Synchronized
        fun open(file: File): Int {
            check(!closed && pdf == null)
            val opened = renderer(file)
            try {
                if (opened.pageCount !in 1..1000) throw DocumentProblem("document.pdfPagesLimit")
                pdf = opened
                return opened.pageCount
            } catch (error: Exception) { opened.close(); throw error }
        }

        @Synchronized
        fun thumbnail(index: Int, checkActive: () -> Unit): Bitmap {
            checkActive()
            val opened = pdf ?: throw DocumentProblem("document.pdfUnreadable")
            if (index !in 0 until opened.pageCount) throw DocumentProblem("document.invalidSelection")
            // Retain only already-visited adjacent pages, at most three thumbnails (about 11 MiB).
            // Published images must never be recycled while Compose may still draw an old frame.
            cache.keys.removeAll { it !in (index - 1)..(index + 1) }
            cache[index]?.let { return it }
            val bitmap = render(opened, index)
            try { checkActive() }
            catch (error: Exception) { bitmap.recycle(); throw error }
            cache[index] = bitmap
            return bitmap
        }

        @Synchronized
        override fun close() {
            closed = true
            cache.clear()
            val opened = pdf
            pdf = null
            opened?.close()
        }
    }

    private fun render(pdf: PdfRenderer, index: Int): Bitmap {
        if (index !in 0 until pdf.pageCount) throw DocumentProblem("document.invalidSelection")
        return pdf.openPage(index).use { page ->
            val scale = minOf(768f / page.width.coerceAtLeast(1), 1200f / page.height.coerceAtLeast(1))
            val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } catch (error: Exception) { bitmap.recycle(); throw error }
        }
    }

    private fun renderer(file: File): PdfRenderer {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try { return PdfRenderer(fd) }
        catch (error: Exception) { fd.close(); throw DocumentProblem("document.pdfUnreadable") }
    }

    /** Caller owns [destination]. All scratch data is bounded and deleted without touching originals. */
    fun writeSelected(context: Context, original: File, pages: List<Int>, destination: File, check: () -> Unit = {}) {
        require(pages.isNotEmpty() && pages.size <= 1000 && pages.distinct().size == pages.size)
        PDFBoxResourceLoader.init(context.applicationContext)
        val scratch = File(destination.parentFile, "scratch-${java.util.UUID.randomUUID()}")
        require(scratch.mkdirs())
        try {
            fun memory() = MemoryUsageSetting.setupMixed(8L * 1024 * 1024, 256L * 1024 * 1024).setTempDir(scratch)
            PDDocument.load(original, memory()).use { input ->
                if (input.numberOfPages !in 1..1000 || pages.any { it !in 0 until input.numberOfPages })
                    throw DocumentProblem("document.invalidSelection")
                if (!input.currentAccessPermission.canAssembleDocument()) throw DocumentProblem("document.pdfUnreadable")
                PDDocument(memory()).use { output ->
                    pages.sorted().forEach { index ->
                        check()
                        val source = input.getPage(index)
                        val copied = output.importPage(source)
                        copied.resources = source.resources
                        copied.cropBox = source.cropBox
                        copied.mediaBox = source.mediaBox
                        copied.rotation = source.rotation
                        // A selection is for analysis only; do not copy interactive actions or links.
                        copied.annotations = emptyList()
                    }
                    check()
                    output.save(destination)
                }
            }
            check()
            if (destination.length() !in 1..DocumentStore.MAX_BYTES) throw DocumentProblem("document.tooLarge")
        } finally {
            // This UUID directory belongs exclusively to this operation.
            scratch.deleteRecursively()
        }
    }
}
