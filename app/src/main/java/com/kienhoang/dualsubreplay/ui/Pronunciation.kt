package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.AnalyzedToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

internal data class PronunciationVoice(
    val name: String,
    val locale: Locale,
    val network: Boolean = false,
    val installed: Boolean = true,
    /** The engine's own rating (Android's Voice.QUALITY_*, higher sounds more natural). */
    val quality: Int = 300,
)

internal interface PronunciationEngine {
    suspend fun initialize(): Boolean

    fun voices(): List<PronunciationVoice>

    fun selectVoice(voice: PronunciationVoice): Boolean

    fun selectLanguage(locale: Locale): Boolean

    suspend fun speak(word: String): Boolean

    /** Writes [word]'s speech to [file] instead of playing it. Engines that cannot return false. */
    suspend fun synthesize(
        word: String,
        file: File,
    ): Boolean = false

    fun close()
}

internal enum class PronunciationResult {
    SPOKEN,
    NO_VOICE,
    UNAVAILABLE,
    PLAYBACK_FAILED,
    INVALID_LANGUAGE,
}

/**
 * What to hand the speech engine for [text], which starts at index [start] of its subtitle line:
 * each of [words] that has a kana reading is replaced by it. Speech engines guess the reading of
 * kanji on their own (今日 as きょう or こんにち), while the reading comes from the whole line.
 */
internal fun spokenText(
    text: String,
    start: Int,
    words: List<AnalyzedToken>,
): String {
    val spoken = StringBuilder()
    var cursor = 0
    for (word in words) {
        val reading = kanaReading(word.reading) ?: continue
        val at = word.startIndex - start
        // Offsets that do not match the text: speak it as written rather than garble it.
        if (at < cursor || !text.startsWith(word.text, at)) return text
        spoken.append(text, cursor, at).append(reading)
        cursor = at + word.text.length
    }
    return spoken.append(text, cursor, text.length).toString()
}

/** A saved [word] is spoken from its [reading] when that reading is kana. */
internal fun spokenWord(
    word: String,
    reading: String?,
): String = kanaReading(reading) ?: word

/**
 * [reading] when it is only kana, otherwise null. Saved words can carry other readings, such as
 * romanization from the assistant, which must not be read aloud as the word.
 */
private fun kanaReading(reading: String?): String? =
    reading?.trim()?.takeIf { text -> text.isNotEmpty() && text.all { it.code in 0x3040..0x30FF || it == ' ' } }

internal fun pronunciationLocale(language: String): Locale? {
    val locale =
        Locale.forLanguageTag(language.trim().replace('_', '-')).takeIf {
            it.language.isNotBlank() && it.language !in setOf("und", "auto")
        } ?: return null
    if (locale.language != "zh" || locale.country.isNotBlank()) return locale
    // Speech engines list Chinese by country, not script: zh-Hans is read as Mandarin from China, zh-Hant from Taiwan.
    return if (locale.script == "Hant") Locale.TAIWAN else Locale.CHINA
}

/** A voice's language, with Mandarin's own code (cmn), which some engines use, read as Chinese. */
private fun speechLanguage(locale: Locale): String = if (locale.language == "cmn") "zh" else locale.language

internal fun pronunciationVoices(
    voices: List<PronunciationVoice>,
    locale: Locale,
): List<PronunciationVoice> {
    val matching =
        voices
            .filter { it.installed && speechLanguage(it.locale) == speechLanguage(locale) }
            .filter { locale.script.isBlank() || it.locale.script.isBlank() || it.locale.script == locale.script }
            .sortedWith(
                compareBy<PronunciationVoice> { it.network }
                    .thenBy { it.locale.country != locale.country }
                    .thenByDescending { it.quality }
                    .thenBy { it.locale != locale }
                    .thenBy { it.name },
            )
    // Keep a network fallback reachable even when an engine lists many offline voices.
    return (matching.take(1) + matching.filter { it.network }.take(1) + matching.drop(1))
        .distinctBy { it.name }
        .take(3)
}

/**
 * Try the user's default engine first, then other installed engines, without changing system settings.
 * [render] speaks the word by default; pass a synthesis step to record it instead.
 */
internal suspend fun pronounceWord(
    word: String,
    language: String,
    engineNames: List<String?>,
    render: suspend PronunciationEngine.() -> Boolean = { speak(word) },
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
            var selected = false
            for (voice in pronunciationVoices(active.voices(), locale)) {
                if (!active.selectVoice(voice)) continue
                selected = true
                playbackFailed = true
                if (withTimeoutOrNull(10_000) { active.render() } == true) return PronunciationResult.SPOKEN
            }
            // Some engines implement setLanguage but list no voice for it, or none that can be selected.
            if (selected || !active.selectLanguage(locale)) continue
            playbackFailed = true
            if (withTimeoutOrNull(10_000) { active.render() } == true) return PronunciationResult.SPOKEN
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
