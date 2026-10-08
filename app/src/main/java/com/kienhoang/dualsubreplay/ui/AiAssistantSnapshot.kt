package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.assistant.AiAppSnapshot
import com.kienhoang.dualsubreplay.translation.TranslationEngine
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import java.util.Locale

/** English names for the model; the assistant answers in [interfaceLocale]'s language. */
internal fun aiAppSnapshot(
    state: DualSubUiState,
    playerMode: PlayerExperienceMode,
    interfaceLocale: Locale,
    appVersion: String,
): AiAppSnapshot =
    AiAppSnapshot(
        appVersion = appVersion,
        build = if (state.onlineTranslationAvailable) "GitHub" else "F-Droid",
        replyLanguage = interfaceLocale.getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" },
        sourceLanguage =
            (state.resolvedSourceLanguage ?: state.sourcePreference.takeUnless { it == "auto" })
                ?.let(TranslationLanguages::displayName)
                ?: "Auto (follows the video)",
        targetLanguage = TranslationLanguages.displayName(state.targetLanguage),
        translation = aiTranslationStatus(state),
        settings = aiSettingsSummary(state, playerMode),
    )

internal fun aiTranslationStatus(state: DualSubUiState): String =
    when {
        state.translationError != null -> "stopped, original captions only (${state.translationError})"
        state.onDeviceFallback -> "on the device, because Google Translate failed; Google is checked again every 2 minutes"
        state.translationEngine == TranslationEngine.GOOGLE_WEB -> "Google Translate (online)"
        else -> "on the device"
    }

private fun onOff(enabled: Boolean) = if (enabled) "on" else "off"

private fun visibility(value: CaptionVisibility) =
    when (value) {
        CaptionVisibility.ALWAYS -> "always"
        CaptionVisibility.PAUSED -> "only when paused"
        CaptionVisibility.NEVER -> "never"
    }

internal fun aiSettingsSummary(
    state: DualSubUiState,
    playerMode: PlayerExperienceMode,
): List<Pair<String, String>> =
    listOf(
        "Text size" to "${(state.fontScale * 100).toInt()}%",
        "Original captions" to visibility(state.originalVisibility),
        "Translated captions" to visibility(state.translatedVisibility),
        "Highlight spoken words" to onOff(state.wordHighlightEnabled),
        "Default view" to if (playerMode == PlayerExperienceMode.TRANSCRIPT_PANEL) "Transcript panel" else "Scroll-friendly overlay",
        "Caption format" to if (state.captionFormat == CaptionFormat.SHORT_PHRASES) "Short paired phrases" else "Whole sentence",
        "Landscape split view" to onOff(state.landscapeSplitEnabled),
        "Custom subtitle colors" to onOff(state.customColorsEnabled),
        "Pronounce tapped words" to onOff(state.autoPronounce),
        "Word learning mode" to onOff(state.wordLearningEnabled),
        "Tap word for definition" to onOff(state.tapToLearnEnabled),
        "Lock overlay to video player" to onOff(state.lockOverlayToVideo),
        "Google Translate (online)" to onOff(state.translationEngine == TranslationEngine.GOOGLE_WEB),
        "Natural subtitle flow" to onOff(state.naturalSubtitlesEnabled),
        "Preload translation models" to onOff(state.preloadModelsEnabled),
    )
