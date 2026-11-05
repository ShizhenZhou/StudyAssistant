package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Test

class BuildIdentityTest {
    private val old = BuildIdentity.parseStamp("202610240609")
    private val newer = BuildIdentity.parseStamp("20261024160001")
    @Test fun parsesLegacyMinutesAndNewUtcSeconds() {
        assertTrue(old > 0)
        assertEquals(old, BuildIdentity.parseStamp("20261024060900"))
        assertEquals(1000, BuildIdentity.parseStamp("20261024060901") - old)
    }
    @Test fun rejectsInvalidOrDifferentVersionNames() {
        for (stamp in listOf("202602300101", "202613010101", "x202610240609", "202610240609001")) assertEquals(0L, BuildIdentity.parseStamp(stamp))
        assertEquals(0L, BuildIdentity.assetTime("StudyAssistant-0.6.6.5-202610240609.apk", "0.6.6.6"))
        assertEquals(newer, BuildIdentity.assetTime("StudyAssistant-0.6.6.7-20261024160001.apk", "0.6.6.7"))
    }
    @Test fun newerBuildIsDetectedWithoutPhoneInstallationTime() { assertTrue(BuildIdentity.isReplacement(old, newer, null, null)) }
    @Test fun olderBuildNeverReplacesNewerEvenWhenHashesDiffer() { assertFalse(BuildIdentity.isReplacement(newer, old, "a", "b")) }
    @Test fun reuploadedIdenticalBytesAreNotAnUpdate() { assertFalse(BuildIdentity.isReplacement(old, newer, "AB", "ab")) }
    @Test fun legacyInstalledApkUsesDigestFallback() {
        assertTrue(BuildIdentity.isReplacement(0, newer, "old", "new"))
        assertFalse(BuildIdentity.isReplacement(0, newer, null, "new"))
    }
    @Test fun sameSecondRebuildUsesDigestWithoutRepeatedSameBytesPrompt() {
        assertTrue(BuildIdentity.isReplacement(newer, newer, "old", "new"))
        assertFalse(BuildIdentity.isReplacement(newer, newer, "same", "same"))
    }
}
