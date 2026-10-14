package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubHoldClockTest {

    /** HDMI-CEC: each repeat comes with its own time as down time. */
    private fun ScrubHoldClock.cec(at: Long): Long? = repeat(eventTime = at, downTime = at)

    @Test
    fun tvRemote_holdCountsFromThePressAndStopsSoonAfterTheLastRepeat() {
        val clock = ScrubHoldClock()
        clock.press(18_959L)
        assertEquals(18_959L, clock.cec(19_434L))
        var t = 19_434L
        var step = 96L
        while (t < 24_800L) {
            t += step
            step = if (step == 96L) 118L else 96L
            assertEquals(18_959L, clock.cec(t))
            assertTrue(clock.moving(t + 16L))
        }
        assertEquals(236L, clock.standStillMs())
        assertTrue(clock.moving(t + 200L))
        assertFalse(clock.moving(t + 250L))
    }

    @Test
    fun tvRemote_tapSentTwiceStaysATap() {
        val clock = ScrubHoldClock()
        clock.press(25_056L)
        assertNull(clock.cec(25_152L))
        assertFalse(clock.moving(25_160L))
    }

    @Test
    fun tvRemote_withoutAKnownPressGlidesFromTheRepeat() {
        val clock = ScrubHoldClock()
        clock.press(1_000L)
        clock.release(0L)
        assertEquals(5_000L, clock.cec(5_000L))
    }

    @Test
    fun slowRepeatingTvKeepsGliding() {
        val clock = ScrubHoldClock()
        clock.press(0L)
        clock.cec(500L)
        clock.cec(950L)
        assertEquals(650L, clock.standStillMs())
        assertTrue(clock.moving(950L + 440L))
        clock.cec(1_400L)
        assertTrue(clock.moving(1_400L + 640L))
        assertFalse(clock.moving(1_400L + 660L))
    }

    @Test
    fun tellsTheTvRemoteFromTheBoxRemote() {
        val clock = ScrubHoldClock()
        clock.press(0L)
        clock.cec(500L)
        assertTrue(clock.fromTv)
        clock.press(1_000L)
        clock.repeat(eventTime = 1_500L, downTime = 1_000L)
        assertFalse(clock.fromTv)
    }

    @Test
    fun boxRemote_holdCountsFromItsDownTime() {
        val clock = ScrubHoldClock()
        clock.press(10_000L)
        assertEquals(10_000L, clock.repeat(eventTime = 10_400L, downTime = 10_000L))
        clock.repeat(eventTime = 10_450L, downTime = 10_000L)
        clock.repeat(eventTime = 10_500L, downTime = 10_000L)
        assertEquals(ScrubHoldClock.MIN_STAND_STILL_MS, clock.standStillMs())
        assertTrue(clock.moving(10_690L))
        assertFalse(clock.moving(10_710L))
    }

    @Test
    fun staleRepeatDoesNotRestartAStoppedGlide() {
        val clock = ScrubHoldClock()
        clock.press(0L)
        clock.cec(500L)
        clock.cec(600L)
        clock.cec(700L)
        assertFalse(clock.moving(1_000L))
        clock.repeat(eventTime = 650L, downTime = 0L)
        assertFalse(clock.moving(1_000L))
        clock.cec(1_000L)
        assertTrue(clock.moving(1_010L))
    }

    @Test
    fun aNewPressStartsAFreshHold() {
        val clock = ScrubHoldClock()
        clock.press(0L)
        clock.cec(500L)
        clock.cec(1_000L)
        clock.press(5_000L)
        assertEquals(ScrubHoldClock.MAX_STAND_STILL_MS, clock.standStillMs())
        assertEquals(5_000L, clock.cec(5_400L))
    }

    @Test
    fun tvBacklogAfterTheReleaseNeverGlides() {
        val clock = ScrubHoldClock()
        clock.release(0L)
        clock.press(20_098L)
        var t = 20_098L
        repeat(36) {
            t += 96L
            assertNull(clock.cec(t))
            assertFalse(clock.moving(t))
        }
    }

    @Test
    fun tvBacklogRightAfterTheReleaseTakesBackItsTap() {
        val clock = ScrubHoldClock()
        clock.release(20_003L)
        clock.press(20_098L)
        assertFalse(clock.takeUndoTap())
        assertNull(clock.cec(20_194L))
        assertTrue(clock.takeUndoTap())
        assertNull(clock.cec(20_290L))
        assertFalse(clock.takeUndoTap())
    }

    @Test
    fun tapDoubledByTheTvLaterThanTheReleaseKeepsItsStep() {
        val clock = ScrubHoldClock()
        clock.release(20_003L)
        clock.press(21_000L)
        assertNull(clock.cec(21_096L))
        assertFalse(clock.takeUndoTap())
    }

    @Test
    fun realHoldRightAfterTheReleaseGlides() {
        val clock = ScrubHoldClock()
        clock.release(1_000L)
        clock.press(1_200L)
        assertEquals(1_200L, clock.cec(1_650L))
        assertFalse(clock.takeUndoTap())
    }

    @Test
    fun tvRepeatingEvery200msStillGlides() {
        val clock = ScrubHoldClock()
        clock.press(0L)
        assertEquals(0L, clock.cec(200L))
        assertEquals(0L, clock.cec(400L))
        assertTrue(clock.moving(450L))
    }
}
