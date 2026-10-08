package com.kienhoang.dualsubreplay.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File

/** Directory under the app's files for saved chats; backups leave it out. */
internal const val AI_CHATS_DIRECTORY = "ai-chats"

/** Connects [AiAssistantController] to the Keystore, preferences, files and network. */
class AiAssistantViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val settingsPreferences = application.getSharedPreferences(AI_SETTINGS_PREFERENCES, 0)
    private val chatHistory = AiChatHistoryStore(File(application.filesDir, AI_CHATS_DIRECTORY))

    /** Read once, the first time a question is asked. */
    internal val guide: String by lazy {
        application.assets
            .open(AI_ASSISTANT_GUIDE_ASSET)
            .bufferedReader()
            .use { it.readText() }
    }

    internal val controller =
        AiAssistantController(
            scope = viewModelScope,
            transport = OpenAiCompatibleTransport(),
            keyStore =
                AiKeyStore(
                    SharedPreferencesSecretStorage(application.getSharedPreferences(AI_KEYS_PREFERENCES, 0)),
                    KeystoreSecretCipher(),
                ),
            settingsStorage =
                object : AiSettingsStorage {
                    override fun load() = readAiAssistantSettings(settingsPreferences)

                    override fun save(settings: AiAssistantSettings) = writeAiAssistantSettings(settingsPreferences, settings)
                },
            historyStorage =
                object : AiHistoryStorage {
                    override fun load() = chatHistory.load()

                    override fun save(chats: List<AiChat>) = chatHistory.save(chats)

                    override fun clear() = chatHistory.clear()
                },
        )
}
