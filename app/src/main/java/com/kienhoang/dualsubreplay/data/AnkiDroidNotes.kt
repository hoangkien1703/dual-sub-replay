package com.kienhoang.dualsubreplay.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** AnkiDroid's public content provider and the permission it asks for (no AnkiDroid library). */
internal const val ANKIDROID_AUTHORITY = "com.ichi2.anki.flashcards"
internal const val ANKIDROID_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
internal const val ANKIDROID_PACKAGE = "com.ichi2.anki"

internal const val ANKI_DECK_NAME = "DualSub Replay"

/**
 * The note type with the two sound fields (owner pick 2026-11-09). AnkiDroid's API can't add fields to the
 * older "DualSub Replay" type people already have, so the new fields come with a new name; old cards keep theirs.
 */
internal const val ANKI_NOTE_TYPE_NAME = "DualSub Replay audio"
internal const val ANKI_WORD_AUDIO_FIELD = "Word audio"
internal const val ANKI_SENTENCE_AUDIO_FIELD = "Sentence audio"
internal val ANKI_FIELDS =
    listOf("Word", "Meaning", "Reading", "Sentence", "Translation", "Source", ANKI_WORD_AUDIO_FIELD, ANKI_SENTENCE_AUDIO_FIELD)

/** Anki keeps a note's fields in one text, separated by this character. */
internal const val ANKI_FIELD_SEPARATOR = "\u001f"

internal const val ANKI_TAG = "DualSubReplay"

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

/**
 * The Translation field: the translation, then the third subtitle line under it. The third line shares
 * this field so the "DualSub Replay" note type keeps its fields: AnkiDroid's API cannot add a field to a
 * note type people already have.
 */
internal fun ankiTranslationHtml(word: SavedWord): String =
    listOfNotNull(word.translatedSentence, word.thirdSentence)
        .filter { it.isNotBlank() }
        .joinToString("<br>", transform = ::ankiHtml)

/**
 * A card's media as Anki field text: the sounds (`[sound:name]`) and the picture (`<img>`), each
 * empty when missing.
 */
internal data class CardMedia(
    val word: String = "",
    val sentence: String = "",
    val picture: String = "",
)

/** The field text that plays the media file [name] in Anki; empty for a name Anki couldn't play. */
internal fun ankiSound(name: String?): String {
    val clean = name?.trim()?.removePrefix("/").orEmpty()
    if (clean.isEmpty() || clean.any { it == '[' || it == ']' || it == '/' || it == '\\' || it.isISOControl() }) return ""
    return "[sound:$clean]"
}

/**
 * The fields of [word]'s note, in [ANKI_FIELDS] order. The picture goes in Source, above the video
 * link: AnkiDroid can't add a field to the note type people already have.
 */
internal fun ankiNoteFields(
    word: SavedWord,
    media: CardMedia = CardMedia(),
): List<String> =
    listOf(
        ankiHtml(word.word),
        ankiHtml(word.meaning),
        ankiHtml(word.reading.orEmpty()),
        ankiSentenceHtml(word.sentence, word.word),
        ankiTranslationHtml(word),
        listOf(media.picture, ankiSourceLink(word.videoId, word.startMs)).filter { it.isNotEmpty() }.joinToString("<br>"),
        media.word,
        media.sentence,
    )

/** [fields] (a note's fields in [ANKI_FIELDS] order) with [field] set to [value]; null when the note has other fields. */
internal fun ankiFieldsWith(
    fields: List<String>,
    field: String,
    value: String,
): List<String>? {
    val index = ANKI_FIELDS.indexOf(field)
    if (index < 0 || fields.size != ANKI_FIELDS.size) return null
    return fields.toMutableList().also { it[index] = value }
}

/** The media files of one card: the word's voice, the line's clip and its picture, each null when there is none. */
internal data class AnkiCardFiles(
    val word: File? = null,
    val sentence: File? = null,
    val picture: File? = null,
)

/**
 * Where cards get their media (owner picks 2026-11-09 and 2026-11-10): [files] makes or finds the
 * ones [options] asks for, and [awaitSentence] remembers a card sent before its line's clip was
 * ready, so the clip is added later.
 */
internal interface AnkiCardMedia {
    suspend fun files(
        word: SavedWord,
        options: CardOptions,
    ): AnkiCardFiles

    fun awaitSentence(item: PendingCardAudio)
}

/** The authority of the provider that lends AnkiDroid the media files (read-only, for one copy). */
internal fun cardAudioAuthority(context: Context): String = "${context.packageName}.cardaudio"

/** The words of [words] that were not sent before, by id. */
internal fun unsentAnkiWords(
    words: List<SavedWord>,
    again: Boolean = false,
    sent: (String) -> Boolean,
): List<SavedWord> = words.distinctBy { it.id }.filterNot { !again && sent(it.id) }

/** Why a send stopped: AnkiDroid missing or not permitted, nothing answering at AnkiConnect's address, or a refusal. */
internal enum class AnkiProblem { NOT_INSTALLED, NO_PERMISSION, NOT_RUNNING, FAILED }

/**
 * What one send did: notes added, words sent before (skipped), and any problem that stopped it,
 * with AnkiConnect's own error text when it gave one. [waiting] cards went without their
 * sentence's sound, which is added once the line has played.
 */
internal data class AnkiSendResult(
    val sent: Int = 0,
    val skipped: Int = 0,
    val problem: AnkiProblem? = null,
    val detail: String? = null,
    val waiting: Int = 0,
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

    /** The card front last set on each note type (by id), see [cardFrontNeedsUpdate]. */
    private val cardFronts = PreferenceCardFronts(this.context, "ankidroid_card_front")
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

    suspend fun send(
        words: List<SavedWord>,
        media: AnkiCardMedia? = null,
        options: CardOptions = CardOptions(),
        again: Boolean = false,
    ): AnkiSendResult =
        withContext(Dispatchers.IO) {
            if (!installed()) return@withContext AnkiSendResult(problem = AnkiProblem.NOT_INSTALLED)
            if (!permitted()) return@withContext AnkiSendResult(problem = AnkiProblem.NO_PERMISSION)
            val unsent = unsentAnkiWords(words, again, ::wasSent)
            val skipped = words.distinctBy { it.id }.size - unsent.size
            if (unsent.isEmpty()) return@withContext AnkiSendResult(skipped = skipped)
            var sent = 0
            var waiting = 0
            try {
                val deck = deckId() ?: return@withContext AnkiSendResult(skipped = skipped, problem = AnkiProblem.FAILED)
                val noteType =
                    noteTypeId(deck, options.front, options.sides)
                        ?: return@withContext AnkiSendResult(skipped = skipped, problem = AnkiProblem.FAILED)
                for (word in unsent) {
                    val added =
                        addNote(noteType, deck, word, media, options)
                            ?: return@withContext AnkiSendResult(sent, skipped, AnkiProblem.FAILED, waiting = waiting)
                    sentIds.edit().putBoolean(word.id, true).apply()
                    sent++
                    if (added.waiting) waiting++
                }
                AnkiSendResult(sent, skipped, waiting = waiting)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // AnkiDroid refused (a revoked permission, a locked collection, or another version's provider).
                AnkiSendResult(sent, skipped, AnkiProblem.FAILED, waiting = waiting)
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

    private fun noteTypeId(
        deck: Long,
        front: CardFront,
        sides: CardSides,
    ): Long? {
        val models = Uri.withAppendedPath(base, "models")
        val fields = ANKI_FIELDS.joinToString(ANKI_FIELD_SEPARATOR)
        resolver.query(models, arrayOf("_id", "name", "field_names"), null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndex("_id")
            val name = cursor.getColumnIndex("name")
            val names = cursor.getColumnIndex("field_names")
            while (id >= 0 && name >= 0 && names >= 0 && cursor.moveToNext()) {
                if (cursor.getString(name) == ANKI_NOTE_TYPE_NAME && cursor.getString(names) == fields) {
                    val found = cursor.getLong(id)
                    if (cardFrontNeedsUpdate(cardFronts.applied(found.toString()), front, sides)) {
                        setTemplate(models, found, front, sides)
                    }
                    return found
                }
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
        // AnkiDroid's own layout (word, then meaning) stays if this is refused.
        setTemplate(models, id, front, sides)
        return id
    }

    /**
     * Sets the note type's card template to [front] (Settings → Flashcards & Anki → Card front) and
     * remembers it, so a template the learner changed in Anki is only replaced by a new pick.
     */
    private fun setTemplate(
        models: Uri,
        noteType: Long,
        front: CardFront,
        sides: CardSides,
    ) {
        val template = ankiCardTemplate(front, sides)
        val values =
            ContentValues().apply {
                put("question_format", template.question)
                put("answer_format", template.answer)
            }
        val changed = runCatching { resolver.update(Uri.withAppendedPath(models, "$noteType/templates/0"), values, null, null) > 0 }
        if (changed.getOrDefault(false)) cardFronts.remember(noteType.toString(), front, sides)
    }

    /** A note AnkiDroid took; [waiting] when it went without its sentence's sound, which comes later. */
    private class AddedNote(
        val waiting: Boolean,
    )

    private suspend fun addNote(
        noteType: Long,
        deck: Long,
        word: SavedWord,
        media: AnkiCardMedia?,
        options: CardOptions,
    ): AddedNote? {
        val files = media?.files(word, options) ?: AnkiCardFiles()
        val fieldMedia =
            CardMedia(
                word = files.word?.let { ankiSound(addMedia(it)) }.orEmpty(),
                sentence = files.sentence?.let { ankiSound(addMedia(it)) }.orEmpty(),
                picture = files.picture?.let { ankiPicture(addMedia(it)) }.orEmpty(),
            )
        val values =
            ContentValues().apply {
                put("mid", noteType)
                put("flds", ankiNoteFields(word, fieldMedia).joinToString(ANKI_FIELD_SEPARATOR))
                put("tags", ANKI_TAG)
            }
        val notes =
            Uri
                .withAppendedPath(base, "notes")
                .buildUpon()
                .appendQueryParameter("deckId", deck.toString())
                .build()
        val added = resolver.insert(notes, values) ?: return null
        val noteId = added.lastPathSegment?.toLongOrNull()
        val line = audioLineOf(word)
        val pending =
            if (media != null && options.sentenceAudio && fieldMedia.sentence.isEmpty() && noteId != null && line != null) {
                PendingCardAudio(PENDING_ANKIDROID, noteId, line, ANKI_SENTENCE_AUDIO_FIELD, "", System.currentTimeMillis())
            } else {
                null
            }
        pending?.let { media?.awaitSentence(it) }
        return AddedNote(waiting = pending != null)
    }

    /**
     * Copies [file] into AnkiDroid's media and returns the name AnkiDroid gave it, or null when
     * AnkiDroid refused it (the card then goes without it). AnkiDroid reads the file through a
     * content address it may read only during this copy.
     */
    private fun addMedia(file: File): String? {
        val authority = cardAudioAuthority(context)
        val uri = runCatching { FileProvider.getUriForFile(context, authority, file) }.getOrNull() ?: return null
        return try {
            context.grantUriPermission(ANKIDROID_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val values =
                ContentValues().apply {
                    put("file_uri", uri.toString())
                    put("preferred_name", "dualsub_${file.nameWithoutExtension}")
                }
            resolver.insert(Uri.withAppendedPath(base, "media"), values)?.path
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        } finally {
            runCatching { context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    /**
     * Adds a waiting card's sentence clip: the clip goes into AnkiDroid's media and [item]'s field
     * plays it. False when AnkiDroid didn't take it (try again later); true also when the note is
     * gone or has other fields, so it stops waiting.
     */
    suspend fun addSentenceAudio(
        item: PendingCardAudio,
        clip: File,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (!installed() || !permitted()) return@withContext false
            try {
                val note = Uri.withAppendedPath(base, "notes/${item.noteId}")
                val fields =
                    resolver.query(note, arrayOf("flds"), null, null, null)?.use { cursor ->
                        val index = cursor.getColumnIndex("flds")
                        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                    } ?: return@withContext true
                val sound = ankiSound(addMedia(clip)).takeIf { it.isNotEmpty() } ?: return@withContext false
                val updated =
                    ankiFieldsWith(fields.split(ANKI_FIELD_SEPARATOR), item.field, item.base + sound)
                        ?: return@withContext true
                val values = ContentValues().apply { put("flds", updated.joinToString(ANKI_FIELD_SEPARATOR)) }
                resolver.update(note, values, null, null) > 0
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                false
            }
        }
}
