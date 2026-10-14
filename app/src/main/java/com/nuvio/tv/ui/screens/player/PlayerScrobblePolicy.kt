package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Player
import com.nuvio.tv.core.tracking.TrackingScrobbleAction

internal fun trackingActionForNonPlayingState(playbackState: Int): TrackingScrobbleAction? = when (playbackState) {
    Player.STATE_BUFFERING -> null
    Player.STATE_ENDED, Player.STATE_IDLE -> TrackingScrobbleAction.STOP
    else -> TrackingScrobbleAction.PAUSE
}

internal fun shouldSendPauseScrobble(
    hasActiveScrobble: Boolean,
    progressPercent: Float
): Boolean = hasActiveScrobble && progressPercent in 0f..100f

internal fun shouldSendStopScrobble(
    hasActiveScrobble: Boolean,
    progressPercent: Float
): Boolean = hasActiveScrobble || progressPercent >= 80f

/** A film, or an episode of a series: real streams of these are never under about 2 minutes. */
internal fun isFilmOrEpisode(contentType: String?, season: Int?, episode: Int?): Boolean {
    val type = contentType?.trim()?.lowercase() ?: return false
    if (type == "movie") return true
    return type in listOf("series", "tv", "anime") && season != null && episode != null
}

/**
 * No scrobbles and no thumbnails: the stream was rejected as an error card, or a film or
 * episode plays a stream under about 2 minutes. [playerDurationMs] is the player's own length
 * for this stream, not the longest one seen, which a failed attempt of a real file can leave behind.
 */
internal fun isErrorCardPlayback(
    placeholderRejected: Boolean,
    filmOrEpisode: Boolean,
    playerDurationMs: Long
): Boolean = placeholderRejected || (filmOrEpisode && isShortPlaceholderDuration(playerDurationMs))
