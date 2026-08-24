package com.kienhoang.dualsubreplay.ui

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.HorizontalDivider
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Advanced visual customization kept behind the More settings disclosure. */
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

    Text("Dual-sub box background")
    Text(
        "Choose the background color of the compact dual-subtitle box shown over the video.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SettingsColorSwatchRow(
        selectedKey = backgroundKey,
        options = SubtitleBoxBackgroundOption.entries,
        onColorChange = { selected ->
            backgroundKey = selected
            preferences.edit()
                .putString(SUBTITLE_BOX_BACKGROUND_PREFERENCE, selected)
                .apply()
        },
        testTagPrefix = "box_background",
    )

    Spacer(Modifier.height(14.dp))
    HorizontalDivider()
    Spacer(Modifier.height(14.dp))
    Text("App theme", style = MaterialTheme.typography.titleSmall)
    Text(
        "Accent color",
        modifier = Modifier.padding(top = 6.dp),
    )
    Text(
        "Changes switches, sliders, buttons, active borders, and other app accents.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SettingsColorSwatchRow(
        selectedKey = accentKey,
        options = AppThemeAccentOption.entries,
        onColorChange = { selected ->
            accentKey = selected
            preferences.edit()
                .putString(APP_THEME_ACCENT_PREFERENCE, selected)
                .apply()
        },
        testTagPrefix = "theme_accent",
    )
}

@Composable
private fun SettingsColorSwatchRow(
    selectedKey: String,
    options: List<ColorSettingOption>,
    onColorChange: (String) -> Unit,
    testTagPrefix: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        options.forEach { option ->
            val selected = option.key == selectedKey
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
                        contentDescription = option.label
                        stateDescription = if (selected) "Selected" else "Not selected"
                    }
                    .testTag("color_option_${testTagPrefix}_${option.key}"),
            )
        }
    }
}
