package com.kienhoang.dualsubreplay.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Known words from Anki (owner pick 2026-11-26, from the competitor research: asbplayer and Fushi
// read the words a learner already studies in Anki). The words of the cards in one AnkiDroid deck
// are added to the known words of one language, so a new user's colours and known share are right
// from the first video. It reads AnkiDroid's content provider only, like Send to; nothing is written.

/** A card reviewed with an interval of at least this many days counts as well known ("mature" in Anki). */
internal const val ANKI_MATURE_DAYS = 21

/** Pieces of one field longer than this are sentences or notes, never a word. */
private const val MAX_ANKI_PIECE_LENGTH = 40

/** One AnkiDroid deck, as its provider lists it. */
internal data class AnkiDeck(
    val id: Long,
    val name: String,
)

/** Which cards of a deck count: only well-known ones (reviewed [ANKI_MATURE_DAYS]+ days), or every card studied at least once. */
internal enum class AnkiCardsToCount { WELL_KNOWN, STUDIED }

/** Why reading Anki stopped, or null when it worked. */
internal enum class AnkiReadProblem { NOT_INSTALLED, NO_PERMISSION, FAILED }

/** What one read did: the word keys found, and the notes that gave none (sentences, pictures, empty fields). */
internal data class AnkiKnownWordsResult(
    val keys: Set<String> = emptySet(),
    val notes: Int = 0,
    val problem: AnkiReadProblem? = null,
)

/**
 * The Anki search for [deck]'s cards that count: the deck with its subdecks, quoted so spaces,
 * quotes and Anki's wildcards (`*`, `_`) in its name are read as written.
 */
internal fun ankiDeckSearch(
    deck: String,
    cards: AnkiCardsToCount,
): String {
    val escaped =
        deck
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("*", "\\*")
            .replace("_", "\\_")
    val which = if (cards == AnkiCardsToCount.WELL_KNOWN) "prop:ivl>=$ANKI_MATURE_DAYS" else "-is:new"
    return "\"deck:$escaped\" $which"
}

private val RUBY_READING = Regex("<rt>.*?</rt>|<rp>.*?</rp>", RegexOption.IGNORE_CASE)
private val HTML_TAG = Regex("<[^>]*>")
private val SOUND = Regex("\\[sound:[^\\]]*]")

/** Anki's furigana: a reading in brackets after the word (`食[た]べる`), sometimes with a space before the word. */
private val BRACKET_READING = Regex("\\[[^\\]]*]")
private val PIECE_SEPARATORS = Regex("[,;/|、，；\\n]")

/** A field's text without HTML, sounds, entities or furigana readings, each piece (`go, went`) on its own. */
internal fun ankiFieldPieces(field: String): List<String> {
    val text =
        field
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(RUBY_READING, "")
            .replace(HTML_TAG, "")
            .replace(SOUND, "")
            .replace(BRACKET_READING, "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
    return text
        .split(PIECE_SEPARATORS)
        .map { it.trim().trim('.', '!', '?', '"', '\'', '(', ')', '「', '」') }
        .filter { it.isNotEmpty() && it.length <= MAX_ANKI_PIECE_LENGTH }
}

/**
 * The known-word keys of one note's word field: each piece that is a single word, in its dictionary
 * form where [keysOf] gives one (`went` → `go`). A piece of several words is a phrase or sentence
 * and is left out, because a known word is always one word of a subtitle line.
 */
internal fun ankiFieldKeys(
    field: String,
    keysOf: (String) -> List<String>,
): Set<String> =
    ankiFieldPieces(field)
        .mapNotNull { piece -> keysOf(piece).takeIf { it.size == 1 }?.first() }
        .toSet()

/** The word keys of [text] in [language], as known words count them in subtitle lines. */
internal fun subtitleWordKeys(
    text: String,
    language: String,
): List<String> = LanguageAwareTokenizer.tokenize(text, language).mapNotNull(::knownWordKey)

/** Reads decks and their cards' word field from AnkiDroid's content provider (never its library). */
internal class AnkiKnownWords(
    context: Context,
) {
    private val context = context.applicationContext
    private val notes = AnkiDroidNotes(this.context)
    private val base = Uri.parse("content://$ANKIDROID_AUTHORITY")

    fun installed(): Boolean = notes.installed()

    fun permitted(): Boolean = notes.permitted()

    /** AnkiDroid's decks by name, or null when they could not be read (see [installed] and [permitted]). */
    suspend fun decks(): List<AnkiDeck>? =
        withContext(Dispatchers.IO) {
            if (!installed() || !permitted()) return@withContext null
            try {
                val found = ArrayList<AnkiDeck>()
                context.contentResolver.query(Uri.withAppendedPath(base, "decks"), arrayOf("deck_id", "deck_name"), null, null, null)?.use {
                    val id = it.getColumnIndex("deck_id")
                    val name = it.getColumnIndex("deck_name")
                    while (id >= 0 && name >= 0 && it.moveToNext()) {
                        it.getString(name)?.takeIf(String::isNotBlank)?.let { deck -> found += AnkiDeck(it.getLong(id), deck) }
                    }
                }
                found.sortedBy { it.name.lowercase() }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
        }

    /**
     * The known-word keys of [deck]'s cards that count, from each note's sort field (the field Anki
     * lists the note by, the word in word decks), read as words of [language].
     */
    suspend fun words(
        deck: String,
        cards: AnkiCardsToCount,
        language: String,
    ): AnkiKnownWordsResult =
        withContext(Dispatchers.IO) {
            if (!installed()) return@withContext AnkiKnownWordsResult(problem = AnkiReadProblem.NOT_INSTALLED)
            if (!permitted()) return@withContext AnkiKnownWordsResult(problem = AnkiReadProblem.NO_PERMISSION)
            try {
                val keys = LinkedHashSet<String>()
                var count = 0
                val search = ankiDeckSearch(deck, cards)
                context.contentResolver.query(Uri.withAppendedPath(base, "notes"), arrayOf("sfld"), search, null, null)?.use {
                    val field = it.getColumnIndex("sfld")
                    while (field >= 0 && it.moveToNext()) {
                        count++
                        keys += ankiFieldKeys(it.getString(field).orEmpty()) { piece -> subtitleWordKeys(piece, language) }
                    }
                }
                AnkiKnownWordsResult(keys, count)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                AnkiKnownWordsResult(problem = AnkiReadProblem.FAILED)
            }
        }
}
