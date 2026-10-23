package app.remastered.core

import app.remastered.core.SleepTimer.Mode
import app.remastered.core.SleepTimer.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepTimerTest {
    private var now = 1_000_000L
    private val timer = SleepTimer { now }

    private fun song(uri: String = "spotify:track:1", durationMs: Long = 200_000) =
        NowPlaying(uri, "Song", emptyList(), "Album", null, null, durationMs = durationMs)
    private fun playing(positionMs: Long, track: NowPlaying = song(), paused: Boolean = false) =
        PlaybackState(track, isPaused = paused, positionMs = positionMs, positionAtMs = now)

    @Test fun offDoesNothing() {
        assertEquals(Step.Idle, timer.step(playing(0)))
        assertNull(timer.remainingMs())
    }

    @Test fun minutesWaitThenPauseOnce() {
        timer.start(5)
        assertEquals(Step.Wait(300_000), timer.step(playing(0)))
        now += 120_000
        assertEquals(180_000L, timer.remainingMs())
        assertEquals(Step.Wait(180_000), timer.step(null))
        now += 180_000
        assertEquals(Step.Pause, timer.step(playing(0)))
        assertNull(timer.mode.value)
        assertEquals(Step.Idle, timer.step(playing(0)))
    }

    @Test fun cancelStopsIt() {
        timer.start(10)
        timer.cancel()
        now += 3_600_000
        assertEquals(Step.Idle, timer.step(playing(0)))
    }

    @Test fun startingAgainReplacesTheTimer() {
        timer.start(60)
        timer.start(15)
        assertEquals(Mode.After(15, now + 900_000), timer.mode.value)
    }

    @Test fun aLongMissedPauseIsDroppedNotDoneLate() {
        timer.start(5)
        now += 300_000 + SleepTimer.STALE_MS + 1
        assertEquals(Step.Idle, timer.step(playing(0)))
        assertNull(timer.mode.value)
    }

    @Test fun endOfTrackPausesJustBeforeTheEnd() {
        timer.startEndOfTrack()
        assertEquals(Step.Wait(150_000 - SleepTimer.EARLY_MS), timer.step(playing(50_000)))
        now += 150_000 - SleepTimer.EARLY_MS
        assertEquals(Step.Pause, timer.step(playing(50_000).copy(positionAtMs = now - 150_000 + SleepTimer.EARLY_MS)))
        assertNull(timer.mode.value)
    }

    @Test fun endOfTrackFollowsASeek() {
        timer.startEndOfTrack()
        timer.step(playing(10_000))
        assertEquals(Step.Wait(20_000 - SleepTimer.EARLY_MS), timer.step(playing(180_000)))
    }

    @Test fun endOfTrackWaitsWhilePaused() {
        timer.startEndOfTrack()
        assertEquals(Step.Idle, timer.step(playing(50_000, paused = true)))
        now += 600_000
        assertEquals(Step.Wait(150_000 - SleepTimer.EARLY_MS), timer.step(playing(50_000)))
    }

    @Test fun endOfTrackMovesOnAfterASkip() {
        timer.startEndOfTrack()
        timer.step(playing(10_000))
        now += 5_000
        assertEquals(Step.Wait(240_000 - SleepTimer.EARLY_MS), timer.step(playing(0, song("spotify:track:2", 240_000))))
    }

    @Test fun endOfTrackPausesTheNextSongIfTheEndWasMissed() {
        timer.startEndOfTrack()
        timer.step(playing(190_000))
        // The driver was late: the next song has already started.
        now += 10_500
        assertEquals(Step.Pause, timer.step(playing(500, song("spotify:track:2"))))
        assertNull(timer.mode.value)
    }

    @Test fun endOfTrackCountsPlaybackSpeed() {
        timer.startEndOfTrack()
        val podcast = playing(100_000).copy(speed = 2f)
        assertEquals(Step.Wait(50_000 - SleepTimer.EARLY_MS), timer.step(podcast))
    }

    @Test fun endOfTrackWaitsForSomethingToPlay() {
        timer.startEndOfTrack()
        assertEquals(Step.Idle, timer.step(null))
        assertEquals(Mode.EndOfTrack, timer.mode.value)
    }
}
