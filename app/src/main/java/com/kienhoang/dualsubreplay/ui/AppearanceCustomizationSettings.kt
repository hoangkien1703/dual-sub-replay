package com.kienhoang.dualsubreplay.ui

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R

/** Overlay box, app and accent colors, shown in the "Colors & theme" settings section. */
@Composable
internal fun AdvancedAppearanceSettings() {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("dual_sub_preferences", 0)
    }
    var backgroundKey by remember {
        mutableStateOf(
            storedSubtitleBoxBackgroundKey(
                preferences.getString(
                    SUBTITLE_BOX_BACKGROUND_PREFERENCE,
                    DEFAULT_SUBTITLE_BOX_BACKGROUND_KEY,
                ),
            ),
        )
    }
    var themeKey by remember {
        mutableStateOf(storedAppColorTheme(preferences.getString(APP_COLOR_THEME_PREFERENCE, null)).key)
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
            when (key) {
                SUBTITLE_BOX_BACKGROUND_PREFERENCE -> {
                    backgroundKey = storedSubtitleBoxBackgroundKey(
                        sharedPreferences.getString(key, DEFAULT_SUBTITLE_BOX_BACKGROUND_KEY),
                    )
                }
                APP_COLOR_THEME_PREFERENCE -> themeKey = storedAppColorTheme(sharedPreferences.getString(key, null)).key
                APP_THEME_ACCENT_PREFERENCE -> {
                    accentKey = storedAppThemeAccentKey(
                        sharedPreferences.getString(key, DEFAULT_APP_THEME_ACCENT_KEY),
                    )
                }
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun save(
        key: String,
        selected: String,
    ) = preferences.edit().putString(key, selected).apply()

    ColorChoiceSetting(
        title = stringResource(R.string.settings_box_background_title),
        hint = stringResource(R.string.settings_box_background_hint),
        selectedKey = backgroundKey,
        options = SubtitleBoxBackgroundOption.entries,
        testTagPrefix = "box_background",
    ) {
        backgroundKey = it
        save(SUBTITLE_BOX_BACKGROUND_PREFERENCE, it)
    }

    Spacer(Modifier.height(14.dp))
    HorizontalDivider()
    Spacer(Modifier.height(14.dp))
    Text(stringResource(R.string.settings_app_theme_title), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    ColorChoiceSetting(
        title = stringResource(R.string.settings_app_colors_title),
        hint = stringResource(R.string.settings_app_colors_hint),
        selectedKey = themeKey,
        options = AppColorThemeOption.entries,
        testTagPrefix = "app_colors",
    ) {
        themeKey = it
        save(APP_COLOR_THEME_PREFERENCE, it)
    }
    Spacer(Modifier.height(10.dp))
    ColorChoiceSetting(
        title = stringResource(R.string.settings_accent_color_title),
        hint = stringResource(R.string.settings_accent_color_hint),
        selectedKey = accentKey,
        options = AppThemeAccentOption.entries,
        testTagPrefix = "theme_accent",
    ) {
        accentKey = it
        save(APP_THEME_ACCENT_PREFERENCE, it)
    }
}

/** A titled row of color swatches with a short hint. */
@Composable
private fun ColorChoiceSetting(
    title: String,
    hint: String,
    selectedKey: String,
    options: List<ColorSettingOption>,
    testTagPrefix: String,
    onColorChange: (String) -> Unit,
) {
    Text(title)
    Text(
        hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SettingsColorSwatchRow(selectedKey, options, onColorChange, testTagPrefix)
}

@Composable
private fun SettingsColorSwatchRow(
    selectedKey: String,
    options: List<ColorSettingOption>,
    onColorChange: (String) -> Unit,
    testTagPrefix: String,
) {
    val selectedText = stringResource(R.string.settings_color_selected)
    val notSelectedText = stringResource(R.string.settings_color_not_selected)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        options.forEach { option ->
            val selected = option.key == selectedKey
            val label = stringResource(option.labelRes)
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(option.argb))
                    .then(
                        if (selected) {
                            Modifier.border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                            )
                        } else {
                            Modifier.border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = CircleShape,
                            )
                        },
                    )
                    .clip(CircleShape)
                    .clickable { onColorChange(option.key) }
                    .semantics {
                        contentDescription = label
                        stateDescription = if (selected) selectedText else notSelectedText
                    }
                    .testTag("color_option_${testTagPrefix}_${option.key}"),
            )
        }
    }
}
