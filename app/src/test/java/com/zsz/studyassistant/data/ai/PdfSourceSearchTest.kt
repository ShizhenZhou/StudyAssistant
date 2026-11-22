package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Test

class PdfSourceSearchTest {
    @Test fun longScanReuses128PagesInsteadOfEvictingEveryUpcomingHit() {
        val session = PdfSourceSession()
        val pages = (0..128).toList()
        fun scan(): Int {
            val retained = session.retainedPages(listOf(document.copy(sha256 = "a", pages = null)))
            var extracted = 0
            for (page in pages) if (session.page("a", page) == null) {
                extracted++
                session.rememberPage("a", page, PdfSourceSession.Page("page$page", 8), retained)
            }
            return extracted
        }
        assertEquals(129, scan())
        assertEquals(1, scan())
        assertEquals(1, scan())
        // Protection is request-scoped: another document may use the bounded capacity.
        repeat(128) { session.rememberPage("b", it, PdfSourceSession.Page("other", 5)) }
        assertTrue(pages.all { session.page("a", it) == null })
    }

    @Test fun firstDocumentCannotEvictSecondDocumentsUpcomingHits() {
        val session = PdfSourceSession(maxPages = 2)
        val a = document.copy(pages = null)
        val b = a.copy(sha256 = "b".repeat(64))
        val page = PdfSourceSession.Page("cached", 6)
        repeat(2) { session.rememberPage(b.sha256, it, page) }
        val retained = session.retainedPages(listOf(a, b, b.copy(pages = listOf(0))))
        assertEquals(setOf(b.sha256 to 0, b.sha256 to 1), retained)
        repeat(2) { session.rememberPage(a.sha256, it, page, retained) }
        repeat(2) { assertEquals(page, session.page(b.sha256, it)) }
    }

    private val document = ConversationDocument("a".repeat(64), "fixture.pdf", 100, listOf(4, 0, 2))

    @Test fun sessionEvictsLeastRecentlyUsedPagesWithinCharacterAndPageLimits() {
        val session = PdfSourceSession(maxChars = 10, maxPages = 2)
        session.rememberPage("a", 0, PdfSourceSession.Page("aaaa", 8))
        session.rememberPage("a", 1, PdfSourceSession.Page("bbbb", 4))
        assertEquals(8, session.page("a", 0)?.extractedChars)
        session.rememberPage("b", 0, PdfSourceSession.Page("cc", 2))
        assertNull(session.page("a", 1))
        assertEquals("aaaa", session.page("a", 0)?.text)
        session.rememberPage("a", 2, PdfSourceSession.Page("123456789", 9))
        assertNull(session.page("a", 0))
        assertNull(session.page("b", 0))
        assertEquals("123456789", session.page("a", 2)?.text)
    }

    @Test fun disposedSessionClearsTextAndMetadataAndRejectsLateWorkerWrites() {
        val session = PdfSourceSession()
        session.rememberPageCount("a", 3)
        session.rememberPage("a", 0, PdfSourceSession.Page("source", 6))
        session.dispose()
        session.rememberPageCount("a", 3)
        session.rememberPage("a", 0, PdfSourceSession.Page("late", 4))
        assertNull(session.pageCount("a"))
        assertNull(session.page("a", 0))
    }

    @Test fun sourceContextExcludesFutureAndDisabledDocumentsButKeepsCurrentAttachments() {
        val newer = document.copy(sha256 = "b".repeat(64), name = "later.pdf")
        val items = listOf(
            com.zsz.studyassistant.ChatItem(0, "question", "q", messageKey = "q", documents = listOf(document)),
            com.zsz.studyassistant.ChatItem(1, "assistant", "a", messageKey = "a"),
            com.zsz.studyassistant.ChatItem(2, "user", "next", messageKey = "u", documents = listOf(newer),
                excludedDocumentHashes = listOf(document.sha256, newer.sha256)),
            com.zsz.studyassistant.ChatItem(3, "assistant", "next answer", messageKey = "b")
        )
        assertEquals(listOf(document), pdfDocumentsAtMessage(items, "a"))
        assertEquals(listOf(newer), pdfDocumentsAtMessage(items, "b"))
        assertTrue(pdfDocumentsAtMessage(items, "missing").isEmpty())
    }

    @Test fun selectedPageOrderIsOriginalOrderAndNeverRenumbersOriginals() {
        assertEquals(listOf(0, 2, 4), PdfSourceSearch.originalPages(document, 5))
        assertEquals(listOf(0, 1, 2), PdfSourceSearch.originalPages(document.copy(pages = null), 3))
        assertThrows(DocumentProblem::class.java) { PdfSourceSearch.originalPages(document, 4) }
        assertThrows(IllegalArgumentException::class.java) {
            PdfSourceSearch.originalPages(document.copy(pages = listOf(0, 0)), 5)
        }
    }

    @Test fun normalizationIgnoresLayoutButPreservesDifferentTextAndMath() {
        assertEquals(PdfSourceSearch.normalize("Café 的原文"), PdfSourceSearch.normalize("Cafe\u0301\n的\u00a0原 文"))
        assertNotEquals(PdfSourceSearch.normalize("x > 10; ABC"), PdfSourceSearch.normalize("x ≥ 10; ABC"))
        assertNotEquals(PdfSourceSearch.normalize("original text"), PdfSourceSearch.normalize("Original text"))
        assertNotEquals(PdfSourceSearch.normalize("This is correct."), PdfSourceSearch.normalize("This is incorrect."))
        assertThrows(DocumentProblem::class.java) { PdfSourceSearch.query("x = 1") }
        assertThrows(DocumentProblem::class.java) { PdfSourceSearch.query("x".repeat(2001)) }
        assertEquals("originaltext", PdfSourceSearch.query("original\n text"))
    }
}
