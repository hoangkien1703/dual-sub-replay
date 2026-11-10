package com.kienhoang.dualsubreplay.data

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * The AnkiConnect address on this phone: the "AnkiConnect Android" app serves it, and Yomitan,
 * asbplayer and other flashcard tools speak the same protocol. Only this address is ever asked
 * (the network security config allows cleartext to 127.0.0.1 and nothing else).
 */
internal const val ANKICONNECT_URL = "http://127.0.0.1:8765"
internal const val ANKICONNECT_ANDROID_PACKAGE = "com.kamwithk.ankiconnectandroid"
private const val ANKICONNECT_VERSION = 6
private const val ANKICONNECT_CONNECT_TIMEOUT_S = 3L
private const val ANKICONNECT_READ_TIMEOUT_S = 20L

/** The longest AnkiConnect error text a card shows. */
private const val ANKICONNECT_DETAIL_LIMIT = 160

/** The deck that has always existed in every Anki collection. */
internal const val ANKI_DEFAULT_DECK = "Default"

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** An error AnkiConnect answered with: its own text, kept for the word card. */
internal class AnkiConnectError(
    message: String,
) : Exception(message)

/** The JSON body of one AnkiConnect request. */
internal fun ankiConnectRequest(
    action: String,
    params: JSONObject? = null,
): String =
    JSONObject()
        .put("action", action)
        .put("version", ANKICONNECT_VERSION)
        .apply { if (params != null) put("params", params) }
        .toString()

/** The `result` of an AnkiConnect reply; throws [AnkiConnectError] with its `error` text. */
internal fun ankiConnectResult(body: String): Any? {
    val reply =
        try {
            JSONObject(body)
        } catch (_: JSONException) {
            throw AnkiConnectError(body.trim().take(ANKICONNECT_DETAIL_LIMIT))
        }
    val error = reply.opt("error")
    if (error != null &&
        error != JSONObject.NULL
    ) {
        throw AnkiConnectError(
            error
                .toString()
                .lineSequence()
                .first()
                .take(ANKICONNECT_DETAIL_LIMIT),
        )
    }
    return reply.opt("result")
}

/** Whether AnkiConnect did a request: AnkiConnect Android answers one it doesn't know with its version text. */
internal fun ankiConnectDone(result: Any?): Boolean = !(result is String && result.startsWith("AnkiConnect"))

/** A JSON list of text (deck, note type or field names), or empty when the result is something else. */
internal fun ankiConnectNames(result: Any?): List<String> {
    val list = result as? JSONArray ?: return emptyList()
    return (0 until list.length()).mapNotNull { list.opt(it) as? String }
}

/**
 * The deck the cards go to: the one chosen in Settings while it still exists, else a DualSub Replay
 * deck, else null (the caller makes one, then falls back to [ankiConnectFallbackDeck]).
 */
internal fun ankiConnectDeck(
    decks: List<String>,
    chosen: String?,
): String? = chosen?.takeIf { it in decks } ?: ANKI_DECK_NAME.takeIf { it in decks }

internal fun ankiConnectFallbackDeck(decks: List<String>): String? = ANKI_DEFAULT_DECK.takeIf { it in decks } ?: decks.firstOrNull()

/**
 * The note type a card uses when the DualSub Replay one cannot be made (AnkiConnect Android cannot
 * make note types): Anki's own Basic, else any note type with at least two fields.
 */
internal fun ankiConnectFallbackNoteType(names: List<String>): String? =
    names.firstOrNull { it == "Basic" } ?: names.firstOrNull { it.startsWith("Basic") } ?: names.firstOrNull()

/**
 * [word]'s note for a note type with [fieldNames]: the DualSub Replay fields one by one, or, in
 * any other note type, what [front] puts on the front in the first field and the rest on the back.
 */
internal fun ankiConnectFields(
    fieldNames: List<String>,
    word: SavedWord,
    media: CardMedia = CardMedia(),
    front: CardFront = CardFront.WORD_AND_SENTENCE,
): Map<String, String> {
    val values = ankiNoteFields(word, media)
    if (fieldNames == ANKI_FIELDS) return ANKI_FIELDS.zip(values).toMap()
    if (fieldNames.isEmpty()) return emptyMap()
    val (frontParts, backParts) = basicCardSides(front, values)
    val frontText = frontParts.filter { it.isNotBlank() }.joinToString("<br><br>")
    val back = backParts.filter { it.isNotBlank() }.joinToString("<br>")
    if (fieldNames.size == 1) return mapOf(fieldNames[0] to listOf(frontText, back).filter { it.isNotBlank() }.joinToString("<hr>"))
    return mapOf(fieldNames[0] to frontText, fieldNames[1] to back)
}

/** The fields ([values], in [ANKI_FIELDS] order) on a two-sided card's front and back for [front]. */
private fun basicCardSides(
    front: CardFront,
    values: List<String>,
): Pair<List<String>, List<String>> {
    val (word, meaning, reading, sentence) = values
    val translation = values[4]
    val source = values[5]
    val wordAudio = values[6]
    val sentenceAudio = values[7]
    return when (front) {
        CardFront.WORD_AND_SENTENCE ->
            listOf(word, sentence, wordAudio) to listOf(reading, meaning, translation, sentenceAudio, source)
        CardFront.WORD -> listOf(word, wordAudio) to listOf(reading, meaning, sentence, translation, sentenceAudio, source)
        CardFront.SENTENCE -> listOf(sentence) to listOf(word, wordAudio, reading, meaning, translation, sentenceAudio, source)
        CardFront.LISTENING ->
            if (sentenceAudio.isBlank()) {
                listOf(sentence) to listOf(word, wordAudio, reading, meaning, translation, source)
            } else {
                listOf(sentenceAudio) to listOf(sentence, word, wordAudio, reading, meaning, translation, source)
            }
    }
}

/**
 * Where a card sent without its sentence clip gets it later: the Sentence audio field of the
 * DualSub Replay audio type, else the back (or only) field, after the text it was sent with.
 */
internal fun ankiConnectSentenceTarget(
    fieldNames: List<String>,
    fields: Map<String, String>,
): Pair<String, String>? {
    if (fieldNames == ANKI_FIELDS) return ANKI_SENTENCE_AUDIO_FIELD to ""
    val field = fieldNames.getOrNull(1) ?: fieldNames.firstOrNull() ?: return null
    val base = fields[field].orEmpty()
    return field to if (base.isBlank()) "" else "$base<br>"
}

/** The DualSub Replay note type, made where AnkiConnect can make one (desktop Anki). */
internal fun ankiConnectNoteTypeParams(front: CardFront = CardFront.WORD_AND_SENTENCE): JSONObject {
    val template = ankiCardTemplate(front)
    return JSONObject()
        .put("modelName", ANKI_NOTE_TYPE_NAME)
        .put("inOrderFields", JSONArray(ANKI_FIELDS))
        .put("css", ANKI_CSS)
        .put("isCloze", false)
        .put(
            "cardTemplates",
            JSONArray().put(
                JSONObject()
                    .put("Name", ANKI_CARD_TEMPLATE_NAME)
                    .put("Front", template.question)
                    .put("Back", template.answer),
            ),
        )
}

/** The note type's card template set to [front] (AnkiConnect's `updateModelTemplates`). */
internal fun ankiConnectTemplateParams(front: CardFront): JSONObject {
    val template = ankiCardTemplate(front)
    val card = JSONObject().put("Front", template.question).put("Back", template.answer)
    return JSONObject().put(
        "model",
        JSONObject()
            .put("name", ANKI_NOTE_TYPE_NAME)
            .put("templates", JSONObject().put(ANKI_CARD_TEMPLATE_NAME, card)),
    )
}

private const val ANKI_CARD_TEMPLATE_NAME = "Card 1"

/** Which words were sent, kept on the phone so a word is not sent twice. */
internal interface SentWords {
    fun wasSent(id: String): Boolean

    fun markSent(id: String)
}

private class PreferenceSentWords(
    context: Context,
    name: String,
) : SentWords {
    private val preferences = context.getSharedPreferences(name, 0)

    override fun wasSent(id: String) = preferences.getBoolean(id, false)

    override fun markSent(id: String) = preferences.edit().putBoolean(id, true).apply()
}

/**
 * Adds notes through AnkiConnect at [ANKICONNECT_URL]. [post] sends one request body and returns the
 * reply body; it throws an [IOException] when nothing answers.
 */
internal class AnkiConnectNotes(
    private val sentWords: SentWords,
    private val post: suspend (String) -> String,
    private val launchIntent: () -> Intent? = { null },
    private val start: (Intent) -> Unit = {},
    private val cardFronts: CardFrontMemory = InMemoryCardFronts(),
) {
    fun wasSent(id: String): Boolean = sentWords.wasSent(id)

    /** True when the AnkiConnect Android app is installed, so a card can offer to open it. */
    fun canOpen(): Boolean = launchIntent() != null

    /** Opens the AnkiConnect Android app, which starts its server. */
    fun open() {
        launchIntent()?.let { runCatching { start(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    suspend fun deckNames(): List<String> = ankiConnectNames(call("deckNames"))

    suspend fun send(
        words: List<SavedWord>,
        chosenDeck: String?,
        media: AnkiCardMedia? = null,
        options: CardOptions = CardOptions(),
        again: Boolean = false,
    ): AnkiSendResult {
        val unsent = unsentAnkiWords(words, again, ::wasSent)
        var skipped = words.distinctBy { it.id }.size - unsent.size
        if (unsent.isEmpty()) return AnkiSendResult(skipped = skipped)
        var sent = 0
        var waiting = 0
        return try {
            val deck = deck(chosenDeck) ?: return AnkiSendResult(skipped = skipped, problem = AnkiProblem.FAILED)
            val (noteType, fields) = noteType(options.front) ?: return AnkiSendResult(skipped = skipped, problem = AnkiProblem.FAILED)
            for (word in unsent) {
                try {
                    val files = media?.files(word, options) ?: AnkiCardFiles()
                    val fieldMedia =
                        CardMedia(
                            word = files.word?.let { ankiSound(storeMedia(it)) }.orEmpty(),
                            sentence = files.sentence?.let { ankiSound(storeMedia(it)) }.orEmpty(),
                            picture = files.picture?.let { ankiPicture(storeMedia(it)) }.orEmpty(),
                        )
                    val values = ankiConnectFields(fields, word, fieldMedia, options.front)
                    val request = JSONObject().put("note", note(deck, noteType, values, allowDuplicate = again))
                    val noteId = (call("addNote", request) as? Number)?.toLong()
                    val line = audioLineOf(word)
                    val target = ankiConnectSentenceTarget(fields, values)
                    val waits = options.audio && fieldMedia.sentence.isEmpty()
                    if (waits && media != null && noteId != null && line != null && target != null) {
                        media.awaitSentence(
                            PendingCardAudio(PENDING_ANKICONNECT, noteId, line, target.first, target.second, System.currentTimeMillis()),
                        )
                        waiting++
                    }
                    sent++
                } catch (error: AnkiConnectError) {
                    // Anki already has this note (sent from another phone, or before a reinstall).
                    if (error.message?.contains("duplicate", ignoreCase = true) != true) throw error
                    skipped++
                }
                sentWords.markSent(word.id)
            }
            AnkiSendResult(sent, skipped, waiting = waiting)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: IOException) {
            AnkiSendResult(sent, skipped, AnkiProblem.NOT_RUNNING, waiting = waiting)
        } catch (error: AnkiConnectError) {
            AnkiSendResult(sent, skipped, AnkiProblem.FAILED, error.message, waiting)
        } catch (_: JSONException) {
            AnkiSendResult(sent, skipped, AnkiProblem.FAILED, waiting = waiting)
        }
    }

    /**
     * Stores [file] in Anki's media and returns the name it got, or null when AnkiConnect refused
     * it (the card then goes without it). AnkiConnect Android may add a number to the name, so the
     * name it answers is used.
     */
    private suspend fun storeMedia(file: File): String? {
        val data = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() } ?: return null
        val params =
            JSONObject()
                .put("filename", "dualsub_${file.name}")
                .put(
                    "data",
                    java.util.Base64
                        .getEncoder()
                        .encodeToString(data),
                )
        return try {
            call("storeMediaFile", params) as? String
        } catch (_: AnkiConnectError) {
            null
        } catch (_: JSONException) {
            null
        }
    }

    /**
     * Adds a waiting card's sentence clip to [item]'s field. False when AnkiConnect didn't answer
     * (try again later); true also when it refused, so the card stops waiting.
     */
    suspend fun addSentenceAudio(
        item: PendingCardAudio,
        clip: File,
    ): Boolean =
        try {
            val sound = ankiSound(storeMedia(clip))
            if (sound.isEmpty()) {
                false
            } else {
                val note = JSONObject().put("id", item.noteId).put("fields", JSONObject().put(item.field, item.base + sound))
                call("updateNoteFields", JSONObject().put("note", note))
                true
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: IOException) {
            false
        } catch (_: AnkiConnectError) {
            true
        } catch (_: JSONException) {
            true
        }

    private suspend fun deck(chosen: String?): String? {
        val decks = ankiConnectNames(call("deckNames"))
        ankiConnectDeck(decks, chosen)?.let { return it }
        // Desktop AnkiConnect makes the deck; AnkiConnect Android cannot, so the cards go to Default.
        tryCall("createDeck", JSONObject().put("deck", ANKI_DECK_NAME))
        val after = ankiConnectNames(call("deckNames"))
        return ankiConnectDeck(after, chosen) ?: ankiConnectFallbackDeck(after)
    }

    private suspend fun noteType(front: CardFront): Pair<String, List<String>>? {
        var names = ankiConnectNames(call("modelNames"))
        if (ANKI_NOTE_TYPE_NAME !in names) {
            if (tryCall("createModel", ankiConnectNoteTypeParams(front))) cardFronts.remember(ANKI_NOTE_TYPE_NAME, front)
            names = ankiConnectNames(call("modelNames"))
        } else if (cardFrontNeedsUpdate(cardFronts.applied(ANKI_NOTE_TYPE_NAME), front)) {
            // Desktop AnkiConnect changes the template; AnkiConnect Android can't, and is asked again next time.
            if (tryCall("updateModelTemplates", ankiConnectTemplateParams(front))) cardFronts.remember(ANKI_NOTE_TYPE_NAME, front)
        }
        val name = ANKI_NOTE_TYPE_NAME.takeIf { it in names } ?: ankiConnectFallbackNoteType(names) ?: return null
        return name to ankiConnectNames(call("modelFieldNames", JSONObject().put("modelName", name)))
    }

    private fun note(
        deck: String,
        noteType: String,
        fields: Map<String, String>,
        allowDuplicate: Boolean = false,
    ): JSONObject =
        JSONObject()
            .put("deckName", deck)
            .put("modelName", noteType)
            .put("fields", JSONObject(fields))
            .put("tags", JSONArray(listOf(ANKI_TAG)))
            // Send again (lion737, #81) asks for a second card on purpose; otherwise Anki refuses a duplicate.
            .put("options", JSONObject().put("allowDuplicate", allowDuplicate).put("duplicateScope", "deck"))

    private suspend fun call(
        action: String,
        params: JSONObject? = null,
    ): Any? = ankiConnectResult(post(ankiConnectRequest(action, params)))

    /**
     * A request AnkiConnect may not know (AnkiConnect Android has no createDeck, createModel or
     * updateModelTemplates; it answers them with its version text). True when it was done.
     */
    private suspend fun tryCall(
        action: String,
        params: JSONObject,
    ): Boolean =
        try {
            ankiConnectDone(call(action, params))
        } catch (_: AnkiConnectError) {
            false
        } catch (_: JSONException) {
            false
        }

    companion object {
        fun create(context: Context): AnkiConnectNotes {
            val app = context.applicationContext
            val client =
                OkHttpClient
                    .Builder()
                    .proxy(Proxy.NO_PROXY)
                    .connectTimeout(ANKICONNECT_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                    .readTimeout(ANKICONNECT_READ_TIMEOUT_S, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false)
                    .build()
            val post: suspend (String) -> String = { body ->
                withContext(Dispatchers.IO) {
                    val request =
                        Request
                            .Builder()
                            .url(ANKICONNECT_URL)
                            .post(body.toRequestBody(JSON_MEDIA_TYPE))
                            .build()
                    client.newCall(request).execute().use { response -> response.body.string() }
                }
            }
            return AnkiConnectNotes(
                PreferenceSentWords(app, "ankiconnect_sent"),
                post,
                launchIntent = { app.packageManager.getLaunchIntentForPackage(ANKICONNECT_ANDROID_PACKAGE) },
                start = app::startActivity,
                cardFronts = PreferenceCardFronts(app, "ankiconnect_card_front"),
            )
        }
    }
}
