package com.kienhoang.dualsubreplay.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import com.kienhoang.dualsubreplay.assistant.AI_MEMORY_TOOLS
import com.kienhoang.dualsubreplay.assistant.AI_NEWS_VERSION
import com.kienhoang.dualsubreplay.assistant.AI_SAVE_MEMORY_TOOL
import com.kienhoang.dualsubreplay.assistant.AI_SEARCH_TOOL
import com.kienhoang.dualsubreplay.assistant.AI_SETTING_TOOL
import com.kienhoang.dualsubreplay.assistant.AI_TOOLS
import com.kienhoang.dualsubreplay.assistant.AiAction
import com.kienhoang.dualsubreplay.assistant.AiActionOutcome
import com.kienhoang.dualsubreplay.assistant.AiActionText
import com.kienhoang.dualsubreplay.assistant.AiAppActions
import com.kienhoang.dualsubreplay.assistant.AiAppSnapshot
import com.kienhoang.dualsubreplay.assistant.AiAssistantController
import com.kienhoang.dualsubreplay.assistant.AiAssistantSettings
import com.kienhoang.dualsubreplay.assistant.AiAttachment
import com.kienhoang.dualsubreplay.assistant.AiAttachmentKind
import com.kienhoang.dualsubreplay.assistant.AiChat
import com.kienhoang.dualsubreplay.assistant.AiChatException
import com.kienhoang.dualsubreplay.assistant.AiChatRequest
import com.kienhoang.dualsubreplay.assistant.AiChatTransport
import com.kienhoang.dualsubreplay.assistant.AiErrorKind
import com.kienhoang.dualsubreplay.assistant.AiHistoryStorage
import com.kienhoang.dualsubreplay.assistant.AiKeyStore
import com.kienhoang.dualsubreplay.assistant.AiMemory
import com.kienhoang.dualsubreplay.assistant.AiMemoryChange
import com.kienhoang.dualsubreplay.assistant.AiMemoryData
import com.kienhoang.dualsubreplay.assistant.AiMemoryKeeper
import com.kienhoang.dualsubreplay.assistant.AiModelInfo
import com.kienhoang.dualsubreplay.assistant.AiPanelPage
import com.kienhoang.dualsubreplay.assistant.AiProvider
import com.kienhoang.dualsubreplay.assistant.AiReply
import com.kienhoang.dualsubreplay.assistant.AiSetting
import com.kienhoang.dualsubreplay.assistant.AiSettingsStorage
import com.kienhoang.dualsubreplay.assistant.AiThinking
import com.kienhoang.dualsubreplay.assistant.AiToolCall
import com.kienhoang.dualsubreplay.assistant.InMemoryAiMemoryStorage
import com.kienhoang.dualsubreplay.assistant.SecretCipher
import com.kienhoang.dualsubreplay.assistant.SecretStorage
import com.kienhoang.dualsubreplay.assistant.aiActionNote
import com.kienhoang.dualsubreplay.assistant.aiCheckedSetup
import com.kienhoang.dualsubreplay.assistant.aiDataUrl
import com.kienhoang.dualsubreplay.translation.TranslationEngine
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import com.kienhoang.dualsubreplay.ui.theme.dualSubColorScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

/** The top-right assistant button, its right-side panel, and the AI section in More settings; no network. */
class AiAssistantPanelUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val requests = mutableListOf<AiChatRequest>()
    private var failure: AiChatException? = null

    /** Answers given before the usual one, for example a tool call. */
    private val replies = ArrayDeque<AiReply>()
    private val secrets = mutableMapOf<String, String>()

    /** Past the first page that introduces the assistant, unless a test starts before it. */
    private var settings = AiAssistantSettings(introSeen = true, newsSeen = AI_NEWS_VERSION)
    private val memory = InMemoryAiMemoryStorage()

    private val keyStore =
        AiKeyStore(
            object : SecretStorage {
                override fun get(name: String) = secrets[name]

                override fun put(values: Map<String, String>) {
                    secrets += values
                }

                override fun remove(names: List<String>) {
                    names.forEach(secrets::remove)
                }
            },
            object : SecretCipher {
                override fun encrypt(plain: ByteArray) = plain.reversedArray()

                override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
            },
        )

    private val controller by lazy {
        AiAssistantController(
            scope = scope,
            transport =
                AiChatTransport { request ->
                    requests += request
                    failure?.let { throw it }
                    replies.removeFirstOrNull() ?: AiReply("**に** marks where you go.")
                },
            keyStore = keyStore,
            settingsStorage =
                object : AiSettingsStorage {
                    override fun load() = settings

                    override fun save(settings: AiAssistantSettings) {
                        this@AiAssistantPanelUiTest.settings = settings
                    }
                },
            historyStorage =
                object : AiHistoryStorage {
                    override fun load() = emptyList<AiChat>()

                    override fun save(chats: List<AiChat>) = Unit

                    override fun clear() = Unit
                },
            io = Dispatchers.Unconfined,
            modelLister = { _, _ -> listOf(AiModelInfo("gemini-flash-latest"), AiModelInfo("gemma-4-31b-it", free = true)) },
            memoryStorage = memory,
        )
    }

    private val host by lazy { AiAssistantHost(controller) { "Guide" } }
    private val fallback =
        DualSubUiState(
            activeVideoId = "video",
            translationEngine = TranslationEngine.GOOGLE_WEB,
            onDeviceFallback = true,
            onlineTranslationFailureDetail = "Google Translate refused the request (HTTP 429).",
        )
    private val snapshot = AiAppSnapshot("1.0", "GitHub", "English", "Japanese", "English", "on the device", emptyList())

    @After
    fun tearDown() = scope.cancel()

    /** A key that already answered, as after a passed check. */
    private fun checkedGeminiKey() {
        keyStore.save(AiProvider.GEMINI, "AIza-FAKE-test-key-for-unit-tests-rstu")
        keyStore.markChecked(AiProvider.GEMINI, aiCheckedSetup(settings, AiProvider.GEMINI))
    }

    private fun showTopBarAndPanel(state: DualSubUiState = fallback) {
        composeRule.setContent {
            DualSubTheme {
                CompositionLocalProvider(LocalAiAssistant provides host) {
                    Box(Modifier.fillMaxSize()) {
                        val problems = appProblems(state, onTryGoogleAgain = {}, onRetry = {})
                        TopBarAssistantOrIssueButton(state, problems, onTryGoogleAgain = {}, onRetry = {})
                        AiAssistantPanel(host, problems, { snapshot }, currentLineQuestion = null)
                    }
                }
            }
        }
    }

    @Test
    fun theFirstOpenIntroducesTheAssistantThenLetsStartShowsTheSetup() {
        settings = AiAssistantSettings()
        showTopBarAndPanel()
        composeRule.onNodeWithTag("ai_assistant_button").performClick()
        composeRule.onNodeWithTag("ai_intro").assertIsDisplayed()
        composeRule.onNodeWithText("Meet your AI assistant").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_setup_card").assertDoesNotExist()
        composeRule.onNodeWithTag("ai_settings_button").assertDoesNotExist()
        composeRule.onNodeWithTag("ai_intro_decline").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("You can turn it on again in More settings → AI assistant.").assertExists()
        saveUiEvidence("ai_panel_intro")
        composeRule.onNodeWithTag("ai_intro_start").performScrollTo().performClick()
        assertTrue(settings.introSeen)
        assertTrue(settings.enabled)
        composeRule.onNodeWithTag("ai_intro").assertDoesNotExist()
        composeRule.onNodeWithTag("ai_setup_card").performScrollTo().assertIsDisplayed()
        // The next time, the panel opens straight to the setup or the chat.
        composeRule.onNodeWithTag("ai_close_button").performClick()
        composeRule.onNodeWithTag("ai_assistant_button").performClick()
        composeRule.onNodeWithTag("ai_intro").assertDoesNotExist()
        assertTrue(requests.isEmpty())
    }

    @Test
    fun dontUseAiOnTheFirstPageTurnsTheAssistantOff() {
        settings = AiAssistantSettings()
        showTopBarAndPanel()
        composeRule.onNodeWithTag("ai_assistant_button").performClick()
        composeRule.onNodeWithTag("ai_intro_decline").performScrollTo().performClick()
        assertFalse(settings.enabled)
        assertTrue(settings.introSeen)
        composeRule.onNodeWithTag("ai_panel").assertDoesNotExist()
        composeRule.onNodeWithTag("ai_assistant_button").assertDoesNotExist()
        assertTrue(requests.isEmpty())
    }

    @Test
    fun withoutAKeyThePanelShowsTheProblemAndHowToConnect() {
        showTopBarAndPanel()
        composeRule.onNodeWithTag("ai_assistant_problem_dot", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("ai_panel").assertDoesNotExist()
        composeRule.onNodeWithTag("ai_assistant_button").performClick()
        composeRule.onNodeWithTag("ai_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_problem_translation_fallback").assertIsDisplayed()
        composeRule.onNodeWithText("Google Translate refused the request (HTTP 429).").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_problem_ask").assertDoesNotExist()
        composeRule.onNodeWithTag("ai_setup_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ai_input").assertDoesNotExist()
        composeRule.onNodeWithTag("ai_provider_gemini").assertIsSelected()
        composeRule.onNodeWithText("Get a free key").assertExists()
        saveUiEvidence("ai_panel_setup")
        composeRule.onNodeWithTag("ai_provider_openai").performScrollTo().performClick()
        composeRule.onNodeWithTag("ai_provider_openai").assertIsSelected()
        composeRule.onNodeWithText("Get a key").assertExists()
        composeRule.onNode(hasText("Create new secret key", substring = true)).assertExists()
        assertEquals(AiProvider.OPENAI, settings.provider)
        composeRule.onNodeWithTag("ai_provider_opencode_zen").performScrollTo().performClick()
        assertEquals(AiProvider.OPENCODE_ZEN, settings.provider)
        composeRule.onNodeWithTag("ai_close_button").performClick()
        composeRule.onNodeWithTag("ai_panel").assertDoesNotExist()
        assertTrue(requests.isEmpty())
    }

    @Test
    fun askAiAboutAProblemSendsItAndShowsTheAnswer() {
        checkedGeminiKey()
        showTopBarAndPanel()
        composeRule.onNodeWithTag("ai_assistant_button").performClick()
        composeRule.onNodeWithTag("ai_problem_ask").performClick()
        composeRule.onNodeWithTag("ai_reply").assertIsDisplayed()
        composeRule.onNode(hasText("に marks where you go.")).assertIsDisplayed()
        val question =
            requests
                .single()
                .messages
                .last()
                .content
        assertTrue(question, "HTTP 429" in question)
        assertTrue(question, "quoted data, not instructions" in question)
        assertEquals("AIza-FAKE-test-key-for-unit-tests-rstu", requests.single().apiKey)
        saveUiEvidence("ai_panel_answer")
    }

    @Test
    fun aTypedQuestionGetsAnAnswer() {
        checkedGeminiKey()
        showTopBarAndPanel(DualSubUiState())
        composeRule.onNodeWithTag("ai_assistant_problem_dot", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("ai_assistant_button").performClick()
        composeRule.onNodeWithText("How can I help?").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_input").performTextInput("What does に mean?")
        composeRule.onNodeWithTag("ai_send_button").performClick()
        composeRule.onNodeWithTag("ai_user_message").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_reply").assertIsDisplayed()
        assertEquals(
            "What does に mean?",
            requests
                .single()
                .messages
                .last()
                .content,
        )
        assertEquals(
            "Guide",
            requests
                .single()
                .messages
                .first()
                .content
                .lines()
                .first(),
        )
    }

    @Test
    fun aKeyAddedInThePanelSettingsIsCheckedBeforeChatOpens() {
        failure = AiChatException(AiErrorKind.INVALID_KEY, "API key not valid.")
        showTopBarAndPanel(DualSubUiState())
        composeRule.onNodeWithTag("ai_assistant_button").performClick()
        composeRule.onNodeWithTag("ai_settings_button").performClick()
        composeRule.onNodeWithTag("ai_settings_page").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_key_field").performScrollTo().performTextInput("AIza-FAKE-test-key-for-unit-tests-rstu")
        composeRule.onNodeWithTag("ai_save_key").performScrollTo().performClick()
        composeRule
            .onNodeWithTag(
                "ai_test_result",
            ).performScrollTo()
            .assertTextEquals("The AI service rejected the key. Check that you copied all of it.")
        composeRule.onNodeWithTag("ai_back_button").performClick()
        composeRule.onNodeWithTag("ai_key_check_failed").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ai_input").assertDoesNotExist()
        saveUiEvidence("ai_panel_key_failed")
        failure = null
        composeRule.onNodeWithTag("ai_check_key").performScrollTo().performClick()
        composeRule.onNodeWithTag("ai_input").assertIsDisplayed()
        composeRule.onNodeWithText("How can I help?").assertIsDisplayed()
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.messages.last().content == "OK?" })
    }

    /** Runs the assistant's actions at once and records them, with labels like the app's. */
    private class RecordingActions : AiAppActions {
        val performed = mutableListOf<AiAction>()

        override fun lookAtVideo(linesAround: Int) = "No video is open."

        override fun refusal(action: AiAction): String? = null

        override fun describe(action: AiAction) =
            when (action) {
                is AiAction.SearchYouTube -> AiActionText("Search YouTube for “${action.query}”", "Search")
                else -> AiActionText("Text size: 150%", "Apply")
            }

        override suspend fun perform(action: AiAction): AiActionOutcome {
            performed += action
            val undo = if (action is AiAction.ChangeSetting) listOf(action.copy(value = "100")) else emptyList()
            return AiActionOutcome.Done(describe(action).label, aiActionNote(action), undo)
        }
    }

    private fun call(
        name: String,
        arguments: String,
    ) = AiToolCall("call_$name", name, arguments, """{"id":"call_$name","type":"function","function":{"name":"$name"}}""")

    @Test
    fun aSettingChangeShowsAChipWithUndo() {
        checkedGeminiKey()
        val actions = RecordingActions()
        controller.appActions = actions
        replies += AiReply("", listOf(call(AI_SETTING_TOOL, """{"setting":"text_size","value":"150"}""")))
        replies += AiReply("Done! The subtitles are bigger now.")
        showTopBarAndPanel(DualSubUiState())
        composeRule.onNodeWithTag("ai_assistant_button").performClick()
        composeRule.onNodeWithTag("ai_input").performTextInput("Make the subtitles bigger")
        composeRule.onNodeWithTag("ai_send_button").performClick()
        composeRule.onNodeWithText("Done! The subtitles are bigger now.").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_action_chip").assertIsDisplayed().assertTextContains("Text size: 150%")
        assertEquals(1, actions.performed.size)
        saveUiEvidence("ai_panel_action_chip")
        composeRule.onNodeWithTag("ai_action_undo").performClick()
        composeRule.onNodeWithText("Undone").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_action_undo").assertDoesNotExist()
        assertEquals(AiAction.ChangeSetting(AiSetting.TEXT_SIZE, "100"), actions.performed.last())
        // The tool result went back to the model in the second request.
        assertEquals(2, requests.size)
        assertTrue(
            requests[1]
                .messages
                .last()
                .content
                .startsWith("Done: Set text_size to 150."),
        )
    }

    @Test
    fun aSearchWaitsForItsButton() {
        checkedGeminiKey()
        val actions = RecordingActions()
        controller.appActions = actions
        replies += AiReply("", listOf(call(AI_SEARCH_TOOL, """{"query":"Japanese cooking"}""")))
        replies += AiReply("Tap Search to see the videos.")
        showTopBarAndPanel(DualSubUiState())
        composeRule.onNodeWithTag("ai_assistant_button").performClick()
        composeRule.onNodeWithTag("ai_input").performTextInput("Find Japanese cooking videos")
        composeRule.onNodeWithTag("ai_send_button").performClick()
        composeRule.onNodeWithTag("ai_action_card").assertIsDisplayed().assertTextContains("Search YouTube for “Japanese cooking”")
        assertTrue(actions.performed.isEmpty())
        saveUiEvidence("ai_panel_action_card")
        composeRule.onNodeWithTag("ai_action_confirm").assertTextEquals("Search").performClick()
        composeRule.onNodeWithTag("ai_action_card").assertDoesNotExist()
        assertEquals(listOf<AiAction>(AiAction.SearchYouTube("Japanese cooking")), actions.performed)
        composeRule.onNodeWithTag("ai_action_chip").assertIsDisplayed()
    }

    @Test
    fun thePanelUsesTheAppThemeEvenOutsideTheAppScreen() {
        // LearningPlayerRoot draws the overlay beside DualSubApp, outside its theme.
        composeRule.setContent {
            AiAssistantOverlay(host, DualSubUiState(), PlayerExperienceMode.TRANSCRIPT_PANEL, false, onTryGoogleAgain = {}, onRetry = {})
        }
        composeRule.runOnIdle { controller.openPanel() }
        val pixels = composeRule.onNodeWithTag("ai_panel").captureToImage().toPixelMap()
        val panelBackground = pixels[pixels.width - 3, pixels.height / 2]
        assertEquals(dualSubColorScheme(DEFAULT_APP_THEME_ACCENT_KEY).surfaceContainer.toArgb(), panelBackground.toArgb())
    }

    @Test
    fun theBarUnderTheChatBoxChangesTheModelAndThinkingLevel() {
        checkedGeminiKey()
        showTopBarAndPanel(DualSubUiState())
        composeRule.onNodeWithTag("ai_assistant_button").performClick()
        composeRule.onNodeWithTag("ai_model_button").assertTextContains("gemini-flash-latest")
        composeRule.onNodeWithTag("ai_model_button").performClick()
        composeRule.onNodeWithText("gemini-2.5-flash").performClick()
        composeRule.onNodeWithTag("ai_model_button").assertTextContains("gemini-2.5-flash")
        assertEquals("gemini-2.5-flash", requests.last().model)
        composeRule.onNodeWithTag("ai_effort_button").performClick()
        composeRule.onNodeWithTag("ai_effort_high").performClick()
        assertEquals(AiThinking.HIGH, settings.thinking)
        composeRule.onNodeWithTag("ai_effort_button").assertTextContains("High")
        composeRule.onNodeWithContentDescription("Thinking: High").assertIsDisplayed()
        saveUiEvidence("ai_panel_model_bar")
        composeRule.onNodeWithTag("ai_model_button").performClick()
        composeRule.onNodeWithTag("ai_models_more").performClick()
        composeRule.onNodeWithText("Choose a model").assertIsDisplayed()
        composeRule.onNodeWithText("gemma-4-31b-it").assertIsDisplayed()
        composeRule.onNodeWithText("Free").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_models_search").performTextInput("my-own-model")
        composeRule.onNodeWithTag("ai_models_use_typed").performClick()
        composeRule.onNodeWithTag("ai_model_button").assertTextContains("my-own-model")
        assertEquals("my-own-model", settings.modelFor(AiProvider.GEMINI))
    }

    @Test
    fun picturesAndFilesWaitAboveTheChatBoxThenGoWithTheQuestion() {
        checkedGeminiKey()
        val pixels = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val jpeg = ByteArrayOutputStream().also { pixels.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        showTopBarAndPanel(DualSubUiState())
        composeRule.onNodeWithTag("ai_assistant_button").performClick()
        composeRule.onNodeWithTag("ai_attach_button").performClick()
        composeRule.onNodeWithTag("ai_attach_photo").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_attach_file").assertIsDisplayed()
        // The pickers belong to the system, so the test closes the menu and adds files directly.
        Espresso.pressBack()
        composeRule.onNodeWithTag("ai_attach_photo").assertDoesNotExist()
        composeRule.onNodeWithTag("ai_panel").assertIsDisplayed()
        composeRule.runOnIdle {
            controller.addAttachments(
                listOf(
                    AiAttachment("page.jpg", AiAttachmentKind.PICTURE, aiDataUrl("image/jpeg", jpeg)),
                    AiAttachment("episode.srt", AiAttachmentKind.TEXT, "こんにちは"),
                ),
            )
        }
        composeRule.onNodeWithTag("ai_draft_attachments").assertIsDisplayed()
        composeRule.onNodeWithText("page.jpg").assertIsDisplayed()
        saveUiEvidence("ai_panel_attachments")
        composeRule.onNodeWithContentDescription("Remove episode.srt").performClick()
        composeRule.onNodeWithText("episode.srt").assertDoesNotExist()
        // A picture alone can be sent; the question then asks to explain it.
        composeRule.onNodeWithTag("ai_send_button").performClick()
        val question = requests.last().messages.last()
        assertEquals("Please explain this.", question.content)
        assertEquals(listOf("page.jpg"), question.attachments.map { it.name })
        composeRule.onNodeWithTag("ai_draft_attachments").assertDoesNotExist()
        composeRule.onNode(hasText("page.jpg") and hasAnyAncestor(hasTestTag("ai_user_message"))).assertIsDisplayed()
    }

    @Test
    fun turningTheAssistantOffBringsBackTheTranslationIcon() {
        showTopBarAndPanel()
        composeRule.onNodeWithTag("ai_assistant_button").assertIsDisplayed()
        composeRule.runOnIdle { controller.setEnabled(false) }
        composeRule.onNodeWithTag("ai_assistant_button").assertDoesNotExist()
        composeRule.onNodeWithTag("translation_issue_button").assertIsDisplayed()
        assertFalse(settings.enabled)
    }

    @Test
    fun theAiSectionInMoreSettingsSavesAKeyAndTurnsTheAssistantOff() {
        composeRule.setContent {
            DualSubTheme {
                CompositionLocalProvider(LocalAiAssistant provides host) {
                    SubtitleSettingsDialog(
                        sourcePreference = "ja",
                        targetLanguage = "en",
                        availableSourceLanguages = emptyList(),
                        fontScale = 1f,
                        landscapeSplitEnabled = true,
                        onSourceChange = {},
                        onTargetChange = {},
                        onFontScaleChange = {},
                        onLandscapeSplitChange = {},
                        onDismiss = {},
                    )
                }
            }
        }
        composeRule.onNodeWithTag("settings_section_ai_assistant").performScrollTo().performClick()
        composeRule.onNodeWithTag("ai_provider_openrouter").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ai_key_field").performScrollTo().performTextInput("sk-or-v1-abcdefghijklmnopqrstuvwxyz")
        composeRule.onNodeWithTag("ai_save_key").performScrollTo().performClick()
        composeRule.onNodeWithTag("ai_key_saved").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ai_test_result").assertIsDisplayed()
        assertEquals(AiProvider.OPENROUTER, settings.provider)
        assertEquals(AiProvider.OPENROUTER.baseUrl, requests.single().baseUrl)
        assertFalse(secrets.values.any { "sk-or-v1-abcdefghijklmnopqrstuvwxyz" in it })
        saveUiEvidence("ai_settings_key_saved")
        composeRule
            .onNodeWithTag("ai_enabled_switch")
            .performScrollTo()
            .assertIsOn()
            .performClick()
        assertFalse(settings.enabled)
        composeRule.onNodeWithTag("ai_key_field").assertDoesNotExist()
    }

    /** Saves and forgets through the controller, as the app's actions do, with the app's English labels. */
    private class MemoryActions(
        private val keeper: AiMemoryKeeper,
    ) : AiAppActions {
        override fun lookAtVideo(linesAround: Int) = "No video is open."

        override fun refusal(action: AiAction) = keeper.memoryRefusal(action)

        override fun describe(action: AiAction) = AiActionText("Save to memory: “${(action as? AiAction.SaveMemory)?.text}”", "Save")

        override suspend fun perform(action: AiAction): AiActionOutcome =
            when (val change = keeper.changeMemory(action)) {
                is AiMemoryChange.Refused -> AiActionOutcome.Refused(change.reason)
                is AiMemoryChange.Done -> AiActionOutcome.Done("Saved to memory: “${change.text}”", change.note, change.undo)
            }
    }

    @Test
    fun aSavedMemoryShowsAChipWhoseManageOpensTheMemoryPage() {
        checkedGeminiKey()
        controller.appActions = MemoryActions(controller)
        replies += AiReply("", listOf(call(AI_SAVE_MEMORY_TOOL, """{"text":"Studies for JLPT N3, exam in December"}""")))
        replies += AiReply("Good luck with N3! I'll keep that in mind.")
        showTopBarAndPanel(DualSubUiState())
        composeRule.onNodeWithTag("ai_assistant_button").performClick()
        composeRule.onNodeWithTag("ai_chat_memory_chip").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_input").performTextInput("I'm studying for JLPT N3. The exam is in December.")
        composeRule.onNodeWithTag("ai_send_button").performClick()
        composeRule.onNodeWithText("Good luck with N3! I'll keep that in mind.").assertIsDisplayed()
        composeRule
            .onNodeWithTag(
                "ai_action_chip",
            ).assertIsDisplayed()
            .assertTextContains("Saved to memory: “Studies for JLPT N3, exam in December”")
        composeRule.onNodeWithTag("ai_action_undo").assertIsDisplayed()
        assertEquals(AI_TOOLS + AI_MEMORY_TOOLS, requests.first().tools)
        saveUiEvidence("ai_panel_memory_chip")

        composeRule.onNodeWithTag("ai_action_manage").performClick()
        composeRule.onNodeWithTag("ai_memory_page").assertIsDisplayed()
        composeRule.onNodeWithText("Memory").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_memory_switch").assertIsOn()
        composeRule.onNodeWithTag("ai_memory_edit").performScrollTo().performClick()
        composeRule.onNodeWithTag("ai_memory_edit_field").performTextClearance()
        composeRule.onNodeWithTag("ai_memory_edit_field").performTextInput("Studies for JLPT N3, exam on December 7")
        composeRule.onNodeWithTag("ai_memory_edit_save").performClick()
        composeRule.onNodeWithText("Studies for JLPT N3, exam on December 7").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ai_instructions").performScrollTo().performTextInput("Explain in Vietnamese. Keep answers short.")
        composeRule.onNodeWithTag("ai_instructions_save").performClick()
        assertEquals("Explain in Vietnamese. Keep answers short.", memory.data.instructions)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("ai_memory_switch").performScrollTo()
        saveUiEvidence("ai_panel_memory_page")

        // Undo on the chip removes the memory, also after an edit.
        composeRule.onNodeWithTag("ai_back_button").performClick()
        composeRule.onNodeWithTag("ai_action_undo").performClick()
        composeRule.onNodeWithText("Undone").assertIsDisplayed()
        assertTrue(memory.data.memories.isEmpty())
    }

    @Test
    fun theMemoryPageDeletesAndTurnsMemoryOff() {
        memory.data =
            AiMemoryData(memories = listOf(AiMemory("m1", "Studies for JLPT N4", 1), AiMemory("m2", "Likes short answers", 2)))
        checkedGeminiKey()
        showTopBarAndPanel(DualSubUiState())
        composeRule.runOnIdle {
            controller.openPanel()
            controller.showPage(AiPanelPage.MEMORY)
        }
        composeRule.onAllNodesWithTag("ai_memory_item").assertCountEquals(2)
        composeRule.onAllNodesWithTag("ai_memory_delete")[1].performClick()
        assertEquals(listOf("Studies for JLPT N4"), memory.data.memories.map { it.text })
        composeRule.onNodeWithTag("ai_memory_clear").performScrollTo().performClick()
        composeRule.onNodeWithTag("ai_memory_clear_confirm").performClick()
        assertTrue(memory.data.memories.isEmpty())
        composeRule.onNodeWithTag("ai_memory_item").assertDoesNotExist()
        composeRule
            .onNodeWithTag("ai_memory_switch")
            .performScrollTo()
            .performClick()
            .assertIsOff()
        assertFalse(settings.memoryEnabled)
        // With memory off, a new chat has no memory chip.
        composeRule.onNodeWithTag("ai_back_button").performClick()
        composeRule.onNodeWithTag("ai_chat_memory_chip").assertDoesNotExist()
    }

    @Test
    fun aChatWithoutMemorySendsNoMemoryActions() {
        checkedGeminiKey()
        controller.appActions = MemoryActions(controller)
        showTopBarAndPanel(DualSubUiState())
        composeRule.onNodeWithTag("ai_assistant_button").performClick()
        composeRule.onNodeWithTag("ai_chat_memory_chip").performClick()
        composeRule.onNodeWithTag("ai_input").performTextInput("Hi")
        composeRule.onNodeWithTag("ai_send_button").performClick()
        composeRule.onNodeWithTag("ai_chat_memory_off").assertIsDisplayed()
        assertEquals(AI_TOOLS, requests.single().tools)
    }

    @Test
    fun peopleWhoUsedTheAssistantBeforeSeeWhatsNewOnce() {
        settings = AiAssistantSettings(introSeen = true)
        checkedGeminiKey()
        showTopBarAndPanel(DualSubUiState())
        composeRule.onNodeWithTag("ai_assistant_button").performClick()
        composeRule.onNodeWithTag("ai_news_card").assertIsDisplayed()
        composeRule.onNodeWithText("New: actions and memory").assertIsDisplayed()
        saveUiEvidence("ai_panel_whats_new")
        composeRule.onNodeWithTag("ai_news_manage").performClick()
        assertEquals(AI_NEWS_VERSION, settings.newsSeen)
        composeRule.onNodeWithTag("ai_memory_page").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_back_button").performClick()
        composeRule.onNodeWithTag("ai_news_card").assertDoesNotExist()
        assertTrue(requests.isEmpty())
    }
}
