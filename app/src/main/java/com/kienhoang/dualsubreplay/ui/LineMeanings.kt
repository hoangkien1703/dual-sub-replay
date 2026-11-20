package com.kienhoang.dualsubreplay.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
 * [words] splits a sentence into its words for a language; [ask] sends (system, user, urgent), hands
 * the reply to its last argument piece by piece while it arrives, and returns the whole reply, "" when
 * the service did not answer, or null when there is no AI key. Each word's meaning is stored as soon
 * as its line of the reply arrives, so a word near the start shows before the rest is written. A line
 * the learner tapped in is urgent: its request goes ahead of background ones (AiKeyPacer), so the next
 * word tapped in that line does not wait behind them. Main thread only, apart from the stored meanings.
 */
internal class LineMeanings(
    private val scope: CoroutineScope,
    private val cached: (key: String) -> String?,
    private val store: (key: String, meaning: String) -> Unit,
    private val words: (sentence: String, language: String) -> List<String>,
    private val ask: suspend (system: String, user: String, urgent: Boolean, onText: (String) -> Unit) -> String?,
) {
    private data class Line(
        val context: MeaningContext,
        val wordLanguage: String,
        val meaningLanguage: String,
        val urgent: Boolean,
    ) {
        val key = lineMeaningKey(context, wordLanguage, meaningLanguage)
    }

    private val asked =
        object : LinkedHashMap<String, Unit>(ASKED_LINES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > ASKED_LINES
        }

    @Volatile private var running: Pair<String, Job>? = null
    private var waiting: Line? = null

    /** Grows each time a meaning is stored or a request ends, so [awaitWord] looks again. */
    private val changes = MutableStateFlow(0L)

    /**
     * Asks for the words of [context]'s sentence, unless they were asked already or are being asked.
     * [urgent] when the learner tapped a word in it; false for Prepare meanings ahead.
     */
    fun prepare(
        context: MeaningContext,
        wordLanguage: String,
        meaningLanguage: String,
        urgent: Boolean = false,
    ) {
        val line = Line(context, wordLanguage, meaningLanguage, urgent)
        if (line.key in asked || running?.first == line.key) return
        if (running == null) start(line) else waiting = line
    }

    /**
     * [word]'s meaning from the request about [context]'s sentence, if one is running: waits until its
     * line of the reply arrives, the request ends, or [maxMs] pass, whichever comes first. Null at once
     * when no request about the sentence runs. A tap waits at most [maxMs] and then asks on its own: the
     * line's request may still wait for its turn behind the key's pacer, or be slow (lion737, Discussion
     * #81, 2026-11-20: about 22 s while Prepare meanings ahead was on).
     */
    suspend fun awaitWord(
        word: String,
        context: MeaningContext,
        wordLanguage: String,
        meaningLanguage: String,
        maxMs: Long,
    ): String? {
        val key = lineMeaningKey(context, wordLanguage, meaningLanguage)
        val job = running?.takeIf { it.first == key }?.second ?: return null
        val wordKey = contextMeaningKey(word, context, wordLanguage, meaningLanguage)
        withTimeoutOrNull(maxMs) { changes.first { cached(wordKey) != null || job.isCompleted } }
        return cached(wordKey)
    }

    private fun start(line: Line) {
        val job =
            scope.launch {
                try {
                    askAbout(line)
                } finally {
                    running = null
                    changes.update { it + 1 }
                    waiting?.let {
                        waiting = null
                        prepare(it.context, it.wordLanguage, it.meaningLanguage, it.urgent)
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
        val save = { word: String, meaning: String ->
            store(contextMeaningKey(word, line.context, line.wordLanguage, line.meaningLanguage), meaning)
            changes.update { it + 1 }
        }
        val stream = LineMeaningStream(wanted, save)
        val meanings = parseLineMeanings(ask(system, user, line.urgent, stream::add) ?: return, wanted)
        // Where the service could not stream, or the last line had no line break.
        meanings.forEach { (word, meaning) -> if (word !in stream.saved) save(word, meaning) }
        // A failed request is not remembered, so a later tap or play of the line asks again.
        if (meanings.isNotEmpty()) asked[line.key] = Unit
    }
}

/**
 * Reads a line-meanings reply while it arrives: each finished numbered line goes to [save] at once.
 * Pieces may come from another thread than the main one.
 */
internal class LineMeaningStream(
    private val words: List<String>,
    private val save: (word: String, meaning: String) -> Unit,
) {
    private val pending = StringBuilder()

    /** The words already handed to [save]. */
    val saved: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    @Synchronized
    fun add(piece: String) {
        pending.append(piece)
        while (true) {
            val end = pending.indexOf("\n")
            if (end < 0) return
            val line = pending.substring(0, end)
            pending.delete(0, end + 1)
            parseLineMeanings(line, words).forEach { (word, meaning) -> if (saved.add(word)) save(word, meaning) }
        }
    }
}
