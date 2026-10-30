package com.kienhoang.dualsubreplay.ui

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.RemoteSwitches

// Settings → Ads & sponsors (owner request 2026-10-25): both switches are on by default.

internal const val SKIP_ADS_PREFERENCE = "skip_ads"
internal const val SKIP_SPONSORS_PREFERENCE = "skip_sponsors"

/** Always skip sponsors (owner request 2026-10-25, off by default): a seek into a sponsor part is skipped too. */
internal const val ALWAYS_SKIP_SPONSORS_PREFERENCE = "always_skip_sponsors"

/** Both switches, reset by Reset all settings. */
internal val AD_SKIP_SETTING_KEYS = listOf(SKIP_ADS_PREFERENCE, SKIP_SPONSORS_PREFERENCE, ALWAYS_SKIP_SPONSORS_PREFERENCE)

internal data class AdSkipSettings(
    val skipAds: Boolean = true,
    val skipSponsors: Boolean = true,
    val alwaysSkipSponsors: Boolean = false,
)

internal fun readAdSkipSettings(preferences: SharedPreferences): AdSkipSettings =
    AdSkipSettings(
        skipAds = preferences.getBoolean(SKIP_ADS_PREFERENCE, true),
        skipSponsors = preferences.getBoolean(SKIP_SPONSORS_PREFERENCE, true),
        alwaysSkipSponsors = preferences.getBoolean(ALWAYS_SKIP_SPONSORS_PREFERENCE, false),
    )

/** What the page does: the user's switches, unless a remote switch turned skipping off for now. */
internal fun AdSkipSettings.withRemote(switches: RemoteSwitches): AdSkipSettings =
    copy(skipAds = skipAds && !switches.skipAdsOff, skipSponsors = skipSponsors && !switches.skipSponsorsOff)

/** The Ads and sponsors settings, following every change (from the settings page or the assistant). */
@Composable
internal fun rememberAdSkipSettings(): State<AdSkipSettings> {
    val context = LocalContext.current
    val preferences = remember(context) { studyPreferences(context) }
    val settings = remember(preferences) { mutableStateOf(readAdSkipSettings(preferences)) }
    DisposableEffect(preferences) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key in AD_SKIP_SETTING_KEYS) settings.value = readAdSkipSettings(preferences)
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        settings.value = readAdSkipSettings(preferences)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return settings
}

/** More settings → Ads and sponsors. */
@Composable
internal fun AdSkipSettingsSection() {
    val context = LocalContext.current
    val preferences = remember(context) { studyPreferences(context) }
    val settings by rememberAdSkipSettings()
    SettingsSwitchRow(
        title = stringResource(R.string.ads_skip_ads_title),
        description = stringResource(R.string.ads_skip_ads_description),
        checked = settings.skipAds,
        onCheckedChange = { preferences.edit().putBoolean(SKIP_ADS_PREFERENCE, it).apply() },
        testTag = "skip_ads_switch",
    )
    SettingsSwitchRow(
        title = stringResource(R.string.ads_skip_sponsors_title),
        description = stringResource(R.string.ads_skip_sponsors_description),
        checked = settings.skipSponsors,
        onCheckedChange = { preferences.edit().putBoolean(SKIP_SPONSORS_PREFERENCE, it).apply() },
        testTag = "skip_sponsors_switch",
    )
    if (settings.skipSponsors) {
        SettingsSwitchRow(
            title = stringResource(R.string.ads_always_skip_sponsors_title),
            description = stringResource(R.string.ads_always_skip_sponsors_description),
            checked = settings.alwaysSkipSponsors,
            onCheckedChange = { preferences.edit().putBoolean(ALWAYS_SKIP_SPONSORS_PREFERENCE, it).apply() },
            testTag = "always_skip_sponsors_switch",
        )
    }
    SettingsHint(stringResource(R.string.ads_note))
    val lastAd by AdSkipReport.lastAd.collectAsState()
    val sponsorParts by AdSkipReport.sponsorParts.collectAsState()
    lastAd?.let { SettingsHint(stringResource(R.string.ads_last_ad_check, it)) }
    sponsorParts?.let { SettingsHint(stringResource(R.string.ads_sponsor_parts_check, it)) }
}
