package com.zsz.studyassistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class CameraZoomTest {
    @Test
    fun `pinch factors accumulate from the requested ratio`() {
        val first = nextCameraZoomRatio(1f, 1.5f, 0.5f, 8f)!!
        val second = nextCameraZoomRatio(first, 2f, 0.5f, 8f)!!
        assertEquals(3f, second, 0.0001f)
        assertEquals(1.5f, nextCameraZoomRatio(second, 0.5f, 0.5f, 8f)!!, 0.0001f)
    }

    @Test
    fun `zoom respects device minimum including ratios below one`() {
        assertEquals(0.5f, nextCameraZoomRatio(1f, 0.1f, 0.5f, 8f)!!, 0f)
        assertEquals(2f, nextCameraZoomRatio(3f, 0.1f, 2f, 8f)!!, 0f)
    }

    @Test
    fun `zoom clamps large factors without overflow`() {
        assertEquals(8f, nextCameraZoomRatio(4f, Float.MAX_VALUE, 1f, 8f)!!, 0f)
        assertEquals(1f, nextCameraZoomRatio(1f, 2f, 1f, 1f)!!, 0f)
    }

    @Test
    fun `invalid factors and ratios are ignored`() {
        for (invalid in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNull(nextCameraZoomRatio(1f, invalid, 1f, 8f))
            assertNull(nextCameraZoomRatio(invalid, 2f, 1f, 8f))
        }
    }

    @Test
    fun `invalid camera limits are ignored`() {
        for ((minimum, maximum) in listOf(
            0f to 8f, -1f to 8f, 8f to 1f,
            Float.NaN to 8f, 1f to Float.NaN,
            Float.POSITIVE_INFINITY to Float.POSITIVE_INFINITY,
            1f to Float.POSITIVE_INFINITY
        )) {
            assertNull(nextCameraZoomRatio(1f, 2f, minimum, maximum))
        }
    }
}
