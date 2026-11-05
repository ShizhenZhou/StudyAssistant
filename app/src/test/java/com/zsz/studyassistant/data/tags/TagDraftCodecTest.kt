package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Test

class TagDraftCodecTest {
    private val base = TagInventory(listOf(Tag(1, "one", 10), Tag(2, "two", 20)), emptyList())
    private fun document() = TagDraftDocument(fingerprint = base.fingerprint(), savedAt = 100,
        drafts = listOf(StoredTagDraft(TagMergeGroup(listOf(1), ""), true)))

    @Test fun incompleteManualDraftRoundTripsButCannotBypassPreview() {
        val restored = TagDraftCodec.decode(TagDraftCodec.encode(document()))
        assertEquals(document(), restored)
        assertTrue(TagDraftCodec.matches(restored, base))
        try { TagMergeEngine.preview(base, restored.drafts.map { it.group }); fail("Expected strict preview") }
        catch (e: TagMergeException) { assertEquals("tagMerge.invalidGroup", e.messageKey) }
    }
    @Test fun changedNamesAndAssociationsInvalidateRestoration() {
        val restored = document()
        assertFalse(TagDraftCodec.matches(restored, base.copy(tags = base.tags.map { it.copy(name = it.name + " changed") })))
        assertFalse(TagDraftCodec.matches(restored, base.copy(links = listOf(QuestionTag(QuestionIdentity.create(100), 1)))))
    }
    @Test fun reorderingDoesNotInvalidateDraft() {
        assertTrue(TagDraftCodec.matches(document(), base.copy(tags = base.tags.reversed())))
    }
    @Test fun unknownVersionAndMalformedFingerprintAreRejected() {
        for (bad in listOf(document().copy(version = 2), document().copy(fingerprint = "invalid"))) {
            try { TagDraftCodec.encode(bad); fail("Expected invalid draft") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun duplicateOrUnknownSourcesCannotBecomeRestoredSelections() {
        val duplicate = document().copy(drafts = listOf(StoredTagDraft(TagMergeGroup(listOf(1, 1), "one"))))
        try { TagDraftCodec.encode(duplicate); fail("Expected duplicate rejection") } catch (_: IllegalArgumentException) { }
        val unknown = document().copy(drafts = listOf(StoredTagDraft(TagMergeGroup(listOf(99), "one"))))
        assertFalse(TagDraftCodec.matches(unknown, base))
    }
    @Test fun preparedPlansAreNotStoredOrRestored() {
        val encoded = TagDraftCodec.encode(document())
        val extra = encoded.dropLast(1) + ",\"prepared\":{\"arbitrary\":true}}"
        assertEquals(document(), TagDraftCodec.decode(extra))
        assertFalse(TagDraftCodec.encode(TagDraftCodec.decode(extra)).contains("prepared"))
    }
}
