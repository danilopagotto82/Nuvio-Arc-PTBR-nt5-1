package com.nuvio.tv.ui.brand

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.nuvio.tv.R
import kotlin.math.PI
import kotlin.math.cos

/**
 * The entrance's sound, 5.1 AAC that Android downmixes where the output has fewer channels. Started on the first
 * drawn frame so the discharge lands with the picture; never takes audio focus, so it stops as soon as the app
 * leaves the screen, also during the fade.
 */
class ArcIntroSound(context: Context, private val lifecycle: Lifecycle) {
    private var released = false
    private var fading = false
    private val main = Handler(Looper.getMainLooper())
    // Watches for as long as the sound lives, also during the tail that outlasts the intro.
    private val stopObserver = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) release() }
    private val player: ExoPlayer? = runCatching {
        val app = context.applicationContext
        val audioOnly = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf(MediaCodecAudioRenderer(app, MediaCodecSelector.DEFAULT, handler, audioListener))
        }
        ExoPlayer.Builder(app, audioOnly)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                false
            )
            .build()
            .apply {
                val uri = Uri.Builder()
                    .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                    .authority(app.packageName)
                    .path(R.raw.arc_intro_sound.toString())
                    .build()
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
            }
    }.onFailure { Log.w(Tag, "sound unavailable", it) }.getOrNull()

    init {
        lifecycle.addObserver(stopObserver)
    }

    fun play() {
        if (!released) player?.play()
    }

    /**
     * Fades out along an equal-power curve and then releases, on the main thread and independent of the screen that
     * started it, so the tail keeps ringing while Home appears. Repeated calls keep the fade already running.
     */
    fun fadeOutAndRelease(durationMs: Long) {
        val p = player
        if (released || fading || p == null) { if (p == null) release(); return }
        fading = true
        val start = p.volume
        val begin = SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                if (released) return
                val t = ((SystemClock.uptimeMillis() - begin).toFloat() / durationMs).coerceIn(0f, 1f)
                p.volume = start * cos(t * PI.toFloat() / 2f)
                if (t >= 1f) release() else main.postDelayed(this, FadeStepMs)
            }
        }
        main.post(step)
    }

    fun release() {
        if (released) return
        released = true
        lifecycle.removeObserver(stopObserver)
        main.removeCallbacksAndMessages(null)
        player?.release()
    }

    companion object {
        const val FadeMs = 1_500L
        private const val FadeStepMs = 20L
        private const val Tag = "ArcIntroSound"
    }
}
