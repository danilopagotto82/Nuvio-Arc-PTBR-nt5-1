package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.core.player.BitrateAwareLoadControl
import com.nuvio.tv.core.player.BufferSizing

/** Numbers the buffer settings show, worked out with the rules the player uses. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object BufferSettingsNumbers {
    /** A 4K remux at about 80 Mbit/s, the bitrate the back buffer is sized for. */
    const val REMUX_MB_PER_SECOND = 10

    /** Custom buffers without native memory never go past this budget. */
    val CUSTOM_ENGINE_MAX_MB: Int = (BitrateAwareLoadControl.MAX_BUDGET_BYTES / (1024L * 1024L)).toInt()

    private const val MB = 1024L * 1024L

    fun secondsHeld(bufferMb: Int): Int = (bufferMb / REMUX_MB_PER_SECOND).coerceAtLeast(0)

    /** Seconds for the line under Max buffer duration, or null when the setting is not longer than the size holds. */
    fun secondsHint(bufferMb: Int, maxBufferSeconds: Int): Int? =
        secondsHeld(bufferMb).takeIf { maxBufferSeconds > it }

    data class BackBufferKept(val seconds: Int, val mb: Int)

    /**
     * The back buffer the player keeps inside [bufferMb]. With native memory the player also holds it to half of
     * [minBufferMs]. Null when nothing is kept.
     */
    fun backBufferKept(backBufferMs: Int, bufferMb: Int, nativeMemory: Boolean, minBufferMs: Int): BackBufferKept? {
        val setMs = if (nativeMemory) backBufferMs.coerceAtMost(minBufferMs / 2) else backBufferMs
        val keptMs = BufferSizing.backBufferMsForTarget(setMs, bufferMb.toLong() * MB)
        if (keptMs <= 0) return null
        return BackBufferKept(
            seconds = ((keptMs + 500) / 1000).coerceAtLeast(1),
            mb = ((keptMs.toLong() * REMUX_MB_PER_SECOND + 500) / 1000).toInt()
        )
    }

    /**
     * Above this a size the user set may stutter: the box's automatic size. Custom buffers without native memory
     * use their automatic size after the parallel chunks, on the slider's 25 MB grid.
     */
    fun stutterLimitMb(nativeMemory: Boolean, ramKnown: Boolean, autoSizeMb: Int, safeMaxMb: Int): Int = when {
        !ramKnown -> safeMaxMb
        nativeMemory -> autoSizeMb
        else -> (autoSizeMb / MemoryBudget.BUFFER_STEP_MB * MemoryBudget.BUFFER_STEP_MB)
            .coerceAtLeast(MemoryBudget.MIN_BUFFER_MB)
    }

    /** The memory card's colour, from the buffer alone: the downloads are shown next to it, not counted against it. */
    fun bufferStatus(bufferMb: Int, automaticSize: Boolean, stutterLimitMb: Int, dangerMb: Int): MemoryUsageStatus = when {
        automaticSize -> MemoryUsageStatus.SAFE
        bufferMb > dangerMb -> MemoryUsageStatus.DANGER
        bufferMb > stutterLimitMb -> MemoryUsageStatus.WARNING
        else -> MemoryUsageStatus.SAFE
    }

    /** The amber line under Connection count: boxes with 2 GB or less, more than 2 connections. */
    fun connectionCountTooHigh(totalRamBytes: Long, connections: Int): Boolean =
        totalRamBytes > 0L && BufferSizing.isSmallBox(totalRamBytes) && connections > BufferSizing.SMALL_BOX_CONNECTIONS
}
