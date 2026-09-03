package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.graphics.Color

const val SUBTITLE_ORIGINAL_COLOR_PREFERENCE = "subtitle_original_color"
const val SUBTITLE_TRANSLATED_COLOR_PREFERENCE = "subtitle_translated_color"
const val SUBTITLE_HIGHLIGHT_COLOR_PREFERENCE = "subtitle_highlight_color"
const val SUBTITLE_BOX_BACKGROUND_PREFERENCE = "subtitle_box_background"
const val APP_THEME_ACCENT_PREFERENCE = "app_theme_accent"
const val WORD_HIGHLIGHT_ENABLED_PREFERENCE = "highlight_spoken_words_enabled"
const val CUSTOM_SUBTITLE_COLORS_ENABLED_PREFERENCE = "custom_subtitle_colors_enabled"

/** Default-on toggles so users can turn the issue #21 features off entirely. */
internal fun storedFeatureEnabled(raw: Boolean?, fallback: Boolean = true): Boolean =
    raw ?: fallback

/** Common shape for the preset color swatches used by subtitle and theme settings. */
internal interface ColorSettingOption {
    val key: String
    val label: String
    val argb: Long
}

/**
 * Curated palette for subtitle text so custom colors stay readable on the dark
 * caption surfaces (issue #21).
 */
internal enum class SubtitleColorOption(
    override val key: String,
    override val label: String,
    override val argb: Long,
) : ColorSettingOption {
    ICE_WHITE("ice_white", "Ice white", 0xFFF3FAFA),
    SKY_BLUE("sky_blue", "Sky blue", 0xFF9EDCE4),
    MINT("mint", "Mint", 0xFF75E7C1),
    AMBER("amber", "Amber", 0xFFFFD54F),
    ROSE("rose", "Rose", 0xFFFF8A80),
    LAVENDER("lavender", "Lavender", 0xFFC5A3FF),
}

/** Semi-opaque backgrounds keep subtitles readable while preserving video context. */
internal enum class SubtitleBoxBackgroundOption(
    override val key: String,
    override val label: String,
    override val argb: Long,
) : ColorSettingOption {
    DEEP_TEAL("deep_teal", "Deep teal", 0xD7061719),
    BLACK("black", "Black", 0xE6000000),
    NAVY("navy", "Navy", 0xE6121B2D),
    SLATE("slate", "Slate", 0xE6242A30),
    PLUM("plum", "Plum", 0xE62A1834),
    FOREST("forest", "Forest", 0xE6102A24),
}

/** Bright accents are used for switches, sliders, buttons, active borders, and controls. */
internal enum class AppThemeAccentOption(
    override val key: String,
    override val label: String,
    override val argb: Long,
) : ColorSettingOption {
    CYAN("cyan", "Cyan", 0xFF13C6D7),
    BLUE("blue", "Blue", 0xFF4FA3FF),
    MINT("mint", "Mint", 0xFF5ED6A3),
    AMBER("amber", "Amber", 0xFFFFC857),
    ROSE("rose", "Rose", 0xFFFF7A8A),
    LAVENDER("lavender", "Lavender", 0xFFB08CFF),
}

internal val DEFAULT_ORIGINAL_COLOR_KEY = SubtitleColorOption.ICE_WHITE.key
internal val DEFAULT_TRANSLATED_COLOR_KEY = SubtitleColorOption.SKY_BLUE.key
internal val DEFAULT_HIGHLIGHT_COLOR_KEY = SubtitleColorOption.MINT.key
internal val DEFAULT_SUBTITLE_BOX_BACKGROUND_KEY = SubtitleBoxBackgroundOption.DEEP_TEAL.key
internal val DEFAULT_APP_THEME_ACCENT_KEY = AppThemeAccentOption.CYAN.key

internal fun storedSubtitleColorKey(raw: String?, fallback: String): String =
    SubtitleColorOption.entries.firstOrNull { it.key == raw }?.key ?: fallback

internal fun subtitleColor(key: String): Color =
    SubtitleColorOption.entries
        .firstOrNull { it.key == key }
        ?.let { Color(it.argb) }
        ?: Color(SubtitleColorOption.ICE_WHITE.argb)

internal fun storedSubtitleBoxBackgroundKey(raw: String?): String =
    SubtitleBoxBackgroundOption.entries.firstOrNull { it.key == raw }?.key
        ?: DEFAULT_SUBTITLE_BOX_BACKGROUND_KEY

internal fun subtitleBoxBackgroundColor(key: String): Color =
    SubtitleBoxBackgroundOption.entries
        .firstOrNull { it.key == key }
        ?.let { Color(it.argb) }
        ?: Color(SubtitleBoxBackgroundOption.DEEP_TEAL.argb)

internal fun storedAppThemeAccentKey(raw: String?): String =
    AppThemeAccentOption.entries.firstOrNull { it.key == raw }?.key
        ?: DEFAULT_APP_THEME_ACCENT_KEY

internal fun appThemeAccentColor(key: String): Color =
    AppThemeAccentOption.entries
        .firstOrNull { it.key == key }
        ?.let { Color(it.argb) }
        ?: Color(AppThemeAccentOption.CYAN.argb)

/** Custom text colors apply only while the "Custom subtitle colors" switch is on. */
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
    OVERLAY_HORIZONTAL_POSITION_PREFERENCE,
    MOVABLE_OVERLAY_PREFERENCE,
    COLLAPSED_CC_HORIZONTAL_POSITION_PREFERENCE,
    COLLAPSED_CC_VERTICAL_POSITION_PREFERENCE,
    SPLIT_LONG_SENTENCES_PREFERENCE,
    LANDSCAPE_VIDEO_FRACTION_PREFERENCE,
    SUBTITLE_ORIGINAL_COLOR_PREFERENCE,
    SUBTITLE_TRANSLATED_COLOR_PREFERENCE,
    SUBTITLE_HIGHLIGHT_COLOR_PREFERENCE,
    SUBTITLE_BOX_BACKGROUND_PREFERENCE,
    APP_THEME_ACCENT_PREFERENCE,
    WORD_HIGHLIGHT_ENABLED_PREFERENCE,
    KARAOKE_TIMING_MODE_PREFERENCE,
    CUSTOM_SUBTITLE_COLORS_ENABLED_PREFERENCE,
    LOCK_OVERLAY_TO_VIDEO_PREFERENCE,
    PRELOAD_MODELS_ENABLED_PREFERENCE,
    NATURAL_SUBTITLES_PREFERENCE,
    WORD_LEARNING_ENABLED_PREFERENCE,
    WORD_LEARNING_TARGET_PREFERENCE,
    TAP_TO_LEARN_PREFERENCE,
)
