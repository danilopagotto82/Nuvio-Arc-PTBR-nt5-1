package com.nuvio.tv.ui.screens.player

import kotlin.math.min
import kotlin.math.pow

/**
 * Remote seeking. A tap moves 10 s. A held key glides from its first repeat: the cursor moves every frame at a
 * speed that doubles every [DOUBLING_MS] of holding, from [START_SPEED] up to [MAX_SPEED] (film ms per ms). A TV
 * remote over HDMI-CEC stops at [TV_REMOTE_MAX_SPEED]: some TVs send the release seconds after the key is let go.
 */
object PlayerScrubRates {
    const val STEP_SHORT_MS = 10_000L

    private const val GLIDE_FROM_MS = 500L
    private const val START_SPEED = 100.0
    private const val MAX_SPEED = 800.0
    private const val TV_REMOTE_MAX_SPEED = 400.0
    private const val DOUBLING_MS = 1_750.0

    /** Thumbnails, time and timeline follow a glide at this interval; the bar's own cursor follows every frame. */
    const val GLIDE_PUBLISH_MS = 166L

    fun tapDeltaMs(forward: Boolean): Long = if (forward) STEP_SHORT_MS else -STEP_SHORT_MS

    /** Film ms per ms for a key held for [holdDurationMs]. */
    fun glideSpeed(holdDurationMs: Long, tvRemote: Boolean = false): Double =
        min(if (tvRemote) TV_REMOTE_MAX_SPEED else MAX_SPEED, START_SPEED * 2.0.pow((holdDurationMs - GLIDE_FROM_MS).coerceAtLeast(0L) / DOUBLING_MS))
}
