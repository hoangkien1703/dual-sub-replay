package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.kienhoang.dualsubreplay.assistant.AiAppSnapshot
import com.kienhoang.dualsubreplay.assistant.AiAssistantController
import com.kienhoang.dualsubreplay.assistant.AiAssistantSettings
import com.kienhoang.dualsubreplay.assistant.AiChat
import com.kienhoang.dualsubreplay.assistant.AiChatRequest
import com.kienhoang.dualsubreplay.assistant.AiChatTransport
import com.kienhoang.dualsubreplay.assistant.AiHistoryStorage
import com.kienhoang.dualsubreplay.assistant.AiKeyStore
import com.kienhoang.dualsubreplay.assistant.AiProvider
import com.kienhoang.dualsubreplay.assistant.AiSettingsStorage
import com.kienhoang.dualsubreplay.assistant.SecretCipher
import com.kienhoang.dualsubreplay.assistant.SecretStorage
import com.kienhoang.dualsubreplay.translation.TranslationEngine
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
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

/** The top-right assistant button, its right-side panel, and the AI section in More settings; no network. */
class AiAssistantPanelUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val requests = mutableListOf<AiChatRequest>()
    private val secrets = mutableMapOf<String, String>()
    private var settings = AiAssistantSettings()

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
                    "**に** marks where you go."
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

    private fun showTopBarAndPanel(state: DualSubUiState = fallback) {
        composeRule.setContent {
            DualSubTheme {
                CompositionLocalProvider(LocalAiAssistant provides host) {
                    Box(Modifier.fillMaxSize()) {
                        val problems = appProblems(state, onTryGoogleAgain = {}, onRetry = {})
                        TopBarAssistantOrIssueButton(state, problems, onTryGoogleAgain = {}, onRetry = {})
                        AiAssistantPanel(host, problems, { snapshot }, currentLineQuestion = null, onOpenSettings = {})
                    }
                }
            }
        }
    }

    @Test
    fun withoutAKeyThePanelShowsTheProblemAndHowToConnect() {
        showTopBarAndPanel()
        composeRule.onNodeWithTag("ai_assistant_problem_dot").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_panel").assertDoesNotExist()
        composeRule.onNodeWithTag("ai_assistant_button").performClick()
        composeRule.onNodeWithTag("ai_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_problem_translation_fallback").assertIsDisplayed()
        composeRule.onNodeWithText("Google Translate refused the request (HTTP 429).").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_problem_ask").assertDoesNotExist()
        composeRule.onNodeWithTag("ai_setup_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ai_input").assertDoesNotExist()
        saveUiEvidence("ai_panel_setup")
        composeRule.onNodeWithTag("ai_close_button").performClick()
        composeRule.onNodeWithTag("ai_panel").assertDoesNotExist()
        assertTrue(requests.isEmpty())
    }

    @Test
    fun askAiAboutAProblemSendsItAndShowsTheAnswer() {
        keyStore.save(AiProvider.GEMINI, "AIza-FAKE-test-key-for-unit-tests-rstu")
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
        keyStore.save(AiProvider.GEMINI, "AIza-FAKE-test-key-for-unit-tests-rstu")
        showTopBarAndPanel(DualSubUiState())
        composeRule.onNodeWithTag("ai_assistant_problem_dot").assertDoesNotExist()
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
                        initialSection = MoreSettingsSection.AI_ASSISTANT,
                    )
                }
            }
        }
        composeRule.onNodeWithTag("ai_key_field").performScrollTo().performTextInput("sk-or-v1-abcdefghijklmnopqrstuvwxyz")
        composeRule.onNodeWithTag("ai_save_key").performClick()
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
}
