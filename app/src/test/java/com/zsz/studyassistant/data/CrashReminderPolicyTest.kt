package com.zsz.studyassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrashReminderPolicyTest {
    @Test fun remindersRequireANewCrashInTheCurrentInstallation() {
        // First upgrade to this feature: legacy files have no handler-created marker.
        assertNull(pendingCrashForInstallation(null, 0, 200, null))
        // Updating (even to the same version) must not replay the previous install's crash.
        assertNull(pendingCrashForInstallation("old-crash", 100, 200, null))
        assertEquals("new-crash", pendingCrashForInstallation("new-crash", 200, 200, "old-crash"))
        assertNull(pendingCrashForInstallation("new-crash", 200, 200, "new-crash"))
        // Missing package metadata must never cause a guessed crash notification.
        assertNull(pendingCrashForInstallation("new-crash", 0, 0, null))
    }
}
