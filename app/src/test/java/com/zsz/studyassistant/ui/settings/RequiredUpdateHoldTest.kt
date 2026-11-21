package com.zsz.studyassistant.ui

import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class RequiredUpdateHoldTest {
    @Test fun onlyAnUninterruptedTenSecondPressOpensTheWarning() = runBlocking {
        val interactions = MutableSharedFlow<Interaction>()
        val warningAt = CompletableDeferred<Long>()
        val observer = launch {
            interactions.watchRequiredUpdateHold { warningAt.complete(System.nanoTime()) }
        }
        try {
            interactions.subscriptionCount.first { it > 0 }
            val tap = PressInteraction.Press(Offset.Zero)
            interactions.emit(tap)
            delay(300)
            interactions.emit(PressInteraction.Release(tap))
            val cancelled = PressInteraction.Press(Offset.Zero)
            interactions.emit(cancelled)
            delay(300)
            interactions.emit(PressInteraction.Cancel(cancelled))
            val started = System.nanoTime()
            interactions.emit(PressInteraction.Press(Offset.Zero))
            val elapsed = withTimeout(15_000) { warningAt.await() } - started
            assertTrue("Only the final press should reach the threshold", elapsed >= 10_000_000_000L)
        } finally {
            observer.cancelAndJoin()
        }
    }
}
