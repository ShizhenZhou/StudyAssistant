package com.zsz.studyassistant.data

import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class SearchSnippetTest {
    @Test fun shortProjectionMatchesUnicodeAndPrefersQuestion() = kotlinx.coroutines.runBlocking {
        val text = "😀".repeat(2040) + "Σİ"
        val hit = searchQuestionProjection(text, "σi answer", "σi") { error("Short fields must not be reread") }
        assertEquals(searchSnippet(text, "σi", false), hit)
        assertEquals(searchSnippet("answer Ω", "ω", true),
            searchQuestionProjection("question", "answer Ω", "ω") { error("Unexpected reread") })
    }

    @Test fun truncatedProjectionUsesOnlyFreshWholeQuestionResult() = kotlinx.coroutines.runBlocking {
        var rereads = 0
        val fresh = searchSnippet("current target question", "target", false)
        // A stale short answer must not override a match in the current long question.
        assertEquals(fresh, searchQuestionProjection("x".repeat(2049), "old target answer", "target") {
            rereads++
            fresh
        })
        // Even a prefix hit cannot be returned after a deletion or content change.
        assertNull(searchQuestionProjection("old target", "x".repeat(2049), "target") {
            rereads++
            null
        })
        assertEquals(2, rereads)
    }

    @Test fun cancelledChunkReaderCannotReturnAHit() = kotlinx.coroutines.runBlocking {
        var returned = false
        val job = launch {
            searchChunks("target", false) { _, _ ->
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
                "target"
            }
            returned = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(returned)
    }

    @Test fun cancelledShortProjectionCannotReturnAHit() = kotlinx.coroutines.runBlocking {
        var returned = false
        val job = launch {
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
            searchQuestionProjection("target", "answer", "target") { error("Unexpected reread") }
            returned = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(returned)
    }

    @Test fun queryLongerThanChunkStillMatchesWithBoundedSnippet() = kotlinx.coroutines.runBlocking {
        val query = "Ω".repeat(9000)
        val text = "x".repeat(8190) + query + "after"
        val hit = searchChunks(query.lowercase(), true) { offset, count ->
            text.drop(offset - 1).take(count)
        }
        assertEquals(searchSnippet(text, query.lowercase(), true), hit)
        assertTrue(hit!!.text.length <= 258)
    }

    @Test fun matchesIgnoreCaseAndMarksRepeatedHits() {
        val hit = searchSnippet("Alpha alpha ALPHA", "alpha", true)!!
        assertTrue(hit.answer)
        assertEquals(listOf(0..4, 6..10, 12..16), hit.highlights)
        assertNull(searchSnippet("alpha", "  ", false))
        assertNull(searchSnippet("alpha", "beta", false))
    }

    @Test fun longAnswerKeepsBoundedContextAroundLateMatch() {
        val hit = searchSnippet("前".repeat(10000) + "PN结" + "后".repeat(10000), "PN结", false)!!
        assertTrue(hit.text.startsWith("…"))
        assertTrue(hit.text.endsWith("…"))
        assertTrue(hit.text.length <= 102)
        assertEquals("PN结", hit.text.substring(hit.highlights.single()))
    }

    @Test fun truncationDoesNotSplitSurrogatePairOrExposeRawMarkup() {
        val hit = searchSnippet("x".repeat(10) + "😀" + "x".repeat(31) + "<script>\n", "<script>", false)!!
        assertFalse(hit.text[1].isLowSurrogate())
        assertTrue(hit.text.contains("<script>")) // Displayed as native Text, never HTML.
        assertFalse(hit.text.contains('\n'))
    }

    @Test fun oversizedQueryStillProducesBoundedHighlight() {
        val hit = searchSnippet("a".repeat(10000), "a".repeat(5000), false)!!
        assertTrue(hit.text.length <= 225)
        assertEquals(0..159, hit.highlights.first())
        assertTrue(hit.highlights.all { it.first >= 0 && it.last < hit.text.length })
    }
    @Test fun chunkedSearchPreservesUnicodeBoundariesAndFullTextContext() = kotlinx.coroutines.runBlocking {
        val prefix = "x".repeat(8188) + "😀"
        for (suffix in listOf("", "z".repeat(10000))) {
            val text = prefix + "Boundary" + suffix
            val actual = searchChunks("boundary", true) { offset, count ->
                val start = text.offsetByCodePoints(0, offset - 1)
                text.substring(start, text.offsetByCodePoints(start, minOf(count, text.codePointCount(start, text.length))))
            }
            assertEquals(searchSnippet(text, "boundary", true), actual)
        }
    }
}
