package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.assistant.AiAppSnapshot
import com.kienhoang.dualsubreplay.assistant.AiSetting
import com.kienhoang.dualsubreplay.translation.TranslationEngine
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import java.util.Locale

/** English names for the model; the assistant answers in [interfaceLocale]'s language. */
internal fun aiAppSnapshot(
    state: DualSubUiState,
    playerMode: PlayerExperienceMode,
    interfaceLocale: Locale,
    appVersion: String,
    stored: AiStoredSettings = AiStoredSettings(),
    speed: Float = 1f,
): AiAppSnapshot =
    AiAppSnapshot(
        appVersion = appVersion,
        replyLanguage = interfaceLocale.getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" },
        sourceLanguage =
            (state.resolvedSourceLanguage ?: state.sourcePreference.takeUnless { it == "auto" })
                ?.let(TranslationLanguages::displayName)
                ?: "Auto (follows the video)",
        targetLanguage = TranslationLanguages.displayName(state.targetLanguage),
        translation = aiTranslationStatus(state),
        settings = aiSettingsSummary(state, playerMode, stored, speed),
    )

internal fun aiTranslationStatus(state: DualSubUiState): String =
    when {
        state.translationError != null -> "stopped, original captions only (${state.translationError})"
        state.onDeviceFallback && state.youTubeTranslationFallback ->
            "YouTube's own translated captions (lines YouTube left out on the device), because Google Translate failed; " +
                "Google is checked again every 2 minutes"
        state.onDeviceFallback -> "on the device, because Google Translate failed; Google is checked again every 2 minutes"
        state.translationEngine == TranslationEngine.GOOGLE_WEB -> "Google Translate (online)"
        else -> "on the device"
    }

private fun onOff(enabled: Boolean) = if (enabled) "on" else "off"

/** Every setting the assistant may change, by the key and value its change_setting tool uses. */
internal fun aiSettingsSummary(
    state: DualSubUiState,
    playerMode: PlayerExperienceMode,
    stored: AiStoredSettings,
    speed: Float,
): List<Pair<String, String>> =
    AiSetting.entries
        .filter { it != AiSetting.PLAYBACK_SPEED || state.activeVideoId != null }
        .map { it.key to aiSettingCurrentValue(state, playerMode, speed, stored, it) } +
        ("google_translate (change_translation)" to onOff(state.translationEngine == TranslationEngine.GOOGLE_WEB))
