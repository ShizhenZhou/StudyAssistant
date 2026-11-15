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

/** Worker-thread operations. No screenshot conversion: imported pages keep their original text. */
internal object PdfDocumentSelection {
    fun pageCount(file: File): Int = renderer(file).use { pdf ->
        pdf.pageCount.also { if (it !in 1..1000) throw DocumentProblem("document.pdfPagesLimit") }
    }

    fun thumbnail(file: File, index: Int): Bitmap = renderer(file).use { pdf ->
        if (index !in 0 until pdf.pageCount) throw DocumentProblem("document.invalidSelection")
        pdf.openPage(index).use { page ->
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
