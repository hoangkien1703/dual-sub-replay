package com.kienhoang.dualsubreplay.ui

import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.kienhoang.dualsubreplay.R

// Settings → YouTube tabs → Use different Google accounts (owner decision 2026-10-27, off by default).
// Each account is an Android WebView profile with its own cookies and site data, so it keeps its own
// Google sign-in. Account 1 is WebView's default profile: the sign-in the app always had.

internal const val DIFFERENT_TAB_ACCOUNTS_PREFERENCE = "different_tab_accounts"

/** Whether the menu's Tabs list is unfolded; the list's ▾/▸ choice is kept. */
internal const val DRAWER_TABS_OPEN_PREFERENCE = "drawer_tabs_open"

/** Reset by Reset all settings. */
internal val YOUTUBE_TABS_SETTING_KEYS = listOf(DIFFERENT_TAB_ACCOUNTS_PREFERENCE, DRAWER_TABS_OPEN_PREFERENCE)

/** WebView profiles need a recent Android System WebView. */
internal fun tabAccountsSupported(): Boolean =
    runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    }.getOrDefault(false)

internal fun tabProfileName(account: Int): String =
    if (normalizeTabAccount(account) ==
        MAIN_TAB_ACCOUNT
    ) {
        Profile.DEFAULT_PROFILE_NAME
    } else {
        "youtube-account-${normalizeTabAccount(account)}"
    }

/** Must run right after the WebView is made, before it loads anything. */
internal fun WebView.useTabAccount(account: Int) {
    if (normalizeTabAccount(account) == MAIN_TAB_ACCOUNT) return
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
    ProfileStore.getInstance().getOrCreateProfile(tabProfileName(account))
    WebViewCompat.setProfile(this, tabProfileName(account))
}

/** The cookies (and so the Google sign-in) of [account]. */
internal fun tabCookieManager(account: Int): CookieManager {
    if (normalizeTabAccount(account) == MAIN_TAB_ACCOUNT) return CookieManager.getInstance()
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return CookieManager.getInstance()
    return ProfileStore.getInstance().getOrCreateProfile(tabProfileName(account)).cookieManager
}

/** True while tabs may use accounts 2 and 3: the setting is on and this phone's WebView can do it. */
@Composable
internal fun rememberDifferentTabAccounts(): State<Boolean> {
    val context = LocalContext.current
    val preferences = remember(context) { studyPreferences(context) }
    val supported = remember { tabAccountsSupported() }
    val on = remember(preferences) { mutableStateOf(supported && preferences.getBoolean(DIFFERENT_TAB_ACCOUNTS_PREFERENCE, false)) }
    DisposableEffect(preferences) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key == DIFFERENT_TAB_ACCOUNTS_PREFERENCE) {
                    on.value = supported && preferences.getBoolean(DIFFERENT_TAB_ACCOUNTS_PREFERENCE, false)
                }
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return on
}

/** More settings → YouTube tabs. */
@Composable
internal fun YouTubeTabsSettingsSection() {
    val context = LocalContext.current
    val preferences = remember(context) { studyPreferences(context) }
    val supported = remember { tabAccountsSupported() }
    val on by rememberDifferentTabAccounts()
    SettingsHint(stringResource(R.string.tabs_settings_note))
    if (supported) {
        SettingsSwitchRow(
            title = stringResource(R.string.tabs_different_accounts_title),
            description = stringResource(R.string.tabs_different_accounts_description),
            checked = on,
            onCheckedChange = { preferences.edit().putBoolean(DIFFERENT_TAB_ACCOUNTS_PREFERENCE, it).apply() },
            testTag = "different_tab_accounts_switch",
        )
    } else {
        SettingsHint(stringResource(R.string.tabs_different_accounts_unsupported))
    }
}
