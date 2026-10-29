package com.zsz.studyassistant.ui

import org.junit.Assert.*
import org.junit.Test

class RootBackExitWindowTest {
    @Test fun secondBackWithinTwoSecondsBackgroundsTheTask() {
        val window = RootBackExitWindow()
        assertFalse(window.commit(0))
        assertTrue(window.commit(1999))
        assertFalse(window.commit(2000))
    }
    @Test fun expiredHintRequiresAnotherFirstBack() {
        val window = RootBackExitWindow()
        assertFalse(window.commit(1000))
        assertFalse(window.commit(3000))
        assertTrue(window.commit(3001))
    }
    @Test fun navigationOrBackgroundingClearsThePreviousConfirmation() {
        val window = RootBackExitWindow()
        assertFalse(window.commit(1000))
        window.reset()
        assertFalse(window.commit(1100))
        assertTrue(window.commit(1200))
    }
}
