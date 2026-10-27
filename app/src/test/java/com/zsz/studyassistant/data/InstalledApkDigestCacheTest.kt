package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstalledApkDigestCacheTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun repeatedUpdatePollingReadsTheInstalledPackageOnlyOnce() {
        val file = folder.newFile("installed.apk").apply { writeText("synthetic package") }
        val cache = InstalledApkDigestCache()
        var reads = 0
        repeat(120) { assertEquals("synthetic package", cache.read(file, 100) { reads++; it.readText() }) }
        assertEquals(1, reads)
    }
    @Test fun installationIdentityInvalidatesEvenWhenLengthAndTimestampStayTheSame() {
        val file = folder.newFile("installed.apk").apply { writeText("old") }
        val cache = InstalledApkDigestCache()
        var reads = 0
        assertEquals("old", cache.read(file, 100) { reads++; it.readText() })
        val stamp = file.lastModified()
        file.writeText("new")
        assertTrue(file.setLastModified(stamp))
        assertEquals("new", cache.read(file, 101) { reads++; it.readText() })
        assertEquals(2, reads)
    }
    @Test fun pathAndLengthChangesCannotReuseAStaleDigest() {
        val first = folder.newFile("first.apk").apply { writeText("first") }
        val second = folder.newFile("second.apk").apply { writeText("other") }
        val cache = InstalledApkDigestCache()
        assertEquals("first", cache.read(first, 100) { it.readText() })
        assertEquals("other", cache.read(second, 100) { it.readText() })
        val stamp = second.lastModified()
        second.writeText("larger replacement")
        assertTrue(second.setLastModified(stamp))
        assertEquals("larger replacement", cache.read(second, 100) { it.readText() })
    }
    @Test fun transientReadFailureIsRetriedRatherThanCachedForever() {
        val file = folder.newFile("installed.apk")
        val cache = InstalledApkDigestCache()
        var reads = 0
        assertNull(cache.read(file, 100) { reads++; null })
        assertEquals("digest", cache.read(file, 100) { reads++; "digest" })
        assertEquals("digest", cache.read(file, 100) { reads++; error("Unexpected repeated package read") })
        assertEquals(2, reads)
    }
}
