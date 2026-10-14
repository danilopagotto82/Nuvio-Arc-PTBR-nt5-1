package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerScrubRatesTest {

    @Test
    fun tapMovesTenSecondsEitherWay() {
        assertEquals(10_000L, PlayerScrubRates.tapDeltaMs(forward = true))
        assertEquals(-10_000L, PlayerScrubRates.tapDeltaMs(forward = false))
    }

    @Test
    fun glideSpeed_doublesSmoothlyUpToTheCap() {
        assertEquals(100.0, PlayerScrubRates.glideSpeed(0L), 1e-9)
        assertEquals(100.0, PlayerScrubRates.glideSpeed(500L), 1e-9)
        assertEquals(200.0, PlayerScrubRates.glideSpeed(2_250L), 1e-9)
        assertEquals(400.0, PlayerScrubRates.glideSpeed(4_000L), 1e-9)
        assertEquals(800.0, PlayerScrubRates.glideSpeed(5_750L), 1e-9)
        assertEquals(800.0, PlayerScrubRates.glideSpeed(60_000L), 1e-9)
        var last = 0.0
        for (t in 0L..10_000L step 16L) {
            val v = PlayerScrubRates.glideSpeed(t)
            assertTrue(v >= last)
            last = v
        }
    }

    @Test
    fun tvRemote_startsTheSameAndStopsAtHalfTheTopSpeed() {
        for (t in listOf(0L, 500L, 2_250L, 4_000L)) {
            assertEquals(PlayerScrubRates.glideSpeed(t), PlayerScrubRates.glideSpeed(t, tvRemote = true), 1e-9)
        }
        assertEquals(400.0, PlayerScrubRates.glideSpeed(5_750L, tvRemote = true), 1e-9)
        assertEquals(400.0, PlayerScrubRates.glideSpeed(60_000L, tvRemote = true), 1e-9)
    }

    private fun reachAfter(holdMs: Long): Double {
        var position = 10_000.0          // the first press is a tap
        var t = 500L                     // the glide starts at the first key repeat
        while (t < holdMs) {
            position += PlayerScrubRates.glideSpeed(t) * 16
            t += 16
        }
        return position
    }

    @Test
    fun heldSeek_movesAboutThreeAndAHalfMinutesInTwoSeconds() {
        val reach = reachAfter(2_000L)
        assertTrue("reach $reach", reach in 180_000.0..240_000.0)
    }

    @Test
    fun heldSeek_crossesATwoHourFilmInAboutTwelveSeconds() {
        var t = 500L
        var position = 10_000.0
        while (position < 7_200_000.0) {
            position += PlayerScrubRates.glideSpeed(t) * 16
            t += 16
        }
        assertTrue("reached 2 h after $t ms", t in 10_000L..14_000L)
    }
}
