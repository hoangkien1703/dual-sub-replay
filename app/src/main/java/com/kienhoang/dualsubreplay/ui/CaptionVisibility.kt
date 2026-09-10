package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

internal const val ORIGINAL_VISIBILITY = "original_caption_visibility"
internal const val TRANSLATED_VISIBILITY = "translated_caption_visibility"

enum class CaptionVisibility(
    val label: String,
) {
    ALWAYS("Always"),
    PAUSED("Only when paused"),
    NEVER("Never"),
    ;

    fun visible(paused: Boolean): Boolean = this == ALWAYS || (this == PAUSED && paused)
}

internal fun storedCaptionVisibility(value: String?): CaptionVisibility =
    CaptionVisibility.entries.firstOrNull { it.name == value } ?: CaptionVisibility.ALWAYS

internal fun DualSubUiState.showOriginal() = originalVisibility.visible(playbackPaused)

internal fun DualSubUiState.showTranslation() = translatedVisibility.visible(playbackPaused)

/** Hiding the transcript for an overlay must not disconnect its live timing source. */
internal fun shouldCaptureCaptionsForPresentation(
    state: DualSubUiState,
    mode: PlayerExperienceMode,
): Boolean =
    (state.subtitlePanelVisible || mode == PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY) &&
        (state.liveFallback || shouldCaptureLiveCaptions(state.generatedCaptions, state.wordHighlightEnabled))

@Composable
internal fun CaptionVisibilitySettings() {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("dual_sub_preferences", 0) }
    Column {
        listOf(ORIGINAL_VISIBILITY to "Original captions", TRANSLATED_VISIBILITY to "Translated captions").forEach { (key, label) ->
            var selected by remember { mutableStateOf(storedCaptionVisibility(preferences.getString(key, null))) }
            var expanded by remember { mutableStateOf(false) }
            Column {
                TextButton(onClick = { expanded = true }) { Text("$label: ${selected.label}") }
                DropdownMenu(expanded, { expanded = false }) {
                    CaptionVisibility.entries.forEach { mode ->
                        DropdownMenuItem(text = { Text(mode.label) }, onClick = {
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

internal fun shouldSuppressNativeCaptions(
    state: DualSubUiState,
    liveCapture: Boolean,
    mode: PlayerExperienceMode,
): Boolean =
    liveCapture || mode == PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY ||
        state.originalVisibility != CaptionVisibility.ALWAYS || state.translatedVisibility != CaptionVisibility.ALWAYS
