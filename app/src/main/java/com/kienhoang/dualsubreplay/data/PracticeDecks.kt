package com.kienhoang.dualsubreplay.data

import java.util.Locale

/** Long enough for "Egyptian Arabic, food words", short enough for a chip. */
internal const val MAX_DECK_NAME_LENGTH = 40

/**
 * A deck in Practice (owner decision 2026-11-02, issue #184): one the learner named, or the
 * automatic deck of a word language that holds every word not put in a named deck.
 */
internal data class PracticeDeck(
    val name: String? = null,
    val language: String? = null,
) {
    val isNamed: Boolean get() = name != null
}

internal data class DeckSummary(
    val deck: PracticeDeck,
    val words: Int,
    val due: Int,
)

internal fun deckOf(word: SavedWord): PracticeDeck =
    word.deck?.let { PracticeDeck(name = it) } ?: PracticeDeck(language = word.wordLanguage)

/** A deck name with spaces tidied and its length capped, or null when nothing is left. */
internal fun cleanDeckName(name: String): String? =
    name
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_DECK_NAME_LENGTH)
        .trim()
        .ifBlank { null }

/** The named decks in use, A to Z. */
internal fun namedDecks(words: List<SavedWord>): List<String> =
    words
        .mapNotNull { it.deck }
        .distinct()
        .sortedBy { it.lowercase(Locale.ROOT) }

/** A typed name, reusing an existing deck's spelling when only the letter case differs. */
internal fun resolveDeckName(
    typed: String,
    existing: List<String>,
): String? {
    val clean = cleanDeckName(typed) ?: return null
    return existing.firstOrNull { it.equals(clean, ignoreCase = true) } ?: clean
}

/** Every deck with its word and due counts, the deck of the newest saved word first ([words] is newest first). */
internal fun deckSummaries(
    words: List<SavedWord>,
    now: Long,
): List<DeckSummary> =
    words.groupBy(::deckOf).map { (deck, list) ->
        DeckSummary(deck, list.size, list.count { it.dueAt <= now })
    }
