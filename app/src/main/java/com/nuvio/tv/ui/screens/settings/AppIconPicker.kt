@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AppIconOption
import com.nuvio.tv.domain.model.availableAppIcons
import com.nuvio.tv.launcher.AppIconSettingsState
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.membership.Membership
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AppIconPickerDialog(
    state: AppIconSettingsState,
    onSelected: (AppIconOption) -> Unit,
    onDismiss: () -> Unit
) {
    val selectedFocusRequester = remember { FocusRequester() }

    LaunchedEffect(state.selected) {
        selectedFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.appearance_app_icon_picker_title),
        subtitle = stringResource(
            if (state.changeFailed) {
                R.string.appearance_app_icon_change_failed
            } else {
                R.string.appearance_app_icon_picker_subtitle
            }
        ),
        width = 760.dp,
        suppressFirstKeyUp = false,
        usePlatformDefaultWidth = false,
        contentPadding = 18.dp,
        contentSpacing = 12.dp
    ) {
        val entitlements = Membership.access.entitlements
        val options = remember(entitlements, state.selected) { availableAppIcons(entitlements, state.selected) }
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            options.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    row.forEach { option ->
                        AppIconOptionCard(
                            option = option,
                            selected = state.selected == option,
                            enabled = state.pending == null,
                            onClick = { onSelected(option) },
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    if (state.selected == option) {
                                        Modifier.focusRequester(selectedFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }
                    repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
internal fun AppIconChangeConfirmationDialog(
    option: AppIconOption,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val confirmFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        confirmFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.appearance_app_icon_confirmation_title),
        subtitle = stringResource(
            R.string.appearance_app_icon_confirmation_message,
            option.localizedName()
        ),
        suppressFirstKeyUp = false
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Button(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(confirmFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(android.R.string.ok))
            }
        }
    }
}

@Composable
private fun AppIconOptionCard(
    option: AppIconOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    Card(
        onClick = { if (enabled) onClick() },
        modifier = modifier.alpha(if (enabled) 1f else 0.55f),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(
            border = if (selected) {
                Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.hairline),
                    shape = shape
                )
            } else {
                Border.None
            },
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = shape
            )
        ),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            val thumbnail = rememberBannerThumbnail(option.bannerResource)
            val thumbnailModifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
            if (thumbnail != null) {
                Image(bitmap = thumbnail, contentDescription = null, modifier = thumbnailModifier, contentScale = ContentScale.Fit)
            } else {
                Box(thumbnailModifier)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(option.iconResource),
                    contentDescription = null,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(colorResource(R.color.arc_launcher_background))
                        .graphicsLayer {
                            scaleX = AdaptiveIconVisibleScale
                            scaleY = AdaptiveIconVisibleScale
                        },
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = option.localizedName(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (selected) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.cd_selected),
                        tint = NuvioTheme.colors.Primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// The launcher entries are adaptive icons on API 26+, which painterResource cannot draw.
private const val AdaptiveIconVisibleScale = 108f / 72f

internal val AppIconOption.iconResource: Int
    get() = when (this) {
        AppIconOption.ORIGINAL -> R.drawable.arc_launcher_foreground
        AppIconOption.ARCTIC_BLUE -> R.drawable.arc_launcher_foreground_arctic_blue
        AppIconOption.EMERALD -> R.drawable.arc_launcher_foreground_emerald
        AppIconOption.ROSE_GOLD -> R.drawable.arc_launcher_foreground_rose_gold
        AppIconOption.COPPER -> R.drawable.arc_launcher_foreground_copper
        AppIconOption.GRAPHITE -> R.drawable.arc_launcher_foreground_graphite
        AppIconOption.GOLD -> R.drawable.arc_launcher_foreground_gold
        AppIconOption.JADE -> R.drawable.arc_launcher_foreground_jade
        AppIconOption.GLASS -> R.drawable.arc_launcher_foreground_glass
        AppIconOption.CRIMSON -> R.drawable.arc_launcher_foreground_crimson
        AppIconOption.OCEAN -> R.drawable.arc_launcher_foreground_ocean
        AppIconOption.VIOLET -> R.drawable.arc_launcher_foreground_violet
        AppIconOption.EMERALD_GREEN -> R.drawable.arc_launcher_foreground_emerald_green
        AppIconOption.AMBER -> R.drawable.arc_launcher_foreground_amber
        AppIconOption.ROSE -> R.drawable.arc_launcher_foreground_rose
        AppIconOption.WHITE -> R.drawable.arc_launcher_foreground_white
    }

internal val AppIconOption.bannerResource: Int
    get() = when (this) {
        AppIconOption.ORIGINAL -> R.mipmap.banner
        AppIconOption.ARCTIC_BLUE -> R.mipmap.banner_arctic_blue
        AppIconOption.EMERALD -> R.mipmap.banner_emerald
        AppIconOption.ROSE_GOLD -> R.mipmap.banner_rose_gold
        AppIconOption.COPPER -> R.mipmap.banner_copper
        AppIconOption.GRAPHITE -> R.mipmap.banner_graphite
        AppIconOption.GOLD -> R.mipmap.banner_gold
        AppIconOption.JADE -> R.mipmap.banner_jade
        AppIconOption.GLASS -> R.mipmap.banner_glass
        AppIconOption.CRIMSON -> R.mipmap.banner_crimson
        AppIconOption.OCEAN -> R.mipmap.banner_ocean
        AppIconOption.VIOLET -> R.mipmap.banner_violet
        AppIconOption.EMERALD_GREEN -> R.mipmap.banner_emerald_green
        AppIconOption.AMBER -> R.mipmap.banner_amber
        AppIconOption.ROSE -> R.mipmap.banner_rose
        AppIconOption.WHITE -> R.mipmap.banner_white
    }

@Composable
internal fun AppIconOption.localizedName(): String = when (this) {
    AppIconOption.ORIGINAL -> stringResource(R.string.appearance_app_icon_original)
    AppIconOption.ARCTIC_BLUE -> stringResource(R.string.appearance_app_icon_arctic_blue)
    AppIconOption.EMERALD -> stringResource(R.string.appearance_app_icon_mint)
    AppIconOption.ROSE_GOLD -> stringResource(R.string.appearance_app_icon_rose_gold)
    AppIconOption.COPPER -> stringResource(R.string.appearance_app_icon_copper)
    AppIconOption.GRAPHITE -> stringResource(R.string.appearance_app_icon_graphite)
    AppIconOption.GOLD -> stringResource(R.string.theme_color_gold)
    AppIconOption.JADE -> stringResource(R.string.theme_color_jade)
    AppIconOption.GLASS -> stringResource(R.string.theme_color_glass)
    AppIconOption.CRIMSON -> stringResource(R.string.theme_color_crimson)
    AppIconOption.OCEAN -> stringResource(R.string.theme_color_ocean)
    AppIconOption.VIOLET -> stringResource(R.string.theme_color_violet)
    AppIconOption.EMERALD_GREEN -> stringResource(R.string.theme_color_emerald)
    AppIconOption.AMBER -> stringResource(R.string.theme_color_amber)
    AppIconOption.ROSE -> stringResource(R.string.theme_color_rose)
    AppIconOption.WHITE -> stringResource(R.string.theme_color_white)
}

/** The cards are a third of the dialog wide, so the launcher banners are decoded at half size, off the main thread. */
@Composable
private fun rememberBannerThumbnail(resource: Int): ImageBitmap? {
    val resources = LocalContext.current.resources
    val thumbnail by produceState<ImageBitmap?>(null, resource) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                BitmapFactory.decodeResource(resources, resource, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return thumbnail
}
