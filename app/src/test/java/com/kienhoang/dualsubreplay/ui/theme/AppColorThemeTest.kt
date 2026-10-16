package com.kienhoang.dualsubreplay.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.kienhoang.dualsubreplay.ui.APP_COLOR_THEME_PREFERENCE
import com.kienhoang.dualsubreplay.ui.AppColorThemeOption
import com.kienhoang.dualsubreplay.ui.DEFAULT_APP_THEME_ACCENT_KEY
import com.kienhoang.dualsubreplay.ui.RESETTABLE_SETTING_KEYS
import com.kienhoang.dualsubreplay.ui.storedAppColorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AppColorThemeTest {
    private fun scheme(theme: AppColorThemeOption) = dualSubColorScheme(DEFAULT_APP_THEME_ACCENT_KEY, theme.key)

    @Test
    fun theDefaultThemeKeepsTodaysTeal() {
        val teal = scheme(AppColorThemeOption.DEEP_TEAL)
        assertEquals(Color(0xFF061416).toArgb(), teal.background.toArgb())
        assertEquals(Color(0xFF0E2427).toArgb(), teal.surfaceContainer.toArgb())
        assertEquals(dualSubColorScheme(DEFAULT_APP_THEME_ACCENT_KEY).surface, teal.surface)
        assertEquals(AppColorThemeOption.DEEP_TEAL, storedAppColorTheme(null))
        assertEquals(AppColorThemeOption.DEEP_TEAL, storedAppColorTheme("rainbow"))
        assertTrue(APP_COLOR_THEME_PREFERENCE in RESETTABLE_SETTING_KEYS)
    }

    @Test
    fun darkThemesStayDarkWithReadableText() {
        for (theme in AppColorThemeOption.entries.filter { !it.isLight }) {
            val colors = scheme(theme)
            assertTrue(theme.key, colors.background.luminance() < 0.05f)
            assertTrue(theme.key, contrast(colors.onSurface, colors.surface) >= 7f)
            assertTrue(theme.key, contrast(colors.onSurfaceVariant, colors.surfaceContainer) >= 4.5f)
        }
        assertEquals(Color.Black, scheme(AppColorThemeOption.BLACK).background)
    }

    @Test
    fun theLightThemeHasDarkTextAndADarkenedAccent() {
        val light = scheme(AppColorThemeOption.LIGHT)
        assertTrue(light.background.luminance() > 0.8f)
        assertTrue(contrast(light.onSurface, light.surface) >= 7f)
        assertTrue(contrast(light.onSurfaceVariant, light.surfaceContainer) >= 4.5f)
        assertTrue(contrast(light.primary, light.surface) >= 3f)
    }

    @Test
    fun retintKeepsLightnessAndTakesTheNewHue() {
        val teal = Color(0xFF173438)
        assertEquals(teal, retint(teal, null, 1f))
        val gray = retint(teal, null, 0f)
        assertEquals(gray.red, gray.green, 0.01f)
        assertEquals(gray.green, gray.blue, 0.01f)
        val navy = retint(teal, 220f, 1f)
        assertTrue(navy.blue > navy.green && navy.green > navy.red)
        assertEquals(lightness(teal), lightness(navy), 0.01f)
    }

    private fun lightness(color: Color) = (maxOf(color.red, color.green, color.blue) + minOf(color.red, color.green, color.blue)) / 2f

    private fun contrast(
        a: Color,
        b: Color,
    ): Float {
        val (high, low) = if (a.luminance() > b.luminance()) a.luminance() to b.luminance() else b.luminance() to a.luminance()
        return abs((high + 0.05f) / (low + 0.05f))
    }
}
