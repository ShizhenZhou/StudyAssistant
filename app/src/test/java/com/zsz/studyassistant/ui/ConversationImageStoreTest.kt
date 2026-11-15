package com.zsz.studyassistant.ui

import org.junit.Assert.*
import org.junit.Test

class ConversationImageStoreTest {
    @Test fun equalImagesKeepTheirHandleAndRemovedImagesBecomeInaccessible() {
        val store = ConversationImageStore()
        val encoded = "aW1hZ2U="
        val original = ChatMsg("user", "question", listOf(encoded))
        val url = store.messages(listOf(original)).single().images.single()
        assertTrue(url.startsWith("file:///android_asset/session-image/"))
        assertFalse(url.contains(encoded))
        assertEquals(encoded, store.original(url))
        val equalCopy = original.copy(images = listOf(String(encoded.toCharArray())))
        assertEquals(url, store.messages(listOf(equalCopy)).single().images.single())
        assertEquals(listOf(encoded), original.images) // Persistence/export input is never rewritten.
        store.messages(emptyList())
        assertNull(store.original(url))
    }

    @Test fun imageHandlesCannotCrossRenderersOrSurviveRelease() {
        val first = ConversationImageStore()
        val second = ConversationImageStore()
        val message = ChatMsg("user", "question", listOf("aW1hZ2U="))
        val url = first.messages(listOf(message)).single().images.single()
        val other = second.messages(listOf(message)).single().images.single()
        assertNotEquals(url, other)
        assertNull(second.original(url))
        assertNull(first.original("file:///data/user/0/private"))
        first.clear()
        assertNull(first.original(url))
    }
}
