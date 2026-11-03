package com.zsz.studyassistant.data

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class CropWaitTest {
    @Test fun detentSeparatesFiveSecondsFromUnlimited() {
        assertEquals(5000L, CropWait.fromPosition(.94f, 5000))
        assertEquals(0L, CropWait.fromPosition(.98f, 5000))
        assertEquals(0L, CropWait.fromPosition(.92f, 0))
        assertEquals(5000L, CropWait.fromPosition(.86f, 0))
        for (ms in listOf(0L, 100L, 500L, 1000L, 3000L, 5000L))
            assertEquals(ms, CropWait.fromPosition(CropWait.position(ms), ms))
    }

    @Test fun unlimitedWaitReturnsResultsAndCanStillBeCancelled() = runBlocking {
        assertEquals("result", CropWait.await(0) { "result" })
        assertNull(CropWait.await<String>(30) { awaitCancellation() })
        assertNull(withTimeoutOrNull(30) { CropWait.await<String>(0) { awaitCancellation() } })
    }

    @Test fun usableCandidateSurvivesRejectedLargerBox() {
        val edge = """{"x":0,"y":0,"w":1,"h":1}"""
        val good = """{"x":0.1,"y":0.2,"w":0.8,"h":0.3}"""
        val box = StudyAssistant.parseCropBox("""{"boxes":[null,$edge,$good]}""")
        assertEquals(ImageAutoCrop.NormRect(.1f,.2f,.8f,.3f), box)
        assertNull(StudyAssistant.parseCropBox("""{"boxes":[$edge]}"""))
        assertEquals(1f, StudyAssistant.parseCropBox("""{"boxes":[$edge]}""", manual = true)?.w)
    }
}
