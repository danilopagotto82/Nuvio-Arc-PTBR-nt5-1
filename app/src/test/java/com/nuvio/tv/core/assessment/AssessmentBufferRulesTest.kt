package com.nuvio.tv.core.assessment

import com.nuvio.tv.core.network.StreamSweepEngine
import com.nuvio.tv.core.network.StreamSweepEngine.MeasuredPass
import com.nuvio.tv.core.player.BufferSizing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssessmentBufferRulesTest {
    private val gb = 1024L * 1024L * 1024L
    private val twoGbBox = (1.9 * gb).toLong()
    private val fourGbBox = (3.8 * gb).toLong()

    @Test
    fun `2 GB box gets custom buffers and managed budget on, no fixed size and at most 2 connections`() {
        val autoMb = BufferSizing.autoBufferMbForRam(twoGbBox)
        assertEquals(150, autoMb)
        assertEquals(true, AssessmentBufferRules.bufferEngineChange(false))
        assertEquals(true, AssessmentBufferRules.managedBudgetChange(false))
        assertNull(AssessmentBufferRules.managedBudgetChange(true))
        assertTrue(AssessmentBufferRules.allowLargeRecommendedOff(twoGbBox))
        assertEquals(false, AssessmentBufferRules.allowLargeChange(twoGbBox, true))
        assertNull(AssessmentBufferRules.allowLargeChange(twoGbBox, false))
        // 150 MB of an 80 Mbit/s remux.
        val capacity = AssessmentBufferRules.capacitySeconds(autoMb, 80.0)
        assertEquals(15, capacity)
        assertEquals(15, AssessmentBufferRules.recommendedMaxBufferSeconds(capacity))

        val measured = listOf(
            MeasuredPass(2, 8, 120.0), MeasuredPass(2, 16, 141.0),
            MeasuredPass(3, 16, 168.0), MeasuredPass(4, 16, 175.0)
        )
        val picks = StreamSweepEngine.recommendablePasses(measured, 32, BufferSizing.isSmallBox(twoGbBox))
        assertTrue(picks.all { it.connections <= 2 })
        assertEquals(2, picks.size)
        assertTrue(StreamSweepEngine.cappedForSmallBox(measured, picks.maxBy { it.mbps }, true))
    }

    @Test
    fun `4 GB box keeps up to 4 connections and leaves Allow larger alone`() {
        val autoMb = BufferSizing.autoBufferMbForRam(fourGbBox)
        assertEquals(1000, autoMb)
        assertFalse(AssessmentBufferRules.allowLargeRecommendedOff(fourGbBox))
        assertNull(AssessmentBufferRules.allowLargeChange(fourGbBox, true))
        assertEquals(100, AssessmentBufferRules.capacitySeconds(autoMb, 80.0))
        assertEquals(100, AssessmentBufferRules.recommendedMaxBufferSeconds(100))
        assertEquals(120, AssessmentBufferRules.recommendedMaxBufferSeconds(AssessmentBufferRules.capacitySeconds(autoMb, 40.0)))

        val measured = listOf(MeasuredPass(2, 16, 141.0), MeasuredPass(3, 16, 168.0), MeasuredPass(4, 16, 175.0))
        val picks = StreamSweepEngine.recommendablePasses(measured, 32, BufferSizing.isSmallBox(fourGbBox))
        assertEquals(3, picks.size)
        assertFalse(StreamSweepEngine.cappedForSmallBox(measured, picks.maxBy { it.mbps }, false))
    }

    @Test
    fun `the cap is only reported when a left out setup was faster`() {
        val measured = listOf(MeasuredPass(2, 16, 180.0), MeasuredPass(3, 16, 168.0))
        assertFalse(StreamSweepEngine.cappedForSmallBox(measured, measured[0], true))
    }

    @Test
    fun `the buffer trade works from the automatic size`() {
        // 2 GB box: 150 MB less 2 x 16 MB chunks with their two spares.
        assertEquals(75, StreamSweepEngine.tradeBufferMb(150, 64))
        assertEquals(100, StreamSweepEngine.tradeBufferMb(150, 32))
        assertEquals(25, StreamSweepEngine.tradeBufferMb(150, 200))
        assertEquals(900, StreamSweepEngine.tradeBufferMb(1000, 80))
    }

    @Test
    fun `max buffer duration stays between 10 and 120 s and needs a bitrate`() {
        assertNull(AssessmentBufferRules.capacitySeconds(150, null))
        assertEquals(120, AssessmentBufferRules.recommendedMaxBufferSeconds(null))
        assertEquals(10, AssessmentBufferRules.recommendedMaxBufferSeconds(AssessmentBufferRules.capacitySeconds(100, 120.0)))
    }

    @Test
    fun `custom network follows the parallel recommendation`() {
        assertEquals(true, AssessmentBufferRules.networkRecommendation(StreamSweepEngine.VerdictKind.RECOMMEND_CONFIG, true))
        assertEquals(true, AssessmentBufferRules.networkRecommendation(StreamSweepEngine.VerdictKind.MARGINAL, true))
        assertEquals(false, AssessmentBufferRules.networkRecommendation(StreamSweepEngine.VerdictKind.LEAVE_PARALLEL_OFF, false))
        assertNull(AssessmentBufferRules.networkRecommendation(null, false))
        assertNull(AssessmentBufferRules.networkRecommendation(StreamSweepEngine.VerdictKind.NONE, false))

        assertEquals(true, AssessmentBufferRules.networkChange(true, false))
        assertNull(AssessmentBufferRules.networkChange(true, true))
        assertEquals(false, AssessmentBufferRules.networkChange(false, true))
        assertNull(AssessmentBufferRules.networkChange(null, true))
    }

    @Test
    fun `a size the user set is compared with the same lines as Settings`() {
        assertEquals(AssessmentBufferRules.CurrentTarget.FINE, AssessmentBufferRules.currentTarget(true, 400, 150, 325))
        assertEquals(AssessmentBufferRules.CurrentTarget.FINE, AssessmentBufferRules.currentTarget(false, 150, 150, 325))
        assertEquals(AssessmentBufferRules.CurrentTarget.STUTTER, AssessmentBufferRules.currentTarget(false, 250, 150, 325))
        assertEquals(AssessmentBufferRules.CurrentTarget.DANGER, AssessmentBufferRules.currentTarget(false, 400, 150, 325))
    }
}
