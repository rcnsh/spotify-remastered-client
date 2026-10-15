package app.remastered.spotify.web

import app.remastered.core.CatalogException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebApiGateTest {
    private val store = WebApiGate.InMemory()

    /** A gate on the test's virtual clock (wall clock = START + virtual time). */
    private fun TestScope.gate(maxInFlight: Int = 3, burst: Int = 10, perWindow: Int = 20) = WebApiGate(
        store, now = { START + currentTime }, sleep = { delay(it) }, maxInFlight = maxInFlight, burst = burst, perWindow = perWindow,
    )

    private suspend fun blocked(gate: WebApiGate): CatalogException {
        try {
            gate.withPermit { fail("sent while paused") }
        } catch (e: CatalogException) {
            return e
        }
        error("unreachable")
    }

    @Test fun `a long pause fails every request without sending it`() = runTest {
        val gate = gate()
        gate.pause(9 * HOUR)
        repeat(5) {
            val e = blocked(gate)
            assertEquals(429, e.status)
            assertEquals(WebApiGate.PAUSED, e.reason)
            assertEquals(9 * HOUR, e.retryAfterMs)
        }
        assertEquals(0L, currentTime) // failed at once, no waiting
    }

    @Test fun `the pause is kept in the store and survives a new gate`() = runTest {
        gate().pause(2 * HOUR)
        assertEquals(START + 2 * HOUR, store.load())
        // A new process: same store, a fresh gate.
        val restarted = gate()
        assertEquals(START + 2 * HOUR, restarted.pausedUntil.value)
        assertEquals(2 * HOUR, blocked(restarted).retryAfterMs)
    }

    @Test fun `requests go again once the deadline passes`() = runTest {
        store.save(START + HOUR)
        val gate = gate()
        blocked(gate)
        delay(HOUR)
        assertNull(gate.remainingMs())
        assertEquals("ok", gate.withPermit { "ok" })
    }

    @Test fun `a short pause is waited out, then the request goes`() = runTest {
        val gate = gate()
        gate.pause(5_000)
        val sentAt = gate.withPermit { currentTime }
        assertEquals(5_000L, sentAt)
    }

    @Test fun `a pause is never shortened, and a past one is dropped`() = runTest {
        val gate = gate()
        gate.pause(HOUR)
        gate.pause(10_000)
        assertEquals(HOUR, gate.remainingMs())

        store.save(START - 1)
        assertNull(gate().pausedUntil.value)
    }

    @Test fun `an unlikely deadline is capped at a day`() = runTest {
        store.save(START + 30 * 24 * HOUR)
        assertEquals(24 * HOUR, gate().remainingMs())
        gate().pause(30 * 24 * HOUR)
        assertEquals(START + 24 * HOUR, store.load())
    }

    @Test fun `requests queued when a long pause starts don't go`() = runTest {
        val gate = gate(maxInFlight = 1)
        var sent = 0
        val first = async { gate.withPermit { delay(1_000); gate.pause(HOUR); sent++ } }
        val queued = (1..3).map { async { runCatching { gate.withPermit { sent++ } } } }
        first.await()
        val results = queued.awaitAll()
        assertEquals(1, sent)
        assertTrue(results.all { (it.exceptionOrNull() as? CatalogException)?.isRateLimited == true })
    }

    @Test fun `at most maxInFlight requests at a time`() = runTest {
        val gate = gate(maxInFlight = 3, burst = 100)
        var inFlight = 0
        var most = 0
        (1..12).map {
            async {
                gate.withPermit {
                    inFlight++
                    most = maxOf(most, inFlight)
                    delay(1_000)
                    inFlight--
                }
            }
        }.awaitAll()
        assertEquals(3, most)
        assertEquals(4_000L, currentTime) // 12 requests, 3 at a time, 1 s each
    }

    @Test fun `a burst is spread out by the token bucket`() = runTest {
        // 10 at once, then 20 per 30 s: one every 1.5 s.
        val gate = gate(maxInFlight = 50, burst = 10, perWindow = 20)
        val times = (1..30).map { async { gate.withPermit { currentTime } } }.awaitAll().sorted()
        assertTrue(times.take(10).all { it == 0L })
        assertEquals(1_500L, times[10])
        assertEquals(30_000L, times.last())
        // No more than the burst plus the refill in any 30 s.
        assertTrue(times.count { it < 30_000 } <= 10 + 20)
    }

    @Test fun `the bucket refills while idle`() = runTest {
        val gate = gate(burst = 4, perWindow = 4)
        repeat(4) { gate.withPermit { } }
        delay(30_000)
        val times = (1..4).map { async { gate.withPermit { currentTime } } }.awaitAll()
        assertTrue(times.all { it == 30_000L })
    }

    private companion object {
        const val START = 1_700_000_000_000L
        const val HOUR = 3_600_000L
    }
}
