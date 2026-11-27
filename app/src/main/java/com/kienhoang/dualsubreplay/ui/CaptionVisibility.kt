package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
 * The gear popup's Show while playing toggle (owner pick 2026-11-10, turned around 2026-11-23 so a tick
 * means the line shows): ticked only when the line always shows, so Never counts as not shown.
 */
internal val CaptionVisibility.shownWhilePlaying: Boolean
    get() = this == CaptionVisibility.ALWAYS

/** A tap on the Show while playing toggle switches Always and Only when paused; from Never it shows the line again. */
internal fun CaptionVisibility.toggledShowWhilePlaying(): CaptionVisibility =
    if (this == CaptionVisibility.ALWAYS) CaptionVisibility.PAUSED else CaptionVisibility.ALWAYS

internal fun storedCaptionVisibility(value: String?): CaptionVisibility =
    CaptionVisibility.entries.firstOrNull { it.name == value } ?: CaptionVisibility.ALWAYS

internal fun DualSubUiState.showOriginal() = studyCaptionVisibility(originalVisibility, studyPlayMode).visible(playbackPaused)

internal fun DualSubUiState.showTranslation() = studyCaptionVisibility(translatedVisibility, studyPlayMode).visible(playbackPaused)

/**
 * The subtitle panel has something to show; Dictation and Listening practice keep it while the video
 * plays, as their rows hide the lines.
 */
internal fun DualSubUiState.tracksVisible() = showOriginal() || showTranslation() || dictationInPanel() || listeningInPanel()

/**
 * Listening practice (owner pick 2026-11-27): neither line shows while the video plays, but at least
 * one is not set to Never. The panel then stays, each line hidden as dots until the learner taps them.
 */
internal fun listeningPractice(
    original: CaptionVisibility,
    translated: CaptionVisibility,
): Boolean =
    !original.shownWhilePlaying && !translated.shownWhilePlaying &&
        (original != CaptionVisibility.NEVER || translated != CaptionVisibility.NEVER)

/**
 * The panel hides lines for [listeningPractice]. Listen first and Dictation keep their own rules, and
 * live subtitles have no fixed lines to hide, so they keep today's behaviour.
 */
internal fun DualSubUiState.listeningInPanel(): Boolean =
    !liveFallback && !studyPlayMode.hidesWhilePlaying && listeningPractice(originalVisibility, translatedVisibility)

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
 * The gear popup's Show while playing row: the label, then an Original and a Translation chip. The
 * Original chip came back with Listening practice (owner pick 2026-11-27): with both off the panel
 * no longer disappears, it hides each line as dots, so the video never looks blank. No hint text
 * under the chips (owner, 2026-11-27): people find out by trying.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShowWhilePlayingRow(
    original: CaptionVisibility,
    translated: CaptionVisibility,
    onChange: (original: Boolean, visibility: CaptionVisibility) -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("show_while_playing"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_show_while_playing), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShowWhilePlayingChip(R.string.settings_colored_lines_original, original, "show_original_while_playing") {
                onChange(true, original.toggledShowWhilePlaying())
            }
            ShowWhilePlayingChip(R.string.settings_colored_lines_translation, translated, "show_translation_while_playing") {
                onChange(false, translated.toggledShowWhilePlaying())
            }
        }
    }
}

@Composable
private fun ShowWhilePlayingChip(
    @StringRes labelRes: Int,
    visibility: CaptionVisibility,
    tag: String,
    onClick: () -> Unit,
) {
    val selected = visibility.shownWhilePlaying
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
