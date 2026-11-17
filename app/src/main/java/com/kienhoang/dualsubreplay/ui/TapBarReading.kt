package com.kienhoang.dualsubreplay.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.AnalyzedToken
import com.kienhoang.dualsubreplay.data.EnglishIpa
import com.kienhoang.dualsubreplay.data.isEnglishLanguage

/**
 * Settings → Word learning → Pronunciation in the tap bar (25kiuo, Discussion #81, 2026-11-17; owner
 * pick: off by default): a single tapped word's reading shows above its meaning in the bar.
 */
internal const val TAP_BAR_READING_PREFERENCE = "tap_bar_reading"

internal fun readTapBarReading(context: Context): Boolean =
    context.getSharedPreferences("dual_sub_preferences", 0).getBoolean(TAP_BAR_READING_PREFERENCE, false)

/** The reading the bar shows for [token], as on the word card: kana or pinyin from the analyzer, else English IPA. */
internal fun tapBarReading(
    token: AnalyzedToken,
    language: String?,
): String? =
    token.reading?.takeIf { it.isNotBlank() && it != token.text }
        ?: token.text.takeIf { isEnglishLanguage(language) }?.let(EnglishIpa::of)

/** The reading the tap bar shows for the selected [words] of [tokens]: only one word, and only while [enabled]. */
@Composable
internal fun rememberBarReading(
    tokens: List<AnalyzedToken>,
    words: IntRange,
    language: String?,
    enabled: Boolean,
): String? {
    // English IPA loads on first use; the bar looks again once it has.
    val ipaLoaded by EnglishIpa.revision.collectAsState()
    return remember(tokens, words, language, ipaLoaded, enabled) {
        tokens.getOrNull(words.first)?.takeIf { enabled && words.first == words.last }?.let { tapBarReading(it, language) }
    }
}

/** Settings → Word learning → Pronunciation in the tap bar. */
@Composable
internal fun TapBarReadingSetting() {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("dual_sub_preferences", 0) }
    var enabled by remember { mutableStateOf(readTapBarReading(context)) }
    DisposableEffect(preferences) {
        // "Reset all settings" and the AI assistant change the key while this section is open.
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == TAP_BAR_READING_PREFERENCE || key == null) enabled = readTapBarReading(context)
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SettingsSwitchRow(
        title = stringResource(R.string.settings_tap_bar_reading_title),
        description = stringResource(R.string.settings_tap_bar_reading_description),
        checked = enabled,
        onCheckedChange = {
            enabled = it
            preferences.edit().putBoolean(TAP_BAR_READING_PREFERENCE, it).apply()
        },
        testTag = "tap_bar_reading_switch",
    )
}
