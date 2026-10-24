package com.kienhoang.dualsubreplay.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.*
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class WordLearningDialogTest {
    @get:Rule val compose = createComposeRule()
    private val selection = LearningWordSelection(AnalyzedToken("word", 0, 4, PartOfSpeech.NOUN), "en", "vi",
        "dQw4w9WgXcQ", SubtitleSegment(1, 1000, 3000, "a word", null), false)

    @Before
    @After
    fun openAllSections() {
        val editor = studyPreferences(InstrumentationRegistry.getInstrumentation().targetContext).edit()
        WORD_CARD_SECTION_KEYS.forEach(editor::remove)
        editor.commit()
    }

    @Test fun pronouncesOnceAndSavesOnlineClipChoiceWithoutOfflineOption() {
        var spoken = 0
        var saved: Pair<String, Boolean>? = null
        val recompose = mutableStateOf<String?>(null)
        compose.setContent { DualSubTheme {
            WordLearningDialog(selection, true, { "từ" }, { meaning, online -> saved = Pair(meaning, online) },
                { spoken++ }, recompose.value, {})
        } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, spoken); recompose.value = "Voice ready" }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, spoken) }
        saveUiEvidence("word-definition")
        compose.onNodeWithTag("offline_clip_choice").assertDoesNotExist()
        compose.onNodeWithTag("online_clip_choice").assertIsDisplayed()
        compose.onNodeWithTag("save_word").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(Pair("từ", true), saved) }
        compose.onNodeWithTag("word_saved").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun englishWordShowsItsDictionaryFormAndLevel() {
        LearnerData.useAssets(InstrumentationRegistry.getInstrumentation().targetContext.assets)
        assertTrue(EnglishMorphology.awaitReady())
        val went = LanguageAwareTokenizer.tokenize("We went home.", "en").first { it.text == "went" }
        val wentSelection = selection.copy(token = went)
        compose.setContent {
            DualSubTheme { WordLearningDialog(wentSelection, false, { "đi" }, { _, _ -> }, {}, null, {}) }
        }
        compose.onNodeWithTag("dictionary_form").assertTextEquals("Dictionary form: go")
        compose.onNodeWithContentDescription("Word level: CEFR A1").assertIsDisplayed()
        saveUiEvidence("word-card-english")
    }

    @Test fun manualSpeechWorksWhenAutomaticSpeechIsDisabled() {
        var spoken = 0
        compose.setContent { DualSubTheme {
            WordLearningDialog(selection, false, { "từ" }, { _, _ -> }, { spoken++ }, null, {})
        } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, spoken) }
        compose.onNodeWithTag("pronounce_word").performClick()
        compose.runOnIdle { assertEquals(1, spoken) }
    }

    @Test fun liveWordCanBeSavedWithoutOfferingUnreliableClipExamples() {
        var saved: Pair<String, Boolean>? = null
        compose.setContent { DualSubTheme {
            WordLearningDialog(selection.copy(videoId = null, segment = null), false, { "từ" },
                { meaning, online -> saved = Pair(meaning, online) }, {}, null, {})
        } }
        compose.onNodeWithTag("offline_clip_choice").assertDoesNotExist()
        compose.onNodeWithTag("online_clip_choice").assertDoesNotExist()
        compose.onNodeWithTag("save_word").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(Pair("từ", false), saved) }
    }

    @Test
    fun missingVoiceOffersSettingsAndAllowsPronunciationRetry() {
        var settingsOpened = 0
        var spoken = 0
        compose.setContent {
            DualSubTheme {
                WordLearningDialog(
                    selection = selection,
                    autoPronounce = false,
                    onTranslateWord = { "từ" },
                    onSave = { _, _ -> },
                    onSpeak = { spoken++ },
                    speechMessage = "No installed speech engine has a voice for this language.",
                    onDismiss = {},
                    onSpeechSettings = { settingsOpened++ },
                )
            }
        }
        compose.onNodeWithTag("speech_settings").performScrollTo().performClick()
        compose.onNodeWithTag("pronounce_word").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, settingsOpened)
            assertEquals(1, spoken)
        }
    }

    @Test
    fun closeSitsApartFromSaveAndPhraseListsEachWord() {
        val parts =
            listOf(
                AnalyzedToken("looking", 2, 9, PartOfSpeech.VERB),
                AnalyzedToken("forward", 10, 17, PartOfSpeech.ADVERB),
                AnalyzedToken("to", 18, 20, PartOfSpeech.PREPOSITION),
            )
        val phrase =
            selection.copy(
                token = AnalyzedToken("looking forward to", 2, 20, PartOfSpeech.OTHER),
                parts = parts,
            )
        val spokenParts = mutableListOf<String>()
        var dismissed = 0
        compose.setContent {
            DualSubTheme {
                WordLearningDialog(
                    selection = phrase,
                    autoPronounce = false,
                    onTranslateWord = { "mong chờ" },
                    onSave = { _, _ -> },
                    onSpeak = {},
                    speechMessage = null,
                    onDismiss = { dismissed++ },
                    onSpeakPart = { spokenParts += it.text },
                )
            }
        }
        compose.onNodeWithText("Phrase").assertIsDisplayed()
        compose.onNodeWithText("Save to vocabulary").assertIsDisplayed()
        compose.onNodeWithText("Word by word").performScrollTo().assertIsDisplayed()
        saveUiEvidence("phrase-card")
        compose.onNodeWithTag("pronounce_part_1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("forward"), spokenParts) }
        compose.onNodeWithTag("close_word_card").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test
    fun japaneseWordShowsItsMeaningBoxAndEachKanjisPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LearnerData.useAssets(context.assets)
        val stories = MyKanjiStories.inDirectory(context.filesDir)
        stories.set("葉", "")
        val word = AnalyzedToken("葉っぱ", 0, 3, PartOfSpeech.NOUN)
        val leaf =
            selection.copy(
                token = word,
                parts = listOf(word),
                wordLanguage = "ja",
                segment = SubtitleSegment(1, 1000, 3000, "葉っぱが落ちた", null),
            )
        compose.setContent {
            DualSubTheme { WordLearningDialog(leaf, false, { "lá cây" }, { _, _ -> }, {}, null, {}) }
        }
        compose.onNodeWithTag("word_meaning_panel").assertIsDisplayed()
        compose.onNodeWithTag("word_meaning_text").assertTextEquals("lá cây")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("kanji_row_葉")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("kanji_row_葉").performScrollTo().assertIsDisplayed()
        saveUiEvidence("word-card-japanese-kanji")

        compose.onNodeWithTag("kanji_row_葉").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("kanji_han_viet")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("kanji_han_viet").assertTextEquals("DIỆP")
        compose.onNodeWithTag("kanji_part_世").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("kanji_story").performScrollTo().assertTextContains("DIỆP", substring = true)
        saveUiEvidence("kanji-page")
        compose.onNodeWithTag("kanji_play_strokes").performScrollTo().performClick()
        compose.onNodeWithTag("kanji_my_story").performScrollTo().performTextInput("Leaves of the world tree")
        compose.waitUntil(5_000) { stories.get("葉") == "Leaves of the world tree" }
        compose.onNodeWithTag("kanji_back").performScrollTo().performClick()
        compose.onNodeWithTag("kanji_section").assertExists()
        compose.onNodeWithTag("kanji_section_header").performScrollTo().performClick()
        compose.onNodeWithTag("kanji_row_葉").assertDoesNotExist()
        compose.onNodeWithTag("kanji_section_count").assertTextEquals("1")
        saveUiEvidence("word-card-kanji-hidden")
        stories.set("葉", "")
    }

    @Test
    fun chineseWordShowsEachCharactersPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LearnerData.useAssets(context.assets)
        val stories = myHanziStories(context.filesDir)
        stories.set("叶", "")
        val word = AnalyzedToken("树叶", 0, 2, PartOfSpeech.NOUN, reading = "shù yè")
        val leaves =
            selection.copy(
                token = word,
                parts = listOf(word),
                wordLanguage = "zh-Hans",
                segment = SubtitleSegment(1, 1000, 3000, "树叶掉了", null),
            )
        compose.setContent {
            DualSubTheme { WordLearningDialog(leaves, false, { "lá cây" }, { _, _ -> }, {}, null, {}) }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("hanzi_row_叶")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("hanzi_row_树").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("hanzi_row_叶").performScrollTo().assertIsDisplayed()
        saveUiEvidence("word-card-chinese-characters")

        compose.onNodeWithTag("hanzi_row_叶").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("hanzi_han_viet")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("hanzi_han_viet").assertTextEquals("DIỆP")
        compose.onNodeWithTag("hanzi_pinyin").assertTextContains("yè", substring = true)
        compose.onNodeWithTag("hanzi_part_口").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("hanzi_story").performScrollTo().assertTextContains("DIỆP", substring = true)
        saveUiEvidence("hanzi-page")
        compose.onNodeWithTag("kanji_play_strokes").performScrollTo().performClick()
        compose.onNodeWithTag("hanzi_my_story").performScrollTo().performTextInput("A mouth on ten leaves")
        compose.waitUntil(5_000) { stories.get("叶") == "A mouth on ten leaves" }
        compose.onNodeWithTag("hanzi_back").performScrollTo().performClick()
        compose.onNodeWithTag("hanzi_section").assertExists()
        compose.onNodeWithTag("hanzi_section_header").performScrollTo().performClick()
        compose.onNodeWithTag("hanzi_row_叶").assertDoesNotExist()
        compose.onNodeWithTag("hanzi_section_count").assertTextEquals("2")
        stories.set("叶", "")
    }
}
