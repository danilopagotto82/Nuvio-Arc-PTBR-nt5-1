package com.nuvio.tv.ui.v2.profile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nuvio.tv.R
import com.nuvio.tv.ui.brand.ArcIgnitionView
import com.nuvio.tv.ui.brand.ArcIntroSound
import com.nuvio.tv.ui.brand.ArcPalette
import com.nuvio.tv.ui.components.BrandWordmark
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object EntryIdentPolicy {
    /** The spark has reached the bottom of the cut by now, so the exit can begin. */
    const val MinimumCycleMs = 4_600L
    const val RecoveryTimeoutMs = 10_000L
    /** The mark fades out first, then the dark stage dissolves into Home; the sound ends as Home is fully there. */
    const val ExitMs = 1_400L
    const val FirstFrameTimeoutMs = 1_500L
    fun shouldFinish(elapsedMs: Long, homeReady: Boolean): Boolean =
        elapsedMs >= RecoveryTimeoutMs || (homeReady && elapsedMs >= MinimumCycleMs)
}

/** Presentation-only handoff, scoped to this Activity. No profile/session or navigation ownership. */
@Stable
class ProfileEntryState {
    var pending by mutableStateOf(false)
        private set
    var homeReady by mutableStateOf(false)
    var revealRequested by mutableStateOf(false)
        private set
    var revealing by mutableStateOf(false)
        private set
    /** Set once the intro has been on screen (or is switched off), not when it was only asked for. */
    var hasRunThisSession = false
        private set
    fun start(enabled: Boolean = true) {
        if (!enabled) hasRunThisSession = true
        homeReady = false; revealRequested = false; revealing = false; pending = enabled
    }
    fun markShown() { if (pending) hasRunThisSession = true }
    /** An intro that was asked for but ended before its first frame (Home rebuilt under it) is started again. */
    fun shouldStartOnEntry(): Boolean = !hasRunThisSession && !pending
    fun requestReveal() { if (homeReady) revealRequested = true }
    fun beginReveal() { revealing = true }
    fun finish() { pending = false }
}

val LocalProfileEntryState = staticCompositionLocalOf<ProfileEntryState?> { null }

/** The Arc entrance plays on its own surface and thread; Compose only owns timing, Back and the exit. */
@Composable
fun ProfileEntryIdent(state: ProfileEntryState, soundEnabled: Boolean = false, onReturnToProfiles: () -> Unit) {
    val context = LocalContext.current
    val opacity = remember { Animatable(1f) }
    var sound by remember { mutableStateOf<ArcIntroSound?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        val stillIntro = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
        if (!soundEnabled || stillIntro) return@LaunchedEffect
        withFrameNanos { }
        // Owned by this coroutine, so leaving at any moment, even the frame it is made in, always lets it go.
        val created = ArcIntroSound(context, lifecycle)
        sound = created
        try {
            awaitCancellation()
        } finally {
            created.fadeOutAndRelease(ArcIntroSound.FadeMs)
        }
    }
    var leaving by remember { mutableStateOf(false) }
    var minimumComplete by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var backdrop by remember { mutableStateOf(true) }
    var surface by remember { mutableStateOf<ArcIgnitionView?>(null) }
    // The theme can still settle (for example membership themes) before the first frame; never after.
    var startedPalette by remember { mutableStateOf<ArcPalette?>(null) }
    val palette = startedPalette ?: NuvioTheme.arcPalette
    val begun = started || failed
    LaunchedEffect(begun) {
        if (!begun) return@LaunchedEffect
        state.markShown()
        delay(EntryIdentPolicy.MinimumCycleMs)
        minimumComplete = true
    }
    LaunchedEffect(minimumComplete, state.homeReady, state.revealRequested) {
        if (state.revealRequested || EntryIdentPolicy.shouldFinish(if (minimumComplete) EntryIdentPolicy.MinimumCycleMs else 0L, state.homeReady)) {
            leaving = true
        }
    }
    LaunchedEffect(Unit) {
        delay(EntryIdentPolicy.FirstFrameTimeoutMs)
        if (!started && !leaving) failed = true
    }
    LaunchedEffect(Unit) {
        delay(EntryIdentPolicy.RecoveryTimeoutMs)
        leaving = true // Expose Home's existing loading/error/retry and navigation surfaces.
    }
    LaunchedEffect(leaving) {
        if (!leaving) return@LaunchedEffect
        state.beginReveal()
        val view = surface
        sound?.fadeOutAndRelease(EntryIdentPolicy.ExitMs)
        if (view != null && !failed && started) {
            // The surface is opaque until it fades, so Home can sit behind it from here on.
            backdrop = false
            repeat(2) { withFrameNanos { } }
            withTimeoutOrNull(EntryIdentPolicy.ExitMs + 1_500L) {
                suspendCancellableCoroutine<Unit> { cont -> view.exit(EntryIdentPolicy.ExitMs) { if (cont.isActive) cont.resume(Unit) } }
            }
        } else {
            view?.exit(0L) {}
            withFrameNanos { }
            opacity.animateTo(0f, tween(EntryIdentPolicy.ExitMs.toInt()))
        }
        state.finish()
    }
    BackHandler { state.finish(); onReturnToProfiles() }
    val description = stringResource(R.string.cd_nuvio_logo)
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = opacity.value }
            .then(if (backdrop) Modifier.background(IdentBackdrop) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (failed) {
            BrandWordmark(Modifier.height(72.dp), contentDescription = description)
        } else {
            key(palette) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        lateinit var view: ArcIgnitionView
                        view = ArcIgnitionView(
                            context = context,
                            palette = palette,
                            onStarted = {
                                if (surface === view) {
                                    if (!started && !leaving) sound?.play()
                                    startedPalette = palette
                                    started = true
                                }
                            },
                            onFailed = { if (surface === view) failed = true }
                        )
                        view.also { it.contentDescription = description; surface = it }
                    },
                    onRelease = { if (surface === it) surface = null }
                )
            }
        }
    }
}

private val IdentBackdrop = Color(0xFF03050A)
