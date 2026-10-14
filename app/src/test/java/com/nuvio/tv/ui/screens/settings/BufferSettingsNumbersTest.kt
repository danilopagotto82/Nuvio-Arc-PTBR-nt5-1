package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferSettingsNumbersTest {
    private val gb = 1024L * 1024L * 1024L

    @Test
    fun `the seconds line shows what the size holds of an 80 Mbit s remux`() {
        assertEquals(15, BufferSettingsNumbers.secondsHint(150, 280))
        assertEquals(8, BufferSettingsNumbers.secondsHint(86, 45))
        assertEquals(100, BufferSettingsNumbers.secondsHint(1000, 280))
        assertEquals(12, BufferSettingsNumbers.secondsHint(125, 45))
    }

    @Test
    fun `the seconds line stays away when the setting is not longer than the size holds`() {
        assertNull(BufferSettingsNumbers.secondsHint(1000, 45))
        assertNull(BufferSettingsNumbers.secondsHint(150, 15))
        assertNull(BufferSettingsNumbers.secondsHint(2000, 120))
    }

    @Test
    fun `the back buffer note shows what the player keeps inside the size`() {
        // Native memory preset: 12 s back buffer, 200 s minimum.
        assertEquals(BufferSettingsNumbers.BackBufferKept(5, 50), BufferSettingsNumbers.backBufferKept(12_000, 150, true, 200_000))
        assertEquals(BufferSettingsNumbers.BackBufferKept(12, 120), BufferSettingsNumbers.backBufferKept(12_000, 1000, true, 200_000))
        assertEquals(BufferSettingsNumbers.BackBufferKept(1, 12), BufferSettingsNumbers.backBufferKept(12_000, 62, true, 200_000))
        assertEquals(BufferSettingsNumbers.BackBufferKept(3, 33), BufferSettingsNumbers.backBufferKept(12_000, 100, true, 200_000))
        // Custom buffers without native memory on a 2 GB box with 2 x 16 MB chunks: 150 - 64 = 86 MB.
        assertEquals(BufferSettingsNumbers.BackBufferKept(3, 29), BufferSettingsNumbers.backBufferKept(5_000, 86, false, 15_000))
        // A short back buffer that fits stays as set.
        assertEquals(BufferSettingsNumbers.BackBufferKept(5, 50), BufferSettingsNumbers.backBufferKept(5_000, 1000, false, 15_000))
    }

    @Test
    fun `with native memory the back buffer is also held to half the minimum buffer`() {
        assertEquals(BufferSettingsNumbers.BackBufferKept(8, 75), BufferSettingsNumbers.backBufferKept(12_000, 1000, true, 15_000))
        assertEquals(BufferSettingsNumbers.BackBufferKept(12, 120), BufferSettingsNumbers.backBufferKept(12_000, 1000, false, 15_000))
    }

    @Test
    fun `nothing is kept at the smallest size`() {
        assertNull(BufferSettingsNumbers.backBufferKept(12_000, 50, true, 200_000))
        assertNull(BufferSettingsNumbers.backBufferKept(0, 1000, true, 200_000))
    }

    @Test
    fun `the stutter line follows the automatic size`() {
        assertEquals(150, BufferSettingsNumbers.stutterLimitMb(true, true, 150, 250))
        assertEquals(75, BufferSettingsNumbers.stutterLimitMb(false, true, 86, 175))
        assertEquals(250, BufferSettingsNumbers.stutterLimitMb(true, false, 150, 250))
    }

    @Test
    fun `the memory card is coloured by the buffer only`() {
        // 2 GB box: 150 MB buffer plus 96 MB of chunks used to turn the card orange.
        assertEquals(MemoryUsageStatus.SAFE, BufferSettingsNumbers.bufferStatus(150, true, 150, 325))
        assertEquals(MemoryUsageStatus.SAFE, BufferSettingsNumbers.bufferStatus(150, false, 150, 325))
        assertEquals(MemoryUsageStatus.WARNING, BufferSettingsNumbers.bufferStatus(250, false, 150, 325))
        assertEquals(MemoryUsageStatus.DANGER, BufferSettingsNumbers.bufferStatus(400, false, 150, 325))
        // Managed custom buffers: the buffer is the automatic size less the chunks.
        assertEquals(MemoryUsageStatus.SAFE, BufferSettingsNumbers.bufferStatus(86, true, 75, 225))
    }

    @Test
    fun `the connection count line is for boxes with 2 GB or less above 2 connections`() {
        assertTrue(BufferSettingsNumbers.connectionCountTooHigh((1.9 * gb).toLong(), 4))
        assertTrue(BufferSettingsNumbers.connectionCountTooHigh((1.9 * gb).toLong(), 3))
        assertFalse(BufferSettingsNumbers.connectionCountTooHigh((1.9 * gb).toLong(), 2))
        assertFalse(BufferSettingsNumbers.connectionCountTooHigh((3.8 * gb).toLong(), 4))
        assertFalse(BufferSettingsNumbers.connectionCountTooHigh(0L, 4))
    }

    @Test
    fun `custom buffers without native memory stop at 1600 MB`() {
        assertEquals(1600, BufferSettingsNumbers.CUSTOM_ENGINE_MAX_MB)
    }
}
