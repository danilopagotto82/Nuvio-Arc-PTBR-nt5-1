package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.RemoteButtonBinding
import com.nuvio.tv.data.local.RemoteButtonJob
import com.nuvio.tv.data.local.RemoteButtonMap
import com.nuvio.tv.data.local.RemoteKeyKind
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.player.remoteBindingLabel
import com.nuvio.tv.ui.screens.player.remoteJobDescription
import com.nuvio.tv.ui.screens.player.remoteJobLabel
import com.nuvio.tv.ui.screens.player.remoteKeyLabel
import com.nuvio.tv.ui.screens.player.remoteSkipSizeLabel
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private const val LEARN_HINT_DELAY_MS = 10_000L

@Composable
internal fun PlaybackRemoteButtonsSection(
    map: RemoteButtonMap,
    onChange: (RemoteButtonMap) -> Unit
) {
    val context = LocalContext.current
    var learning by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf<Int?>(null) }
    var sizeFor by remember { mutableStateOf<Pair<Int, RemoteButtonJob>?>(null) }

    RemoteButtonsNote(stringResource(R.string.remote_buttons_intro))
    SettingsActionRow(
        title = stringResource(R.string.remote_buttons_setup_title),
        subtitle = stringResource(R.string.remote_buttons_setup_subtitle),
        leadingIcon = Icons.Default.Add,
        onClick = { learning = true }
    )
    map.rowKeys.forEach { key ->
        if (key == RemoteButtonMap.NUMBER_KEYS_ROW) {
            SettingsActionRow(
                title = stringResource(R.string.remote_buttons_number_keys),
                subtitle = stringResource(R.string.remote_buttons_number_keys_desc),
                value = remoteJobLabel(
                    context,
                    if (map.numberKeysJump) RemoteButtonJob.JUMP_TO_PERCENT else RemoteButtonJob.NOTHING
                ),
                onClick = { editingKey = key }
            )
        } else {
            SettingsActionRow(
                title = remoteKeyLabel(context, key),
                subtitle = if (map.isCustomKey(key)) stringResource(R.string.remote_buttons_added) else null,
                value = remoteBindingLabel(context, map.bindingFor(key) ?: RemoteButtonBinding.NOTHING),
                onClick = { editingKey = key }
            )
        }
    }
    SettingsActionRow(
        title = stringResource(R.string.remote_buttons_reset),
        subtitle = null,
        trailingIcon = null,
        enabled = map != RemoteButtonMap.DEFAULT,
        onClick = { onChange(RemoteButtonMap.DEFAULT) }
    )
    RemoteButtonsNote(stringResource(R.string.remote_buttons_mapper_note))

    if (learning) {
        RemoteButtonLearnDialog(
            onDismiss = { learning = false },
            onLearned = { key ->
                learning = false
                editingKey = key
            }
        )
    }

    val key = editingKey
    if (key == RemoteButtonMap.NUMBER_KEYS_ROW) {
        val options = listOf(RemoteButtonJob.JUMP_TO_PERCENT, RemoteButtonJob.NOTHING)
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.remote_buttons_job_title, stringResource(R.string.remote_buttons_number_keys)),
            options = options.map { SettingsPickerOption(it, remoteJobLabel(context, it), remoteJobDescription(context, it)) },
            selectedValue = if (map.numberKeysJump) RemoteButtonJob.JUMP_TO_PERCENT else RemoteButtonJob.NOTHING,
            onOptionSelected = {
                onChange(map.withNumberKeysJump(it == RemoteButtonJob.JUMP_TO_PERCENT))
                editingKey = null
            },
            onDismiss = { editingKey = null }
        )
    } else if (key != null) {
        val current = map.bindingFor(key) ?: RemoteButtonBinding.NOTHING
        val options = buildList<SettingsPickerOption<RemoteButtonJob?>> {
            RemoteButtonMap.jobsFor(key).forEach {
                add(SettingsPickerOption(it, remoteJobLabel(context, it), remoteJobDescription(context, it)))
            }
            if (map.isCustomKey(key) && key in map.rowKeys) {
                add(SettingsPickerOption(null, stringResource(R.string.remote_buttons_remove)))
            }
        }
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.remote_buttons_job_title, remoteKeyLabel(context, key)),
            options = options,
            selectedValue = current.job,
            width = 480.dp,
            maxHeight = 380.dp,
            onOptionSelected = { job ->
                editingKey = null
                when {
                    job == null -> onChange(map.without(key))
                    job.needsSkipSize -> sizeFor = key to job
                    else -> onChange(map.with(key, RemoteButtonBinding(job)))
                }
            },
            onDismiss = { editingKey = null }
        )
    }

    sizeFor?.let { (sizeKey, job) ->
        val current = map.bindingFor(sizeKey)?.takeIf { it.job == job }?.skipMs ?: RemoteButtonMap.DEFAULT_SKIP_MS
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.remote_buttons_skip_size_title, remoteKeyLabel(context, sizeKey)),
            subtitle = stringResource(R.string.remote_buttons_skip_size_subtitle),
            options = RemoteButtonMap.SKIP_SIZES_MS.map { SettingsPickerOption(it, remoteSkipSizeLabel(context, it)) },
            selectedValue = current,
            onOptionSelected = { ms ->
                onChange(map.with(sizeKey, RemoteButtonBinding(job, ms)))
                sizeFor = null
            },
            onDismiss = { sizeFor = null }
        )
    }
}

@Composable
private fun RemoteButtonsNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = NuvioTheme.colors.TextSecondary,
        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)
    )
}

@Composable
private fun RemoteButtonLearnDialog(
    onDismiss: () -> Unit,
    onLearned: (Int) -> Unit
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    var message by remember { mutableStateOf<String?>(null) }
    var waitedLong by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focusRequester.requestFocusAfterFrames() }
    LaunchedEffect(Unit) {
        delay(LEARN_HINT_DELAY_MS)
        waitedLong = true
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.remote_buttons_learn_title),
        subtitle = stringResource(R.string.remote_buttons_learn_subtitle)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    val code = native.keyCode
                    if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_BUTTON_B) {
                        return@onPreviewKeyEvent false
                    }
                    when (RemoteButtonMap.kindOf(code)) {
                        RemoteKeyKind.IGNORED -> false
                        RemoteKeyKind.VOLUME -> {
                            if (native.action == KeyEvent.ACTION_DOWN) {
                                message = context.getString(R.string.remote_buttons_learn_volume)
                            }
                            false
                        }
                        RemoteKeyKind.LOCKED -> {
                            if (native.action == KeyEvent.ACTION_DOWN) {
                                message = context.getString(R.string.remote_buttons_learn_locked, remoteKeyLabel(context, code))
                            }
                            true
                        }
                        RemoteKeyKind.ASSIGNABLE -> {
                            if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                                onLearned(RemoteButtonMap.learnTarget(code))
                            }
                            true
                        }
                    }
                }
        ) {
            Text(
                text = message ?: if (waitedLong) {
                    stringResource(R.string.remote_buttons_learn_nothing)
                } else {
                    stringResource(R.string.remote_buttons_learn_waiting)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(vertical = NuvioTheme.spacing.md)
            )
        }
    }
}
