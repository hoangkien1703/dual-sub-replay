package com.kienhoang.dualsubreplay.translation

import com.kienhoang.dualsubreplay.ui.RESETTABLE_SETTING_KEYS
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleWaitTest {
    @Test fun automaticWaitsTwoSecondsOnMobileDataAndFourOnWifi() {
        assertEquals(2_000L, googleWaitMs(GoogleWait.AUTO, onWifi = false))
        assertEquals(4_000L, googleWaitMs(GoogleWait.AUTO, onWifi = true))
    }

    @Test fun aFixedWaitIgnoresTheNetwork() {
        for (onWifi in listOf(false, true)) {
            assertEquals(2_000L, googleWaitMs(GoogleWait.TWO_SECONDS, onWifi))
            assertEquals(4_000L, googleWaitMs(GoogleWait.FOUR_SECONDS, onWifi))
            assertEquals(8_000L, googleWaitMs(GoogleWait.EIGHT_SECONDS, onWifi))
        }
    }

    @Test fun storedValuesReadBackAndUnknownOnesAreAutomatic() {
        GoogleWait.entries.forEach { assertEquals(it, storedGoogleWait(it.storageValue)) }
        assertEquals(GoogleWait.AUTO, storedGoogleWait(null))
        assertEquals(GoogleWait.AUTO, storedGoogleWait("3"))
        assertTrue(GOOGLE_WAIT_PREFERENCE in RESETTABLE_SETTING_KEYS)
    }

    @Test fun aQuickAnswerIsReturned() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob())
            assertEquals("hello", awaitWithin(scope, 1_000L) { "hello" })
            assertEquals(null, awaitWithin(scope, 1_000L) { null })
            scope.cancel()
        }

    @Test fun aQuickFailureIsRethrown() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob())
            val failure = GoogleTranslateException("Google Translate could not be reached.", unreachable = true)
            val thrown = runCatching { awaitWithin<String>(scope, 1_000L) { throw failure } }.exceptionOrNull()
            assertSame(failure, thrown)
            // The failure stays in its request and does not cancel the other requests.
            assertTrue(scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
            scope.cancel()
        }

    @Test fun aSlowRequestKeepsGoingAfterTheWait() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob())
            val answer = CompletableDeferred<String>()
            val slow = runCatching { awaitWithin(scope, 50L) { answer.await() } }.exceptionOrNull()
            assertTrue(slow is GoogleSlowException)
            slow as GoogleSlowException
            assertEquals(50L, slow.waitMs)
            assertFalse(slow.pending.isCompleted)
            answer.complete("late")
            delay(10)
            assertEquals("late", slow.pending.await())
            scope.cancel()
        }

    @Test fun theSlowMessageNamesTheWaitInSeconds() {
        val slow = GoogleSlowException(2_000L, CompletableDeferred<Unit>())
        assertEquals("Google Translate did not answer within 2 s; the app keeps waiting for it in the background.", slow.message)
    }
}
