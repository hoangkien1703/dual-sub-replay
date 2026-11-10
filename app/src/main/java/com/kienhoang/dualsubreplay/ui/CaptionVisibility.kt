package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R

internal const val ORIGINAL_VISIBILITY = "original_caption_visibility"
internal const val TRANSLATED_VISIBILITY = "translated_caption_visibility"

enum class CaptionVisibility(
    @StringRes val labelRes: Int,
) {
    ALWAYS(R.string.settings_caption_visibility_always),
    PAUSED(R.string.settings_caption_visibility_paused),
    NEVER(R.string.settings_caption_visibility_never),
    ;

    fun visible(paused: Boolean): Boolean = this == ALWAYS || (this == PAUSED && paused)
}

/**
 * The gear popup's Hide while playing toggles (owner pick 2026-11-10): ticked unless the line always
 * shows, so Never counts as hidden too.
 */
internal val CaptionVisibility.hiddenWhilePlaying: Boolean
    get() = this != CaptionVisibility.ALWAYS

/** A tap on a Hide while playing toggle switches Always and Only when paused; from Never it shows the line again. */
internal fun CaptionVisibility.toggledHideWhilePlaying(): CaptionVisibility =
    if (this == CaptionVisibility.ALWAYS) CaptionVisibility.PAUSED else CaptionVisibility.ALWAYS

internal fun storedCaptionVisibility(value: String?): CaptionVisibility =
    CaptionVisibility.entries.firstOrNull { it.name == value } ?: CaptionVisibility.ALWAYS

internal fun DualSubUiState.showOriginal() = studyCaptionVisibility(originalVisibility, studyPlayMode).visible(playbackPaused)

internal fun DualSubUiState.showTranslation() = studyCaptionVisibility(translatedVisibility, studyPlayMode).visible(playbackPaused)

/** The subtitle panel has something to show; Dictation keeps it while the video plays, as its rows and box hide the lines. */
internal fun DualSubUiState.tracksVisible() = showOriginal() || showTranslation() || dictationInPanel()

/** Hiding the transcript for an overlay must not disconnect its live timing source. */
internal fun shouldCaptureCaptionsForPresentation(
    state: DualSubUiState,
    mode: PlayerExperienceMode,
): Boolean =
    (state.subtitlePanelVisible || mode == PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY) &&
        (state.liveFallback || shouldCaptureLiveCaptions(state.generatedCaptions, state.wordHighlightEnabled))

/** Only a loaded transcript sets the page's track; live fallback takes its language from the page. */
internal fun captionTrackTarget(state: DualSubUiState): CaptionTrackTarget? {
    if (state.liveFallback) return null
    val videoId = state.activeVideoId ?: return null
    val language = state.resolvedSourceLanguage ?: return null
    return CaptionTrackTarget(videoId, language, state.generatedCaptions)
}

@Composable
internal fun CaptionVisibilitySettings() {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("dual_sub_preferences", 0) }
    Column {
        listOf(
            ORIGINAL_VISIBILITY to R.string.settings_original_captions_visibility,
            TRANSLATED_VISIBILITY to R.string.settings_translated_captions_visibility,
        ).forEach { (key, labelRes) ->
            var selected by remember { mutableStateOf(storedCaptionVisibility(preferences.getString(key, null))) }
            var expanded by remember { mutableStateOf(false) }
            Column(Modifier.settingAnchor(key)) {
                TextButton(onClick = { expanded = true }) { Text(stringResource(labelRes, stringResource(selected.labelRes))) }
                DropdownMenu(expanded, { expanded = false }) {
                    CaptionVisibility.entries.forEach { mode ->
                        DropdownMenuItem(text = { Text(stringResource(mode.labelRes)) }, onClick = {
                            selected = mode
                            preferences.edit().putString(key, mode.name).apply()
                            expanded = false
                        })
                    }
                }
            }
        }
    }
}

/**
 * One line in the gear popup for the setting people flip most: translated lines only when paused.
 * Original stays on the settings page, so the popup can't hide both lines and leave the video blank.
 */
@Composable
internal fun HideWhilePlayingRow(
    translated: CaptionVisibility,
    onChange: (original: Boolean, visibility: CaptionVisibility) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().testTag("hide_while_playing"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.settings_hide_while_playing),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        HideWhilePlayingChip(R.string.settings_colored_lines_translation, translated, "hide_translation_while_playing") {
            onChange(false, translated.toggledHideWhilePlaying())
        }
    }
}

@Composable
private fun HideWhilePlayingChip(
    @StringRes labelRes: Int,
    visibility: CaptionVisibility,
    tag: String,
    onClick: () -> Unit,
) {
    val selected = visibility.hiddenWhilePlaying
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(stringResource(labelRes)) },
        leadingIcon =
            if (selected) {
                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
            } else {
                null
            },
        modifier = Modifier.testTag(tag),
    )
}

internal fun shouldSuppressNativeCaptions(
    state: DualSubUiState,
    liveCapture: Boolean,
    mode: PlayerExperienceMode,
): Boolean =
    liveCapture || mode == PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY ||
        state.originalVisibility != CaptionVisibility.ALWAYS || state.translatedVisibility != CaptionVisibility.ALWAYS ||
        state.studyPlayMode.hidesWhilePlaying
