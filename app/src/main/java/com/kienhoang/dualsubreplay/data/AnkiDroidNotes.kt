package com.kienhoang.dualsubreplay.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** AnkiDroid's public content provider and the permission it asks for (no AnkiDroid library). */
internal const val ANKIDROID_AUTHORITY = "com.ichi2.anki.flashcards"
internal const val ANKIDROID_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
internal const val ANKIDROID_PACKAGE = "com.ichi2.anki"

internal const val ANKI_DECK_NAME = "DualSub Replay"
internal const val ANKI_NOTE_TYPE_NAME = "DualSub Replay"
internal val ANKI_FIELDS = listOf("Word", "Meaning", "Reading", "Sentence", "Translation", "Source")

/** Anki keeps a note's fields in one text, separated by this character. */
internal const val ANKI_FIELD_SEPARATOR = "\u001f"

internal const val ANKI_TAG = "DualSubReplay"

internal const val ANKI_QUESTION_FORMAT = "<div class=word>{{Word}}</div><div class=sentence>{{Sentence}}</div>"
internal const val ANKI_ANSWER_FORMAT =
    "{{FrontSide}}<hr id=answer><div class=reading>{{Reading}}</div><div class=meaning>{{Meaning}}</div>" +
        "<div class=translation>{{Translation}}</div><div class=source>{{Source}}</div>"
internal const val ANKI_CSS =
    ".card{font-family:sans-serif;font-size:22px;text-align:center;}.word{font-size:34px;}" +
        ".sentence,.translation{font-size:18px;margin-top:12px;}.reading{font-size:18px;}.source{font-size:14px;margin-top:12px;}"

private const val SECONDS_PER_MINUTE = 60
private const val MS_PER_SECOND = 1_000

/** [text] as Anki field HTML: special characters escaped and line breaks kept. */
internal fun ankiHtml(text: String): String =
    text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
        .replace("\r\n", "\n")
        .replace("\n", "<br>")

/** The sentence as Anki HTML with the first appearance of [word] in bold. */
internal fun ankiSentenceHtml(
    sentence: String,
    word: String,
): String {
    val at = if (word.isBlank()) -1 else sentence.indexOf(word, ignoreCase = true)
    if (at < 0) return ankiHtml(sentence)
    val end = at + word.length
    return ankiHtml(sentence.substring(0, at)) + "<b>" + ankiHtml(sentence.substring(at, end)) + "</b>" + ankiHtml(sentence.substring(end))
}

/** A link to the line's moment in the video, or empty text when the word has no video. */
internal fun ankiSourceLink(
    videoId: String?,
    startMs: Long,
): String {
    if (videoId == null || !videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) return ""
    val seconds = (startMs.coerceAtLeast(0) / MS_PER_SECOND).toInt()
    val label = String.format(Locale.ROOT, "%d:%02d", seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
    return "<a href=\"https://www.youtube.com/watch?v=$videoId&amp;t=${seconds}s\">YouTube $label</a>"
}

/** The fields of [word]'s note, in [ANKI_FIELDS] order. */
internal fun ankiNoteFields(word: SavedWord): List<String> =
    listOf(
        ankiHtml(word.word),
        ankiHtml(word.meaning),
        ankiHtml(word.reading.orEmpty()),
        ankiSentenceHtml(word.sentence, word.word),
        ankiHtml(word.translatedSentence.orEmpty()),
        ankiSourceLink(word.videoId, word.startMs),
    )

/** The words of [words] that were not sent before, by id. */
internal fun unsentAnkiWords(
    words: List<SavedWord>,
    sent: (String) -> Boolean,
): List<SavedWord> = words.distinctBy { it.id }.filterNot { sent(it.id) }

/** Why a send stopped: AnkiDroid missing or not permitted, nothing answering at AnkiConnect's address, or a refusal. */
internal enum class AnkiProblem { NOT_INSTALLED, NO_PERMISSION, NOT_RUNNING, FAILED }

/**
 * What one send did: notes added, words sent before (skipped), and any problem that stopped it,
 * with AnkiConnect's own error text when it gave one.
 */
internal data class AnkiSendResult(
    val sent: Int = 0,
    val skipped: Int = 0,
    val problem: AnkiProblem? = null,
    val detail: String? = null,
)

/**
 * Adds notes to AnkiDroid through its content provider: a "DualSub Replay" deck and note type are
 * made the first time. Which words were sent is kept on the phone, so a word is not sent twice.
 */
internal class AnkiDroidNotes(
    context: Context,
) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val sentIds = this.context.getSharedPreferences("ankidroid_sent", 0)
    private val base = Uri.parse("content://$ANKIDROID_AUTHORITY")

    fun installed(): Boolean =
        runCatching { context.packageManager.resolveContentProvider(ANKIDROID_AUTHORITY, 0) != null }.getOrDefault(false)

    fun permitted(): Boolean = context.checkSelfPermission(ANKIDROID_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun wasSent(id: String): Boolean = sentIds.getBoolean(id, false)

    /**
     * Opens AnkiDroid. On many phones its provider answers only while AnkiDroid runs, so a card it
     * refused goes in once AnkiDroid was opened and left in the background.
     */
    fun open() {
        val launch = context.packageManager.getLaunchIntentForPackage(ANKIDROID_PACKAGE) ?: return
        runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    suspend fun send(words: List<SavedWord>): AnkiSendResult =
        withContext(Dispatchers.IO) {
            if (!installed()) return@withContext AnkiSendResult(problem = AnkiProblem.NOT_INSTALLED)
            if (!permitted()) return@withContext AnkiSendResult(problem = AnkiProblem.NO_PERMISSION)
            val unsent = unsentAnkiWords(words, ::wasSent)
            val skipped = words.distinctBy { it.id }.size - unsent.size
            if (unsent.isEmpty()) return@withContext AnkiSendResult(skipped = skipped)
            try {
                val deck = deckId() ?: return@withContext AnkiSendResult(skipped = skipped, problem = AnkiProblem.FAILED)
                val noteType = noteTypeId(deck) ?: return@withContext AnkiSendResult(skipped = skipped, problem = AnkiProblem.FAILED)
                var sent = 0
                for (word in unsent) {
                    if (!addNote(noteType, deck, word)) return@withContext AnkiSendResult(sent, skipped, AnkiProblem.FAILED)
                    sentIds.edit().putBoolean(word.id, true).apply()
                    sent++
                }
                AnkiSendResult(sent, skipped)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // AnkiDroid refused (a revoked permission, a locked collection, or another version's provider).
                AnkiSendResult(skipped = skipped, problem = AnkiProblem.FAILED)
            }
        }

    private fun deckId(): Long? {
        val decks = Uri.withAppendedPath(base, "decks")
        resolver.query(decks, arrayOf("deck_id", "deck_name"), null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndex("deck_id")
            val name = cursor.getColumnIndex("deck_name")
            while (id >= 0 && name >= 0 && cursor.moveToNext()) {
                if (cursor.getString(name) == ANKI_DECK_NAME) return cursor.getLong(id)
            }
        }
        val created = resolver.insert(decks, ContentValues().apply { put("deck_name", ANKI_DECK_NAME) })
        return created?.lastPathSegment?.toLongOrNull()
    }

    private fun noteTypeId(deck: Long): Long? {
        val models = Uri.withAppendedPath(base, "models")
        val fields = ANKI_FIELDS.joinToString(ANKI_FIELD_SEPARATOR)
        resolver.query(models, arrayOf("_id", "name", "field_names"), null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndex("_id")
            val name = cursor.getColumnIndex("name")
            val names = cursor.getColumnIndex("field_names")
            while (id >= 0 && name >= 0 && names >= 0 && cursor.moveToNext()) {
                if (cursor.getString(name) == ANKI_NOTE_TYPE_NAME && cursor.getString(names) == fields) return cursor.getLong(id)
            }
        }
        val values =
            ContentValues().apply {
                put("name", ANKI_NOTE_TYPE_NAME)
                put("field_names", fields)
                put("num_cards", 1)
                put("css", ANKI_CSS)
                put("deck_id", deck)
                put("sort_field_index", 0)
            }
        val id = resolver.insert(models, values)?.lastPathSegment?.toLongOrNull() ?: return null
        // The card shows the word with its sentence; AnkiDroid's own layout (word, then meaning) stays if this is refused.
        runCatching {
            val template =
                ContentValues().apply {
                    put("question_format", ANKI_QUESTION_FORMAT)
                    put("answer_format", ANKI_ANSWER_FORMAT)
                }
            resolver.update(Uri.withAppendedPath(models, "$id/templates/0"), template, null, null)
        }
        return id
    }

    private fun addNote(
        noteType: Long,
        deck: Long,
        word: SavedWord,
    ): Boolean {
        val values =
            ContentValues().apply {
                put("mid", noteType)
                put("flds", ankiNoteFields(word).joinToString(ANKI_FIELD_SEPARATOR))
                put("tags", ANKI_TAG)
            }
        val notes =
            Uri
                .withAppendedPath(base, "notes")
                .buildUpon()
                .appendQueryParameter("deckId", deck.toString())
                .build()
        return resolver.insert(notes, values) != null
    }
}
