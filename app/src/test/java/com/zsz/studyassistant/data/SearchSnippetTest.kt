package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Test

class SearchSnippetTest {
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
}
