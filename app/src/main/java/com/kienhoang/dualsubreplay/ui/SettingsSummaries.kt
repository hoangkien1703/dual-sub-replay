package com.kienhoang.dualsubreplay.ui

import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.translation.SubtitleTranslator
import com.kienhoang.dualsubreplay.update.APP_AUTO_UPDATE_PREFERENCE

/** Joins the pieces of a section's line: "Skip ads: On · Skip sponsors: On". */
internal const val SETTINGS_SUMMARY_SEPARATOR = " · "

/** One piece of the line under a closed section's name. */
internal sealed interface SummaryPart {
    /** Text already in the app's language, such as the names of the two languages. */
    data class Plain(
        val text: String,
    ) : SummaryPart

    /** A text of its own, such as the name of the chosen translator; [number] fills its `%1$d`. */
    data class Label(
        @StringRes val res: Int,
        val number: Int? = null,
    ) : SummaryPart

    /** A setting and its value, shown as "Skip ads: On". */
    data class Value(
        @StringRes val nameRes: Int,
        @StringRes val valueRes: Int,
    ) : SummaryPart
}

/** What the assistant's section says: off, still without a key, or the service it uses. */
internal data class AiSummaryState(
    val enabled: Boolean,
    val hasKey: Boolean,
    @StringRes val serviceRes: Int,
)

/** The values the settings page's section lines show (owner pick 2026-11-11). */
internal data class SettingsSummaryState(
    val languages: String,
    val translator: SubtitleTranslator = SubtitleTranslator.GOOGLE,
    val fontScale: Float = 1f,
    val translationPosition: TranslationPosition = TranslationPosition.BELOW,
    val playerMode: PlayerExperienceMode = PlayerExperienceMode.TRANSCRIPT_PANEL,
    val wordColors: Boolean = true,
    val study: StudySettings = StudySettings(),
    val overlayInFullscreen: Boolean = true,
    val ads: AdSkipSettings = AdSkipSettings(),
    val differentTabAccounts: Boolean = false,
    val appColors: AppColorThemeOption = AppColorThemeOption.DEEP_TEAL,
    /** Null in builds without the assistant, which have no AI section. */
    val ai: AiSummaryState? = null,
    val autoUpdate: Boolean = false,
    val tvLayout: Boolean = false,
)

@StringRes
private fun onOff(on: Boolean): Int = if (on) R.string.ai_action_on else R.string.ai_action_off

/**
 * The line under a closed section's name: the current value of the setting people open it for,
 * so most answers show without opening anything. Before 2026-11-11 the line listed what was
 * inside ("Caption flow and model preloading"), and only Languages showed its value.
 */
internal fun settingsSectionSummary(
    section: MoreSettingsSection,
    state: SettingsSummaryState,
): List<SummaryPart> =
    when (section) {
        MoreSettingsSection.LANGUAGES -> listOf(SummaryPart.Plain(state.languages))
        MoreSettingsSection.TRANSLATION -> listOf(SummaryPart.Label(state.translator.titleRes))
        MoreSettingsSection.READING ->
            listOf(
                SummaryPart.Label(R.string.settings_text_size, (state.fontScale * 100).toInt()),
                SummaryPart.Label(
                    when (state.translationPosition) {
                        TranslationPosition.BELOW -> R.string.settings_summary_translation_below
                        TranslationPosition.ABOVE -> R.string.settings_summary_translation_above
                    },
                ),
            )
        MoreSettingsSection.LAYOUT ->
            listOf(
                SummaryPart.Value(
                    R.string.settings_group_default_view,
                    when (state.playerMode) {
                        PlayerExperienceMode.TRANSCRIPT_PANEL -> R.string.settings_transcript_panel_title
                        PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY -> R.string.settings_scroll_overlay_title
                    },
                ),
            )
        MoreSettingsSection.WORD_LEARNING ->
            listOf(
                SummaryPart.Value(R.string.settings_summary_word_colors, onOff(state.wordColors)),
                SummaryPart.Value(R.string.study_known_words_title, state.study.knownWordsMode.labelRes),
            )
        MoreSettingsSection.STUDY -> listOf(SummaryPart.Value(R.string.study_play_mode_title, state.study.playMode.titleRes))
        MoreSettingsSection.FLASHCARDS ->
            if (state.study.ankiDroid) {
                listOf(
                    SummaryPart.Label(state.study.sendTo.nameRes),
                    SummaryPart.Value(R.string.study_card_sentence_sound_title, state.study.cardSentenceSound.labelRes),
                )
            } else {
                listOf(SummaryPart.Value(R.string.study_anki_title, onOff(false)))
            }
        MoreSettingsSection.OVERLAY ->
            listOf(SummaryPart.Value(R.string.settings_overlay_fullscreen_title, onOff(state.overlayInFullscreen)))
        MoreSettingsSection.ADS ->
            listOf(
                SummaryPart.Value(R.string.ads_skip_ads_title, onOff(state.ads.skipAds)),
                SummaryPart.Value(R.string.ads_skip_sponsors_title, onOff(state.ads.skipSponsors)),
            )
        MoreSettingsSection.TABS ->
            listOf(SummaryPart.Value(R.string.tabs_different_accounts_title, onOff(state.differentTabAccounts)))
        MoreSettingsSection.COLORS -> listOf(SummaryPart.Label(state.appColors.labelRes))
        MoreSettingsSection.AI_ASSISTANT -> aiSummary(state.ai)
        MoreSettingsSection.UPDATES -> listOf(SummaryPart.Value(R.string.updates_auto_title, onOff(state.autoUpdate)))
        MoreSettingsSection.TV -> listOf(SummaryPart.Value(R.string.tv_layout_title, onOff(state.tvLayout)))
        // Nothing to switch here, so the line says what the section does.
        MoreSettingsSection.BACKUP -> listOf(SummaryPart.Label(R.string.backup_section_summary))
    }

private fun aiSummary(ai: AiSummaryState?): List<SummaryPart> =
    when {
        ai == null -> emptyList()
        !ai.enabled -> listOf(SummaryPart.Label(R.string.ai_action_off))
        // The service says nothing useful before it can answer; a missing key is the next step.
        !ai.hasKey -> listOf(SummaryPart.Label(R.string.ai_settings_keys_none))
        else -> listOf(SummaryPart.Label(ai.serviceRes))
    }

/** [parts] as the one line the section shows, in the app's language. */
@Composable
internal fun settingsSummaryText(parts: List<SummaryPart>): String {
    val texts =
        parts.map { part ->
            when (part) {
                is SummaryPart.Plain -> part.text
                is SummaryPart.Label -> part.number?.let { stringResource(part.res, it) } ?: stringResource(part.res)
                is SummaryPart.Value ->
                    stringResource(R.string.settings_summary_value, stringResource(part.nameRes), stringResource(part.valueRes))
            }
        }
    return texts.joinToString(SETTINGS_SUMMARY_SEPARATOR)
}

/** A stored setting, read again whenever one of [keys] changes or Reset all settings clears them. */
@Composable
private fun <T> rememberStoredSetting(
    vararg keys: String,
    read: (SharedPreferences) -> T,
): State<T> {
    val context = LocalContext.current
    val preferences = remember(context) { studyPreferences(context) }
    val value = remember(preferences) { mutableStateOf(read(preferences)) }
    DisposableEffect(preferences) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key in keys) value.value = read(preferences)
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        value.value = read(preferences)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

/**
 * The settings the page does not hold itself, kept up to date while the page is open: each
 * section saves its own changes, so the lines follow the stored values.
 */
@Composable
internal fun rememberSettingsSummaryState(pageValues: SettingsSummaryState): SettingsSummaryState {
    val study by rememberStudySettings()
    val ads by rememberAdSkipSettings()
    val differentTabAccounts by rememberDifferentTabAccounts()
    val tvLayout by rememberTvLayout()
    val overlayInFullscreen by rememberStoredSetting(AUTO_OVERLAY_FULLSCREEN_PREFERENCE) {
        it.getBoolean(AUTO_OVERLAY_FULLSCREEN_PREFERENCE, true)
    }
    val autoUpdate by rememberStoredSetting(APP_AUTO_UPDATE_PREFERENCE) { it.getBoolean(APP_AUTO_UPDATE_PREFERENCE, false) }
    val appColors by rememberStoredSetting(APP_COLOR_THEME_PREFERENCE) {
        storedAppColorTheme(it.getString(APP_COLOR_THEME_PREFERENCE, null))
    }
    val ai =
        LocalAiAssistant.current?.controller?.let { controller ->
            val aiState by controller.state.collectAsStateWithLifecycle()
            AiSummaryState(aiState.settings.enabled, aiState.hasKey, aiState.settings.provider.labelRes)
        }
    return pageValues.copy(
        study = study,
        overlayInFullscreen = overlayInFullscreen,
        ads = ads,
        differentTabAccounts = differentTabAccounts,
        appColors = appColors,
        ai = ai,
        autoUpdate = autoUpdate,
        tvLayout = tvLayout,
    )
}
