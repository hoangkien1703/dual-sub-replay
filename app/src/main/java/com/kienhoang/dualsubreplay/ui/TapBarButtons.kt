package com.kienhoang.dualsubreplay.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R

/**
 * Settings → Word learning → Tap bar buttons (lion737, Discussion #81, 2026-11-19; owner pick
 * 2026-11-20: switches in Settings, not a drag-and-drop editor). Each of the bar's actions shows as
 * an icon in the bar while its chip is on; every action stays in the bar's ▾ list either way. The
 * order is fixed, with Add to Anki next to Pronounce as lion737 asked. Add to Anki shows only while
 * Send to can be used, as on the word card.
 */
internal enum class TapBarButton(
    val preference: String,
    @StringRes val label: Int,
    val tag: String,
    val inBarByDefault: Boolean,
) {
    COPY("tap_bar_copy", R.string.practice_copy, "copy", true),
    TRANSLATE("tap_bar_translate", R.string.practice_translate, "translate", true),
    PRONOUNCE("tap_bar_pronounce", R.string.practice_pronounce, "pronounce", true),
    ANKI("tap_bar_anki", R.string.phrase_add_to_anki, "anki", true),
    GOOGLE_TRANSLATE("tap_bar_google_translate", R.string.practice_open_google_translate, "google_translate", false),
}

/** Every Tap bar buttons preference key, for backups, search and Reset all settings. */
internal val TAP_BAR_BUTTON_PREFERENCES: List<String> = TapBarButton.entries.map { it.preference }

/** The buttons the bar shows as icons: the defaults until the learner changes a chip. */
internal fun readTapBarButtons(preferences: SharedPreferences): Set<TapBarButton> =
    TapBarButton.entries.filterTo(LinkedHashSet()) { preferences.getBoolean(it.preference, it.inBarByDefault) }

internal fun readTapBarButtons(context: Context): Set<TapBarButton> =
    readTapBarButtons(context.getSharedPreferences("dual_sub_preferences", 0))

/** Settings → Word learning → Tap bar buttons: one chip per action, on while it shows in the bar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TapBarButtonsSetting() {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("dual_sub_preferences", 0) }
    var shown by remember { mutableStateOf(readTapBarButtons(preferences)) }
    DisposableEffect(preferences) {
        // "Reset all settings", a restored backup and the AI assistant change the keys while this section is open.
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key in TAP_BAR_BUTTON_PREFERENCES) shown = readTapBarButtons(preferences)
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .settingAnchor(TAP_BAR_BUTTONS_ANCHOR)
            .padding(vertical = 6.dp),
    ) {
        Text(stringResource(R.string.settings_tap_bar_buttons_title), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TapBarButton.entries.forEach { button ->
                val on = button in shown
                FilterChip(
                    selected = on,
                    onClick = {
                        preferences.edit().putBoolean(button.preference, !on).apply()
                        shown = readTapBarButtons(preferences)
                    },
                    label = { Text(stringResource(button.label)) },
                    leadingIcon =
                        if (on) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        } else {
                            null
                        },
                    modifier = Modifier.testTag(button.preference),
                )
            }
        }
        Text(
            stringResource(R.string.settings_tap_bar_buttons_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The settings page anchor (and search target) of Tap bar buttons. */
internal const val TAP_BAR_BUTTONS_ANCHOR = "tap_bar_buttons"
