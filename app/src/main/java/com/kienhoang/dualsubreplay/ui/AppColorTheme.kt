package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance
import com.kienhoang.dualsubreplay.R

const val APP_COLOR_THEME_PREFERENCE = "app_color_theme"

/**
 * The colors of the whole app: backgrounds, cards, dialogs and text. [hue] and [saturation] recolor
 * the dark teal palette ([saturation] scales it); teal keeps it as it is, and light has its own.
 * The overlay on the video keeps its own background ([SubtitleBoxBackgroundOption]).
 */
internal enum class AppColorThemeOption(
    override val key: String,
    @StringRes override val labelRes: Int,
    override val argb: Long,
    val hue: Float? = null,
    val saturation: Float = 1f,
) : ColorSettingOption {
    DEEP_TEAL("deep_teal", R.string.settings_color_deep_teal, 0xFF0C2023),
    BLACK("black", R.string.settings_color_black, 0xFF000000, saturation = 0f),
    NAVY("navy", R.string.settings_color_navy, 0xFF0E1A30, hue = 220f),
    SLATE("slate", R.string.settings_color_slate, 0xFF1C2228, hue = 210f, saturation = 0.35f),
    PLUM("plum", R.string.settings_color_plum, 0xFF22132B, hue = 285f),
    FOREST("forest", R.string.settings_color_forest, 0xFF0D2219, hue = 152f),
    LIGHT("light", R.string.settings_color_light, 0xFFF4F8F8),
    ;

    val isLight: Boolean get() = this == LIGHT
}

internal val DEFAULT_APP_COLOR_THEME_KEY = AppColorThemeOption.DEEP_TEAL.key

internal fun storedAppColorTheme(raw: String?): AppColorThemeOption =
    AppColorThemeOption.entries.firstOrNull { it.key == raw } ?: AppColorThemeOption.DEEP_TEAL

/** True while the app shows the light theme, so subtitle text on app surfaces uses its dark shades. */
@Composable
internal fun appThemeIsLight(): Boolean = MaterialTheme.colorScheme.background.luminance() > LIGHT_BACKGROUND_LUMINANCE

private const val LIGHT_BACKGROUND_LUMINANCE = 0.5f
