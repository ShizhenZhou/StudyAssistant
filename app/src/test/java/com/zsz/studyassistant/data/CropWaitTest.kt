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

    @Test fun manualRetryChoosesTheMarkedProblemInsteadOfTheLargestOne() {
        val json = """{"boxes":[{"x":0.05,"y":0.1,"w":0.85,"h":0.33},{"x":0.1,"y":0.58,"w":0.65,"h":0.2}]}"""
        val target = ImageAutoCrop.NormRect(.2f, .62f, .3f, .08f)
        assertEquals(.58f, StudyAssistant.parseCropBox(json, true, target)?.y)
        assertNull(StudyAssistant.parseCropBox(json, true, target.copy(y = .91f)))
    }

    @Test fun localProjectionFindsSameLinesOnLightAndDarkPages() {
        fun page(background: Int, ink: Int) = IntArray(160 * 120) { i ->
            val y = i / 160
            if (i % 160 in 20..130 && (y in 40..44 || y in 48..53)) ink else background
        }
        val light = ImageAutoCrop.projectBlocks(page(-1, 0xff000000.toInt()), 160, 120)
        assertEquals(1, light.size)
        assertEquals(light, ImageAutoCrop.projectBlocks(page(0xff000000.toInt(), -1), 160, 120))
    }

    @Test fun localSelectionIncludesTheOptionButStopsBeforeTheNextProblem() {
        val problem = ImageAutoCrop.NormRect(.12f, .32f, .64f, .18f)
        val option = ImageAutoCrop.NormRect(.16f, .52f, .28f, .04f)
        val next = ImageAutoCrop.NormRect(.12f, .65f, .6f, .13f)
        val box = requireNotNull(ImageAutoCrop.selectQuestionBlock(listOf(problem, option, next)))
        assertTrue(box.y <= problem.y && box.y + box.h >= option.y + option.h)
        assertTrue(box.y + box.h < next.y)
    }
}
