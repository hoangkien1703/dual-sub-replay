package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.translation.AiSubtitleTranslator
import com.kienhoang.dualsubreplay.translation.AiTranslationModel
import com.kienhoang.dualsubreplay.translation.aiSentences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * The AI side of one video's online translation (Settings → Translation → Translate subtitles with
 * an AI choice): one request at a time from the playing window, which [ahead] fills with the
 * minutes after it. Rows wait only while the running request may still bring their sentence.
 */
internal class AiWindowTranslation(
    private val scope: CoroutineScope,
    private val translator: AiSubtitleTranslator,
    private val model: AiTranslationModel,
    languages: Pair<String, String>,
    private val ahead: suspend () -> List<String>,
) {
    private val source = languages.first
    private val target = languages.second
    private var job: Job? = null

    suspend fun cached(text: String): String? = translator.cachedTranslation(model, source, target, text)

    /** Sends [texts] unless a request is already running; the next row step sends what is still missing. */
    fun ask(texts: List<String>) {
        if (job?.isActive != true) job = scope.launch { translateQuietly(texts) }
    }

    /** [text]'s translation, waiting while the running request may still bring it; null when it ends without it. */
    suspend fun await(text: String): String? {
        job?.let { running -> awaitOrEnd(running) { translator.shownTranslation(model, source, target, text) != null } }
        return cached(text)
    }

    /** AI only: waits for the window's first sentence, asking again once if the running request was for another place. */
    suspend fun first(texts: List<String>) {
        val first = aiSentences(texts).firstOrNull()?.text ?: return
        repeat(2) {
            if (cached(first) != null) return
            ask(texts)
            await(first)
        }
    }

    /** Sends the window's sentences; a slow, refused or unreadable answer leaves the rows to Google. */
    private suspend fun translateQuietly(texts: List<String>) {
        try {
            translator.translateAll(model, source, target, texts, ahead)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Google translates what the AI did not.
        }
    }

    /** Waits until [arrived] (checked whenever the AI translator has news) or until [running] ends. */
    private suspend fun awaitOrEnd(
        running: Job,
        arrived: () -> Boolean,
    ) = coroutineScope {
        val watch = launch { translator.updates.first { arrived() } }
        select {
            running.onJoin {}
            watch.onJoin {}
        }
        watch.cancel()
    }
}
