package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Player
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerScrobblePolicyTest {
    @Test
    fun `ready non-playing state emits pause`() {
        assertEquals(
            TrackingScrobbleAction.PAUSE,
            trackingActionForNonPlayingState(Player.STATE_READY)
        )
    }

    @Test
    fun `ended and idle states emit stop`() {
        assertEquals(
            TrackingScrobbleAction.STOP,
            trackingActionForNonPlayingState(Player.STATE_ENDED)
        )
        assertEquals(
            TrackingScrobbleAction.STOP,
            trackingActionForNonPlayingState(Player.STATE_IDLE)
        )
    }

    @Test
    fun `buffering state emits no terminal scrobble`() {
        assertNull(trackingActionForNonPlayingState(Player.STATE_BUFFERING))
    }

    @Test
    fun `active pause remains eligible above watched threshold`() {
        assertTrue(shouldSendPauseScrobble(hasActiveScrobble = true, progressPercent = 90f))
    }

    @Test
    fun `pause requires active scrobble and accepts early progress`() {
        assertFalse(shouldSendPauseScrobble(hasActiveScrobble = false, progressPercent = 45f))
        assertTrue(shouldSendPauseScrobble(hasActiveScrobble = true, progressPercent = 0f))
        assertTrue(shouldSendPauseScrobble(hasActiveScrobble = true, progressPercent = 0.5f))
    }

    @Test
    fun `stop closes active sessions below one percent`() {
        assertTrue(shouldSendStopScrobble(hasActiveScrobble = true, progressPercent = 0f))
        assertTrue(shouldSendStopScrobble(hasActiveScrobble = true, progressPercent = 0.5f))
        assertFalse(shouldSendStopScrobble(hasActiveScrobble = false, progressPercent = 0.5f))
    }

    @Test
    fun `completion stop remains eligible without an active session`() {
        assertFalse(shouldSendStopScrobble(hasActiveScrobble = false, progressPercent = 79.99f))
        assertTrue(shouldSendStopScrobble(hasActiveScrobble = false, progressPercent = 80f))
    }

    @Test
    fun `films and episodes are recognised, other content is not`() {
        assertTrue(isFilmOrEpisode("movie", null, null))
        assertTrue(isFilmOrEpisode("Movie", null, null))
        assertTrue(isFilmOrEpisode("series", 1, 3))
        assertTrue(isFilmOrEpisode("tv", 2, 1))
        assertFalse(isFilmOrEpisode("series", null, null))
        assertFalse(isFilmOrEpisode("channel", null, null))
        assertFalse(isFilmOrEpisode("other", 1, 1))
        assertFalse(isFilmOrEpisode(null, 1, 1))
    }

    @Test
    fun `an 8 second stream for a film is an error card`() {
        assertTrue(isErrorCardPlayback(placeholderRejected = false, filmOrEpisode = true, playerDurationMs = 8_000L))
        assertTrue(isErrorCardPlayback(placeholderRejected = false, filmOrEpisode = true, playerDurationMs = 120_999L))
        assertFalse(isErrorCardPlayback(placeholderRejected = false, filmOrEpisode = true, playerDurationMs = 121_000L))
        assertFalse(isErrorCardPlayback(placeholderRejected = false, filmOrEpisode = true, playerDurationMs = 7_080_000L))
    }

    @Test
    fun `an unknown length is not judged`() {
        assertFalse(isErrorCardPlayback(placeholderRejected = false, filmOrEpisode = true, playerDurationMs = 0L))
        assertFalse(isErrorCardPlayback(placeholderRejected = false, filmOrEpisode = true, playerDurationMs = -9_223_372_036_854_775_807L))
    }

    @Test
    fun `short content outside films and episodes keeps its scrobbles and thumbnails`() {
        assertFalse(isErrorCardPlayback(placeholderRejected = false, filmOrEpisode = false, playerDurationMs = 90_000L))
    }

    @Test
    fun `a rejected error card never scrobbles whatever its length`() {
        assertTrue(isErrorCardPlayback(placeholderRejected = true, filmOrEpisode = false, playerDurationMs = 0L))
        assertTrue(isErrorCardPlayback(placeholderRejected = true, filmOrEpisode = true, playerDurationMs = 7_080_000L))
    }
}
