package com.kienhoang.dualsubreplay.ui

import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.BILIBILI_HOME_URL
import com.kienhoang.dualsubreplay.data.BILIBILI_TV_HOME_URL
import com.kienhoang.dualsubreplay.data.IS_PLAY_BUILD
import com.kienhoang.dualsubreplay.data.VideoKeys
import com.kienhoang.dualsubreplay.data.VideoSite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

/** Settings → Beta → Bilibili (bilibili.com). Off by default (owner pick 2026-11-17; spec docs/specs/2026-11-17-bilibili-beta.md). */
internal const val BETA_BILIBILI_PREFERENCE = "beta_bilibili"

/** Settings → Beta → Bilibili International (bilibili.tv). Off by default. */
internal const val BETA_BILIBILI_TV_PREFERENCE = "beta_bilibili_tv"

/** Set once a site was opened after its switch first went on, so it opens by itself only that once. */
private const val BETA_BILIBILI_OPENED_PREFERENCE = "beta_bilibili_opened"
private const val BETA_BILIBILI_TV_OPENED_PREFERENCE = "beta_bilibili_tv_opened"

/** Reset by Reset all settings. */
internal val BETA_SETTING_KEYS = listOf(BETA_BILIBILI_PREFERENCE, BETA_BILIBILI_TV_PREFERENCE)

/** A Beta video site: its switch, its home page, and the name the New tab menu shows. */
internal enum class BetaSite(
    val site: VideoSite,
    val preference: String,
    val openedPreference: String,
    val homeUrl: String,
    @StringRes val title: Int,
) {
    BILIBILI(
        VideoSite.BILIBILI,
        BETA_BILIBILI_PREFERENCE,
        BETA_BILIBILI_OPENED_PREFERENCE,
        BILIBILI_HOME_URL,
        R.string.beta_bilibili_title,
    ),
    BILIBILI_TV(
        VideoSite.BILIBILI_TV,
        BETA_BILIBILI_TV_PREFERENCE,
        BETA_BILIBILI_TV_OPENED_PREFERENCE,
        BILIBILI_TV_HOME_URL,
        R.string.beta_bilibili_tv_title,
    ),
}

/**
 * The Beta sites this build offers: none in the Google Play build ([IS_PLAY_BUILD]), which keeps to
 * YouTube; there the TV layout is the only Beta setting.
 */
internal fun betaSitesInBuild(playStore: Boolean = IS_PLAY_BUILD): List<BetaSite> = if (playStore) emptyList() else BetaSite.entries

/**
 * The Beta sites that are on, for code without a composition (the web view's navigation rules).
 * [AppViewModel] keeps it in step with the stored switches.
 */
internal object BetaSites {
    private val on = MutableStateFlow<Set<VideoSite>>(emptySet())
    val enabled: StateFlow<Set<VideoSite>> = on.asStateFlow()

    fun read(preferences: SharedPreferences) {
        on.value =
            betaSitesInBuild()
                .filter { preferences.getBoolean(it.preference, false) }
                .map { it.site }
                .toSet()
    }

    /** For tests. */
    fun set(sites: Set<VideoSite>) {
        on.value = sites
    }

    fun isOn(site: VideoSite): Boolean = site in on.value
}

/** A shared link to a Beta site that is off in Settings (25kiuo, Discussion #81, 2026-11-21): the app asks to turn it on. */
internal data class BetaSiteOffer(
    val site: BetaSite,
    val url: String,
)

/**
 * The first link in shared [text] (Bilibili's share text puts a title before it) to a Beta site of
 * this build that is off, or null. Before, such a link was dropped without a word.
 */
internal fun offBetaSiteLink(
    text: String,
    isOn: (VideoSite) -> Boolean = BetaSites::isOn,
    sites: List<BetaSite> = betaSitesInBuild(),
): BetaSiteOffer? =
    Regex("https://[^\\s]+").findAll(text).map { it.value.trimEnd('.', ',', ')', '】') }.firstNotNullOfOrNull { url ->
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return@firstNotNullOfOrNull null
        val site = VideoKeys.bilibiliSiteOfHost(host) ?: return@firstNotNullOfOrNull null
        sites.firstOrNull { it.site == site && !isOn(site) }?.let { BetaSiteOffer(it, url) }
    }

/**
 * Turns [site] on as its Settings switch does, marked as opened so its home page does not open by
 * itself (see [OpenBetaSiteWhenFirstOn]): the shared link opens instead.
 */
internal fun turnOnBetaSite(
    preferences: SharedPreferences,
    site: BetaSite,
) {
    preferences
        .edit()
        .putBoolean(site.openedPreference, true)
        .putBoolean(site.preference, true)
        .apply()
    BetaSites.read(preferences)
}

@Composable
internal fun rememberBetaSitesOn(): State<List<BetaSite>> =
    rememberStoredSetting(*BETA_SETTING_KEYS.toTypedArray()) { preferences ->
        betaSitesInBuild().filter { preferences.getBoolean(it.preference, false) }
    }

/** Settings → Beta (owner pick 2026-11-17): Bilibili, Bilibili International, then the TV layout. */
@Composable
internal fun BetaSettingsSection() {
    val context = LocalContext.current
    val preferences = remember(context) { studyPreferences(context) }
    val on by rememberBetaSitesOn()
    // The Play build offers no Beta sites: only the TV layout is left.
    if (betaSitesInBuild().isNotEmpty()) {
        SettingsHint(stringResource(R.string.beta_section_hint))
        SettingsSwitchRow(
            title = stringResource(R.string.beta_bilibili_title),
            description = stringResource(R.string.beta_bilibili_description),
            checked = BetaSite.BILIBILI in on,
            onCheckedChange = { preferences.edit().putBoolean(BETA_BILIBILI_PREFERENCE, it).apply() },
            testTag = "beta_bilibili_switch",
        )
        SettingsSwitchRow(
            title = stringResource(R.string.beta_bilibili_tv_title),
            description = stringResource(R.string.beta_bilibili_tv_description),
            checked = BetaSite.BILIBILI_TV in on,
            onCheckedChange = { preferences.edit().putBoolean(BETA_BILIBILI_TV_PREFERENCE, it).apply() },
            testTag = "beta_bilibili_tv_switch",
        )
    }
    TvLayoutSettingsSection()
}

/**
 * Opens a Beta site in a new tab the first time its switch goes on, from Settings or the assistant
 * (owner, 2026-11-17: "it will open bilibili web immediately for the first time").
 */
@Composable
internal fun OpenBetaSiteWhenFirstOn(
    onOpen: (String) -> Boolean,
    onOpened: () -> Unit = {},
) {
    val context = LocalContext.current
    val preferences = remember(context) { studyPreferences(context) }
    val open by rememberUpdatedState(onOpen)
    val opened by rememberUpdatedState(onOpened)
    DisposableEffect(preferences) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { stored, key ->
                val site = BetaSite.entries.firstOrNull { it.preference == key } ?: return@OnSharedPreferenceChangeListener
                if (stored.getBoolean(site.preference, false) && !stored.getBoolean(site.openedPreference, false)) {
                    // Listeners run in no set order: the web view's rules must know the site is on first.
                    BetaSites.read(stored)
                    stored.edit().putBoolean(site.openedPreference, true).apply()
                    if (open(site.homeUrl)) opened()
                }
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

/**
 * Asks to turn on the Beta site of a link shared to the app while its switch is off (25kiuo,
 * Discussion #81, 2026-11-21). "Turn on" opens the link; the X leaves the switch off.
 */
@Composable
internal fun BetaSiteOfferDialog(
    offer: BetaSiteOffer,
    onTurnOn: () -> Unit,
    onDismiss: () -> Unit,
) {
    val site = stringResource(offer.site.title)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(stringResource(R.string.beta_offer_title, site), onClose = onDismiss) },
        text = { Text(stringResource(R.string.beta_offer_message, site)) },
        confirmButton = {
            TextButton(onClick = onTurnOn, modifier = Modifier.testTag("beta_offer_turn_on")) {
                Text(stringResource(R.string.beta_offer_turn_on))
            }
        },
        modifier = Modifier.testTag("beta_offer_dialog"),
    )
}
