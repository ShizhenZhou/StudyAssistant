package com.zsz.studyassistant.data

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic fixture only; optional targeted device verification, no real user PDFs or API. */
@RunWith(AndroidJUnit4::class)
class PdfSourceSearchIntegrationTest {
    @Test fun selectedPdfFindsOriginalPagesAndVerifiesBeforePreview() {
        val context = ApplicationProvider.getApplicationContext<Context>().also {
            check(it.packageName == "com.zsz.studyassistant.integration")
        }
        val work = File(context.cacheDir, "source-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val original = File(work, "fixture.pdf")
            val pdf = PdfDocument()
            try {
                repeat(3) { index ->
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                    page.canvas.drawText("Exact source sentence", 30f, 70f, Paint().apply { textSize = 18f })
                    pdf.finishPage(page)
                }
                original.outputStream().use(pdf::writeTo)
            } finally { pdf.close() }
            val metadata = ConversationDocument(DocumentStore.hash(original), "fixture.pdf", original.length(), listOf(2, 0))
            val store = DocumentStore(context)
            store.install(original, metadata)
            val matches = PdfSourceSearch.find(store, listOf(metadata), "Exact source sentence")
            assertEquals(listOf(0, 2), matches.map { it.pageIndex })
            assertTrue(PdfSourceSearch.find(store, listOf(metadata), "Another source sentence").isEmpty())
            val preview = PdfDocumentSelection.thumbnail(store.verifiedFile(metadata), matches.last().pageIndex)
            assertTrue(preview.width > 0)
            preview.recycle()
            assertEquals(metadata.sha256, DocumentStore.hash(original))
            val wrong = metadata.copy(sha256 = "a".repeat(64))
            assertThrows(DocumentProblem::class.java) { PdfSourceSearch.find(store, listOf(wrong), "Exact source sentence") }
            val fakeHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(UUID.randomUUID().toString().toByteArray()).joinToString("") { "%02x".format(it) }
            val damaged = File(store.directory, fakeHash)
            check(!damaged.exists())
            try {
                original.copyTo(damaged)
                val error = assertThrows(DocumentProblem::class.java) {
                    PdfSourceSearch.find(store, listOf(metadata.copy(sha256 = fakeHash)), "Exact source sentence")
                }
                assertEquals("document.damaged", error.messageKey)
            } finally { damaged.delete() }
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                PdfSourceSearch.find(store, listOf(metadata), "Exact source sentence") {
                    throw kotlinx.coroutines.CancellationException("synthetic cancellation")
                }
            }
            assertTrue(store.selectionDirectory.listFiles().orEmpty().none { it.name.startsWith("source-") })
        } finally { work.deleteRecursively() }
    }
}
