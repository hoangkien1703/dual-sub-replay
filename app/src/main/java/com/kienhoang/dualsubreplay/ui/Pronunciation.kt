package com.kienhoang.dualsubreplay.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

internal data class PronunciationVoice(
    val name: String,
    val locale: Locale,
    val network: Boolean = false,
    val installed: Boolean = true,
)

internal interface PronunciationEngine {
    suspend fun initialize(): Boolean

    fun voices(): List<PronunciationVoice>

    fun selectVoice(voice: PronunciationVoice): Boolean

    fun selectLanguage(locale: Locale): Boolean

    suspend fun speak(word: String): Boolean

    fun close()
}

internal enum class PronunciationResult {
    SPOKEN,
    NO_VOICE,
    UNAVAILABLE,
    PLAYBACK_FAILED,
    INVALID_LANGUAGE,
}

internal fun pronunciationLocale(language: String): Locale? =
    Locale.forLanguageTag(language.trim().replace('_', '-')).takeIf {
        it.language.isNotBlank() && it.language !in setOf("und", "auto")
    }

internal fun pronunciationVoices(
    voices: List<PronunciationVoice>,
    locale: Locale,
): List<PronunciationVoice> =
    voices
        .filter { it.installed && it.locale.language == locale.language }
        .filter { locale.script.isBlank() || it.locale.script.isBlank() || it.locale.script == locale.script }
        .sortedWith(
            compareBy<PronunciationVoice> { it.network }
                .thenBy { it.locale != locale }
                .thenBy { it.locale.country != locale.country }
                .thenBy { it.name },
        ).distinctBy { Triple(it.locale, it.network, it.installed) }
        .take(3)

/** Try the user's default engine first, then other installed engines, without changing system settings. */
internal suspend fun pronounceWord(
    word: String,
    language: String,
    engineNames: List<String?>,
    createEngine: (String?) -> PronunciationEngine,
): PronunciationResult {
    val locale = pronunciationLocale(language) ?: return PronunciationResult.INVALID_LANGUAGE
    var initialized = false
    var playbackFailed = false
    for (name in engineNames.distinct()) {
        var engine: PronunciationEngine? = null
        try {
            val active = createEngine(name).also { engine = it }
            if (withTimeoutOrNull(4_000) { active.initialize() } != true) continue
            initialized = true
            val voices = pronunciationVoices(active.voices(), locale)
            if (voices.isEmpty()) {
                // Some older engines implement setLanguage but do not expose a voice catalog.
                if (!active.selectLanguage(locale)) continue
                playbackFailed = true
                if (withTimeoutOrNull(10_000) { active.speak(word) } == true) return PronunciationResult.SPOKEN
            } else {
                for (voice in voices) {
                    if (!active.selectVoice(voice)) continue
                    playbackFailed = true
                    if (withTimeoutOrNull(10_000) { active.speak(word) } == true) return PronunciationResult.SPOKEN
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // A broken vendor engine must not prevent trying the next installed engine.
            playbackFailed = true
        } finally {
            runCatching { engine?.close() }
        }
    }
    return when {
        playbackFailed -> PronunciationResult.PLAYBACK_FAILED
        initialized -> PronunciationResult.NO_VOICE
        else -> PronunciationResult.UNAVAILABLE
    }
}
