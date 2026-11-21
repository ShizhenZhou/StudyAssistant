package com.zsz.studyassistant.data

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.Writer
import java.text.Normalizer
import java.util.UUID

/** A local text match, never a model citation or a claim that the answer is supported. */
internal data class PdfSourceMatch(val document: ConversationDocument, val pageIndex: Int)

internal data class PdfSourceProgress(
    val documentName: String, val documentNumber: Int, val documentCount: Int,
    val completedPages: Int = 0, val pageCount: Int = 0, val originalPage: Int? = null
)

internal object PdfSourceSearch {
    const val MAX_QUERY = 2000
    private const val MAX_PAGE_CHARS = 200_000
    private const val MAX_TOTAL_CHARS = 2_000_000

    // Ignore layout whitespace only; keep punctuation, case and mathematical symbols significant.
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
        .filterNot { it.isWhitespace() || Character.isSpaceChar(it) }

    fun query(text: String): String {
        if (text.length > MAX_QUERY) throw DocumentProblem("pdfSource.queryInvalid")
        return normalize(text).also {
            if (it.length < 8 || it.count { c -> c.isLetterOrDigit() } < 4)
                throw DocumentProblem("pdfSource.queryInvalid")
        }
    }

    /** Upload order is sorted; returned indices always address the immutable original. */
    fun originalPages(document: ConversationDocument, count: Int): List<Int> {
        validateDocument(document)
        if (!document.name.endsWith(".pdf", ignoreCase = true) || count !in 1..1000)
            throw DocumentProblem("document.invalidSelection")
        return (document.pages?.sorted() ?: (0 until count).toList()).also { pages ->
            if (pages.any { it !in 0 until count }) throw DocumentProblem("document.invalidSelection")
        }
    }

    /** Worker-thread only. Bounded extraction; optional cache lasts only for the current conversation. */
    fun find(store: DocumentStore, documents: List<ConversationDocument>, text: String,
        session: PdfSourceSession? = null, onProgress: (PdfSourceProgress) -> Unit = {},
        check: () -> Unit = {}): List<PdfSourceMatch> {
        val needle = query(text)
        val pdfs = documents.filter { it.name.endsWith(".pdf", ignoreCase = true) }
            .distinctBy { it.requestKey }
        if (pdfs.size > 20) throw DocumentProblem("document.tooMany")
        PDFBoxResourceLoader.init(store.context)
        require(store.selectionDirectory.isDirectory || store.selectionDirectory.mkdirs())
        val scratch = File(store.selectionDirectory, "source-${UUID.randomUUID()}")
        require(scratch.mkdir())
        var total = 0
        val matches = mutableListOf<PdfSourceMatch>()
        try {
            for ((documentIndex, document) in pdfs.withIndex()) {
                check()
                onProgress(PdfSourceProgress(document.name, documentIndex + 1, pdfs.size))
                // Always hash the complete original, even if every selected page is cached.
                val file = store.verifiedFile(document, check)
                var opened: PDDocument? = null
                fun open(): PDDocument {
                    opened?.let { return it }
                    check()
                    val memory = MemoryUsageSetting.setupMixed(8L * 1024 * 1024, 256L * 1024 * 1024).setTempDir(scratch)
                    val pdf = PDDocument.load(file, memory)
                    opened = pdf
                    if (!pdf.currentAccessPermission.canExtractContent()) throw DocumentProblem("document.pdfUnreadable")
                    return pdf
                }
                try {
                    val count = session?.pageCount(document.sha256) ?: open().numberOfPages
                    val pages = originalPages(document, count)
                    check()
                    session?.rememberPageCount(document.sha256, count)
                    val retained = session?.retainedPages(document.sha256, pages).orEmpty()
                    var stripper: PDFTextStripper? = null
                    for ((pageOffset, page) in pages.withIndex()) {
                        check()
                        onProgress(PdfSourceProgress(document.name, documentIndex + 1, pdfs.size,
                            pageOffset, pages.size, page + 1))
                        val cached = session?.page(document.sha256, page)
                        val normalized = if (cached != null) {
                            total += cached.extractedChars
                            if (total > MAX_TOTAL_CHARS) throw DocumentProblem("pdfSource.tooLarge")
                            cached.text
                        } else {
                            val pdf = open()
                            val extractor = stripper ?: PDFTextStripper().also { stripper = it }
                            val out = StringBuilder()
                            val writer = object : Writer() {
                                override fun write(chars: CharArray, offset: Int, length: Int) {
                                    check()
                                    total += length
                                    if (out.length + length > MAX_PAGE_CHARS || total > MAX_TOTAL_CHARS)
                                        throw DocumentProblem("pdfSource.tooLarge")
                                    out.append(chars, offset, length)
                                }
                                override fun flush() {}
                                override fun close() {}
                            }
                            extractor.startPage = page + 1
                            extractor.endPage = page + 1
                            extractor.writeText(pdf, writer)
                            normalize(out.toString()).also {
                                check()
                                session?.rememberPage(document.sha256, page, PdfSourceSession.Page(it, out.length), retained)
                            }
                        }
                        if (normalized.contains(needle)) {
                            matches += PdfSourceMatch(document, page)
                            if (matches.size > 100) throw DocumentProblem("pdfSource.tooManyMatches")
                        }
                        check()
                        onProgress(PdfSourceProgress(document.name, documentIndex + 1, pdfs.size,
                            pageOffset + 1, pages.size, page + 1))
                    }
                } finally { opened?.close() }
            }
            return matches.distinctBy { it.document.sha256 to it.pageIndex }
        } finally { scratch.deleteRecursively() }
    }
}
