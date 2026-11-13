package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.CardPlace
import com.kienhoang.dualsubreplay.translation.SubtitleTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSummariesTest {
    private val defaults = SettingsSummaryState(languages = "Auto → Vietnamese")

    private fun summary(
        section: MoreSettingsSection,
        state: SettingsSummaryState = defaults,
    ) = settingsSectionSummary(section, state)

    private fun value(
        nameRes: Int,
        on: Boolean,
    ) = SummaryPart.Value(nameRes, if (on) R.string.ai_action_on else R.string.ai_action_off)

    @Test
    fun everySectionSaysSomethingExceptAnAssistantThatIsNotBuiltIn() {
        for (section in MoreSettingsSection.entries - MoreSettingsSection.AI_ASSISTANT) {
            assertTrue(section.name, summary(section).isNotEmpty())
        }
        assertTrue(summary(MoreSettingsSection.AI_ASSISTANT).isEmpty())
    }

    @Test
    fun subtitlesShowTheLanguagesTranslatorSizeAndView() {
        assertEquals(listOf(SummaryPart.Plain("Auto → Vietnamese")), summary(MoreSettingsSection.LANGUAGES))
        assertEquals(
            listOf(SummaryPart.Label(SubtitleTranslator.GOOGLE.titleRes)),
            summary(MoreSettingsSection.TRANSLATION),
        )
        assertEquals(
            listOf(SummaryPart.Label(SubtitleTranslator.AI_ONLY.titleRes)),
            summary(MoreSettingsSection.TRANSLATION, defaults.copy(translator = SubtitleTranslator.AI_ONLY)),
        )
        assertEquals(
            listOf(SummaryPart.Label(R.string.settings_text_size, 100), SummaryPart.Label(R.string.settings_summary_translation_below)),
            summary(MoreSettingsSection.READING),
        )
        assertEquals(
            listOf(SummaryPart.Label(R.string.settings_text_size, 150), SummaryPart.Label(R.string.settings_summary_translation_above)),
            summary(MoreSettingsSection.READING, defaults.copy(fontScale = 1.5f, translationPosition = TranslationPosition.ABOVE)),
        )
        assertEquals(
            listOf(SummaryPart.Value(R.string.settings_group_default_view, R.string.settings_scroll_overlay_title)),
            summary(MoreSettingsSection.LAYOUT, defaults.copy(playerMode = PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY)),
        )
    }

    @Test
    fun studySectionsFollowTheStudySettings() {
        val study =
            StudySettings(
                playMode = StudyPlayMode.DICTATION,
                knownWordsMode = KnownWordsMode.AUTO,
                sendTo = FlashcardApp.ANKICONNECT,
                cardSentenceSound = CardPlace.OFF,
            )
        val state = defaults.copy(study = study, wordColors = false)
        assertEquals(
            listOf(
                value(R.string.settings_summary_word_colors, on = false),
                SummaryPart.Value(R.string.study_known_words_title, R.string.study_known_words_auto),
            ),
            summary(MoreSettingsSection.WORD_LEARNING, state),
        )
        assertEquals(
            listOf(SummaryPart.Value(R.string.study_play_mode_title, R.string.study_play_mode_dictation)),
            summary(MoreSettingsSection.STUDY, state),
        )
        assertEquals(
            listOf(
                SummaryPart.Label(R.string.study_app_ankiconnect),
                SummaryPart.Value(R.string.study_card_sentence_sound_title, R.string.study_card_place_off),
            ),
            summary(MoreSettingsSection.FLASHCARDS, state),
        )
        // With Send to off, the app and the card's sound mean nothing.
        assertEquals(
            listOf(value(R.string.study_anki_title, on = false)),
            summary(MoreSettingsSection.FLASHCARDS, defaults.copy(study = study.copy(ankiDroid = false))),
        )
    }

    @Test
    fun videoAndAppSectionsShowTheirSwitches() {
        val state =
            defaults.copy(
                overlayInFullscreen = false,
                ads = AdSkipSettings(skipAds = true, skipSponsors = false),
                differentTabAccounts = true,
                appColors = AppColorThemeOption.NAVY,
                autoUpdate = true,
                tvLayout = true,
            )
        assertEquals(
            listOf(value(R.string.settings_overlay_fullscreen_title, on = false)),
            summary(MoreSettingsSection.OVERLAY, state),
        )
        assertEquals(
            listOf(value(R.string.ads_skip_ads_title, on = true), value(R.string.ads_skip_sponsors_title, on = false)),
            summary(MoreSettingsSection.ADS, state),
        )
        assertEquals(listOf(value(R.string.tabs_different_accounts_title, on = true)), summary(MoreSettingsSection.TABS, state))
        assertEquals(listOf(SummaryPart.Label(R.string.settings_color_navy)), summary(MoreSettingsSection.COLORS, state))
        assertEquals(listOf(value(R.string.updates_auto_title, on = true)), summary(MoreSettingsSection.UPDATES, state))
        assertEquals(listOf(value(R.string.tv_layout_title, on = true)), summary(MoreSettingsSection.TV, state))
    }

    @Test
    fun theAssistantSaysOffThenNoKeyThenItsService() {
        fun ai(
            enabled: Boolean,
            hasKey: Boolean,
        ) = summary(MoreSettingsSection.AI_ASSISTANT, defaults.copy(ai = AiSummaryState(enabled, hasKey, R.string.ai_settings_service)))
        assertEquals(listOf(SummaryPart.Label(R.string.ai_action_off)), ai(enabled = false, hasKey = true))
        assertEquals(listOf(SummaryPart.Label(R.string.ai_settings_keys_none)), ai(enabled = true, hasKey = false))
        assertEquals(listOf(SummaryPart.Label(R.string.ai_settings_service)), ai(enabled = true, hasKey = true))
    }
}
