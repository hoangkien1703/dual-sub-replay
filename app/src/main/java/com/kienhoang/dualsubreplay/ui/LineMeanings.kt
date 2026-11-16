package com.kienhoang.dualsubreplay.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** How many lines whose words were already asked about are remembered, so they are not asked again. */
private const val ASKED_LINES = 200

/**
 * The meanings of every word of a line, asked for in one request (lion737, Discussion #81,
 * 2026-11-15, spec docs/specs/2026-11-16-instant-word-card-read-aloud-ipa-sleep-timer.md): after
 * the first tap in a line, and while a line plays when Prepare meanings ahead is on. The answers go
 * through [cached] and [store], under [contextMeaningKey], where a later tap finds them at once.
 *
 * At most one request runs at a time. While it runs, only the newest line waits for its turn, so a
 * video that keeps playing never builds a queue of requests behind the key's pacer (AiKeyPacer
 * still spaces every request; AiRequestBudgetTest covers the playing case).
 *
 * [words] splits a sentence into its words for a language; [ask] sends (system, user) and returns
 * the reply, "" when the service did not answer, or null when there is no AI key. Main thread only.
 */
internal class LineMeanings(
    private val scope: CoroutineScope,
    private val cached: (key: String) -> String?,
    private val store: (key: String, meaning: String) -> Unit,
    private val words: (sentence: String, language: String) -> List<String>,
    private val ask: suspend (system: String, user: String) -> String?,
) {
    private data class Line(
        val context: MeaningContext,
        val wordLanguage: String,
        val meaningLanguage: String,
    ) {
        val key = lineMeaningKey(context, wordLanguage, meaningLanguage)
    }

    private val asked =
        object : LinkedHashMap<String, Unit>(ASKED_LINES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > ASKED_LINES
        }
    private var running: Pair<String, Job>? = null
    private var waiting: Line? = null

    /** Asks for the words of [context]'s sentence, unless they were asked already or are being asked. */
    fun prepare(
        context: MeaningContext,
        wordLanguage: String,
        meaningLanguage: String,
    ) {
        val line = Line(context, wordLanguage, meaningLanguage)
        if (line.key in asked || running?.first == line.key) return
        if (running == null) start(line) else waiting = line
    }

    /** Waits for the request about [context]'s sentence if one is running; returns at once otherwise. */
    suspend fun awaitRunning(
        context: MeaningContext,
        wordLanguage: String,
        meaningLanguage: String,
    ) {
        val key = lineMeaningKey(context, wordLanguage, meaningLanguage)
        running?.takeIf { it.first == key }?.second?.join()
    }

    private fun start(line: Line) {
        val job =
            scope.launch {
                try {
                    askAbout(line)
                } finally {
                    running = null
                    waiting?.let {
                        waiting = null
                        prepare(it.context, it.wordLanguage, it.meaningLanguage)
                    }
                }
            }
        if (!job.isCompleted) running = line.key to job
    }

    private suspend fun askAbout(line: Line) {
        val wanted =
            lineMeaningWords(words(line.context.sentence, line.wordLanguage)).filter {
                cached(contextMeaningKey(it, line.context, line.wordLanguage, line.meaningLanguage)) == null
            }
        if (wanted.isEmpty()) {
            asked[line.key] = Unit
            return
        }
        val (system, user) = lineMeaningsRequest(wanted, line.context, line.wordLanguage, line.meaningLanguage)
        val meanings = parseLineMeanings(ask(system, user) ?: return, wanted)
        meanings.forEach { (word, meaning) ->
            store(contextMeaningKey(word, line.context, line.wordLanguage, line.meaningLanguage), meaning)
        }
        // A failed request is not remembered, so a later tap or play of the line asks again.
        if (meanings.isNotEmpty()) asked[line.key] = Unit
    }
}
