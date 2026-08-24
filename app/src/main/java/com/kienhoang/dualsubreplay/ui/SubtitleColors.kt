package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.graphics.Color

const val SUBTITLE_ORIGINAL_COLOR_PREFERENCE = "subtitle_original_color"
const val SUBTITLE_TRANSLATED_COLOR_PREFERENCE = "subtitle_translated_color"
const val SUBTITLE_HIGHLIGHT_COLOR_PREFERENCE = "subtitle_highlight_color"
const val WORD_HIGHLIGHT_ENABLED_PREFERENCE = "highlight_spoken_words_enabled"
const val CUSTOM_SUBTITLE_COLORS_ENABLED_PREFERENCE = "custom_subtitle_colors_enabled"

/** Default-on toggles so users can turn the issue #21 features off entirely. */
internal fun storedFeatureEnabled(raw: Boolean?, fallback: Boolean = true): Boolean =
    raw ?: fallback

/**
 * Curated palette for subtitle text so custom colors stay readable on the dark
 * caption surfaces (issue #21).
 */
enum class SubtitleColorOption(
    val key: String,
    val label: String,
    val argb: Long,
) {
    ICE_WHITE("ice_white", "Ice white", 0xFFF3FAFA),
    SKY_BLUE("sky_blue", "Sky blue", 0xFF9EDCE4),
    MINT("mint", "Mint", 0xFF75E7C1),
    AMBER("amber", "Amber", 0xFFFFD54F),
    ROSE("rose", "Rose", 0xFFFF8A80),
    LAVENDER("lavender", "Lavender", 0xFFC5A3FF),
}

internal val DEFAULT_ORIGINAL_COLOR_KEY = SubtitleColorOption.ICE_WHITE.key
internal val DEFAULT_TRANSLATED_COLOR_KEY = SubtitleColorOption.SKY_BLUE.key
internal val DEFAULT_HIGHLIGHT_COLOR_KEY = SubtitleColorOption.MINT.key

internal fun storedSubtitleColorKey(raw: String?, fallback: String): String =
    SubtitleColorOption.entries.firstOrNull { it.key == raw }?.key ?: fallback

internal fun subtitleColor(key: String): Color =
    SubtitleColorOption.entries
        .firstOrNull { it.key == key }
        ?.let { Color(it.argb) }
        ?: Color(SubtitleColorOption.ICE_WHITE.argb)

/** Custom colors apply only while the "Custom subtitle colors" switch is on. */
internal fun effectiveOriginalColor(state: DualSubUiState): Color = if (
    state.customColorsEnabled
) {
    subtitleColor(state.originalColorKey)
} else {
    subtitleColor(DEFAULT_ORIGINAL_COLOR_KEY)
}

internal fun effectiveTranslatedColor(state: DualSubUiState): Color = if (
    state.customColorsEnabled
) {
    subtitleColor(state.translatedColorKey)
} else {
    subtitleColor(DEFAULT_TRANSLATED_COLOR_KEY)
}

internal fun effectiveHighlightColor(state: DualSubUiState): Color = if (
    state.customColorsEnabled && state.wordHighlightEnabled
) {
    subtitleColor(state.highlightColorKey)
} else {
    subtitleColor(DEFAULT_HIGHLIGHT_COLOR_KEY)
}

/** Every preference key that "Reset all settings to defaults" clears (issue #22). */
val RESETTABLE_SETTING_KEYS = listOf(
    "font_scale",
    "preferred_caption_language",
    "target_language",
    "landscape_split_enabled",
    PLAYER_EXPERIENCE_MODE_PREFERENCE,
    AUTO_OVERLAY_FULLSCREEN_PREFERENCE,
    AUTO_OVERLAY_LANDSCAPE_PREFERENCE,
    AUTO_AVOID_PLAYER_CONTROLS_PREFERENCE,
    REMEMBER_OVERLAY_POSITION_PREFERENCE,
    OVERLAY_VERTICAL_POSITION_PREFERENCE,
    LANDSCAPE_VIDEO_FRACTION_PREFERENCE,
    SUBTITLE_ORIGINAL_COLOR_PREFERENCE,
    SUBTITLE_TRANSLATED_COLOR_PREFERENCE,
    SUBTITLE_HIGHLIGHT_COLOR_PREFERENCE,
    WORD_HIGHLIGHT_ENABLED_PREFERENCE,
    CUSTOM_SUBTITLE_COLORS_ENABLED_PREFERENCE,
)
