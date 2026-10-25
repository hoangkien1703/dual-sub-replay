package com.kienhoang.dualsubreplay.ui.theme

import android.content.SharedPreferences
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.kienhoang.dualsubreplay.ui.APP_COLOR_THEME_PREFERENCE
import com.kienhoang.dualsubreplay.ui.APP_THEME_ACCENT_PREFERENCE
import com.kienhoang.dualsubreplay.ui.AppColorThemeOption
import com.kienhoang.dualsubreplay.ui.DEFAULT_APP_COLOR_THEME_KEY
import com.kienhoang.dualsubreplay.ui.DEFAULT_APP_THEME_ACCENT_KEY
import com.kienhoang.dualsubreplay.ui.appThemeAccentColor
import com.kienhoang.dualsubreplay.ui.findActivity
import com.kienhoang.dualsubreplay.ui.inLineDirection
import com.kienhoang.dualsubreplay.ui.storedAppColorTheme
import com.kienhoang.dualsubreplay.ui.storedAppThemeAccentKey

/** How far the light theme darkens the accent, so buttons and links stay readable on white. */
private const val LIGHT_ACCENT_DARKENING = 0.35f
private const val LIGHT_CONTAINER_TINT = 0.82f
private const val HUE_SEXTANT = 60f
private const val FULL_CIRCLE = 360f

internal fun dualSubColorScheme(
    accentKey: String,
    themeKey: String = DEFAULT_APP_COLOR_THEME_KEY,
): ColorScheme {
    val theme = storedAppColorTheme(themeKey)
    return if (theme.isLight) lightDualSubColorScheme(appThemeAccentColor(accentKey)) else darkDualSubColorScheme(accentKey, theme)
}

/** The dark teal palette, recolored by [theme]'s hue and saturation. */
private fun darkDualSubColorScheme(
    accentKey: String,
    theme: AppColorThemeOption,
): ColorScheme {
    fun tint(argb: Long): Color = retint(Color(argb), theme.hue, theme.saturation)
    val black = theme == AppColorThemeOption.BLACK
    return darkColorScheme(
        primary = appThemeAccentColor(accentKey),
        onPrimary = tint(0xFF001F23),
        secondary = appThemeAccentColor(accentKey),
        background = if (black) Color.Black else tint(0xFF061416),
        onBackground = tint(0xFFE4F5F6),
        surface = tint(0xFF0C2023),
        onSurface = tint(0xFFE4F5F6),
        surfaceVariant = tint(0xFF173438),
        onSurfaceVariant = tint(0xFFB8CDD0),
        // Material's defaults for these are purple-grey; keep drawers, dialogs, and chips in the app's colors.
        primaryContainer = tint(0xFF0F3F45),
        onPrimaryContainer = tint(0xFFCDF3F6),
        secondaryContainer = tint(0xFF1B4448),
        onSecondaryContainer = tint(0xFFD2EEF0),
        surfaceDim = if (black) Color.Black else tint(0xFF041012),
        surfaceBright = tint(0xFF1F3A3E),
        surfaceContainerLowest = if (black) Color.Black else tint(0xFF030D0F),
        surfaceContainerLow = tint(0xFF0A1C1F),
        surfaceContainer = tint(0xFF0E2427),
        surfaceContainerHigh = tint(0xFF132D31),
        surfaceContainerHighest = tint(0xFF1A383C),
        outline = tint(0xFF6F8B8F),
        outlineVariant = tint(0xFF2A4649),
        error = Color(0xFFFFB4AB),
    )
}

private fun lightDualSubColorScheme(accent: Color): ColorScheme {
    val primary = lerp(accent, Color.Black, LIGHT_ACCENT_DARKENING)
    return lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        secondary = primary,
        onSecondary = Color.White,
        background = Color(0xFFF4F8F8),
        onBackground = Color(0xFF0F1D1F),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF0F1D1F),
        surfaceVariant = Color(0xFFDCE6E7),
        onSurfaceVariant = Color(0xFF3E5356),
        primaryContainer = lerp(accent, Color.White, LIGHT_CONTAINER_TINT),
        onPrimaryContainer = Color(0xFF0B2E33),
        secondaryContainer = Color(0xFFD3E6E8),
        onSecondaryContainer = Color(0xFF0F2E31),
        surfaceDim = Color(0xFFD8E0E1),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF7FAFA),
        surfaceContainer = Color(0xFFEFF4F4),
        surfaceContainerHigh = Color(0xFFE9EFF0),
        surfaceContainerHighest = Color(0xFFE3EAEB),
        outline = Color(0xFF6F8386),
        outlineVariant = Color(0xFFC3D1D3),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
    )
}

/** [color] with its hue replaced by [hue] (when given) and its saturation scaled; lightness stays. */
internal fun retint(
    color: Color,
    hue: Float?,
    saturationScale: Float,
): Color {
    if (hue == null && saturationScale == 1f) return color
    val max = maxOf(color.red, color.green, color.blue)
    val min = minOf(color.red, color.green, color.blue)
    val lightness = (max + min) / 2f
    val delta = max - min
    val saturation = if (delta == 0f) 0f else delta / (1f - kotlin.math.abs(2f * lightness - 1f))
    val ownHue =
        when {
            delta == 0f -> 0f
            max == color.red -> HUE_SEXTANT * (((color.green - color.blue) / delta).mod(6f))
            max == color.green -> HUE_SEXTANT * ((color.blue - color.red) / delta + 2f)
            else -> HUE_SEXTANT * ((color.red - color.green) / delta + 4f)
        }
    return Color.hsl(
        (hue ?: ownHue).mod(FULL_CIRCLE),
        (saturation * saturationScale).coerceIn(0f, 1f),
        lightness.coerceIn(0f, 1f),
        color.alpha,
    )
}

@Composable
fun DualSubTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences =
        remember(context) {
            context.getSharedPreferences("dual_sub_preferences", 0)
        }
    var accentKey by remember {
        mutableStateOf(
            storedAppThemeAccentKey(
                preferences.getString(APP_THEME_ACCENT_PREFERENCE, DEFAULT_APP_THEME_ACCENT_KEY),
            ),
        )
    }
    var themeKey by remember {
        mutableStateOf(storedAppColorTheme(preferences.getString(APP_COLOR_THEME_PREFERENCE, null)).key)
    }

    DisposableEffect(preferences) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
                when (key) {
                    APP_THEME_ACCENT_PREFERENCE ->
                        accentKey = storedAppThemeAccentKey(sharedPreferences.getString(key, DEFAULT_APP_THEME_ACCENT_KEY))
                    APP_COLOR_THEME_PREFERENCE -> themeKey = storedAppColorTheme(sharedPreferences.getString(key, null)).key
                    // "Reset all settings" clears every key at once.
                    null -> {
                        accentKey = storedAppThemeAccentKey(sharedPreferences.getString(APP_THEME_ACCENT_PREFERENCE, null))
                        themeKey = storedAppColorTheme(sharedPreferences.getString(APP_COLOR_THEME_PREFERENCE, null)).key
                    }
                }
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val colors = remember(accentKey, themeKey) { dualSubColorScheme(accentKey, themeKey) }
    val light = storedAppColorTheme(themeKey).isLight
    val view = LocalView.current
    if (!view.isInEditMode) {
        // Only when the theme changes: this content recomposes on every playback tick, and each
        // system-bar change is a call into the system.
        LaunchedEffect(light, view) {
            context.findActivity()?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    if (isAppearanceLightStatusBars != light) isAppearanceLightStatusBars = light
                    if (isAppearanceLightNavigationBars != light) isAppearanceLightNavigationBars = light
                }
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = contentDirectionTypography, content = content)
}

/**
 * Material's type scale with every paragraph laid out in the direction of its own first letter
 * (issue #152): saved Persian or Arabic meanings and chat answers keep their punctuation and numbers
 * in place, while the app's own text stays left to right.
 */
private val contentDirectionTypography: Typography =
    Typography().run {
        Typography(
            displayLarge = displayLarge.inLineDirection(),
            displayMedium = displayMedium.inLineDirection(),
            displaySmall = displaySmall.inLineDirection(),
            headlineLarge = headlineLarge.inLineDirection(),
            headlineMedium = headlineMedium.inLineDirection(),
            headlineSmall = headlineSmall.inLineDirection(),
            titleLarge = titleLarge.inLineDirection(),
            titleMedium = titleMedium.inLineDirection(),
            titleSmall = titleSmall.inLineDirection(),
            bodyLarge = bodyLarge.inLineDirection(),
            bodyMedium = bodyMedium.inLineDirection(),
            bodySmall = bodySmall.inLineDirection(),
            labelLarge = labelLarge.inLineDirection(),
            labelMedium = labelMedium.inLineDirection(),
            labelSmall = labelSmall.inLineDirection(),
        )
    }
