package com.kienhoang.dualsubreplay.ui.theme

import android.content.SharedPreferences
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.kienhoang.dualsubreplay.ui.APP_THEME_ACCENT_PREFERENCE
import com.kienhoang.dualsubreplay.ui.DEFAULT_APP_THEME_ACCENT_KEY
import com.kienhoang.dualsubreplay.ui.appThemeAccentColor
import com.kienhoang.dualsubreplay.ui.storedAppThemeAccentKey

internal fun dualSubColorScheme(accentKey: String) = darkColorScheme(
    primary = appThemeAccentColor(accentKey),
    onPrimary = Color(0xFF001F23),
    secondary = appThemeAccentColor(accentKey),
    background = Color(0xFF061416),
    onBackground = Color(0xFFE4F5F6),
    surface = Color(0xFF0C2023),
    onSurface = Color(0xFFE4F5F6),
    surfaceVariant = Color(0xFF173438),
    onSurfaceVariant = Color(0xFFB8CDD0),
    error = Color(0xFFFFB4AB),
)

@Composable
fun DualSubTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("dual_sub_preferences", 0)
    }
    var accentKey by remember {
        mutableStateOf(
            storedAppThemeAccentKey(
                preferences.getString(APP_THEME_ACCENT_PREFERENCE, DEFAULT_APP_THEME_ACCENT_KEY),
            ),
        )
    }

    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == APP_THEME_ACCENT_PREFERENCE) {
                accentKey = storedAppThemeAccentKey(
                    sharedPreferences.getString(key, DEFAULT_APP_THEME_ACCENT_KEY),
                )
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val colors = remember(accentKey) { dualSubColorScheme(accentKey) }
    MaterialTheme(colorScheme = colors, content = content)
}
