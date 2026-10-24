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
 * any other note type, the word and sentence on the front (first field) and the rest on the back.
 */
internal fun ankiConnectFields(
    fieldNames: List<String>,
    word: SavedWord,
): Map<String, String> {
    val values = ankiNoteFields(word)
    if (fieldNames == ANKI_FIELDS) return ANKI_FIELDS.zip(values).toMap()
    if (fieldNames.isEmpty()) return emptyMap()
    val front = listOf(values[0], values[3]).filter { it.isNotBlank() }.joinToString("<br><br>")
    val back = listOf(values[2], values[1], values[4], values[5]).filter { it.isNotBlank() }.joinToString("<br>")
    if (fieldNames.size == 1) return mapOf(fieldNames[0] to listOf(front, back).filter { it.isNotBlank() }.joinToString("<hr>"))
    return mapOf(fieldNames[0] to front, fieldNames[1] to back)
}

/** The DualSub Replay note type, made where AnkiConnect can make one (desktop Anki). */
internal fun ankiConnectNoteTypeParams(): JSONObject =
    JSONObject()
        .put("modelName", ANKI_NOTE_TYPE_NAME)
        .put("inOrderFields", JSONArray(ANKI_FIELDS))
        .put("css", ANKI_CSS)
        .put("isCloze", false)
        .put(
            "cardTemplates",
            JSONArray().put(
                JSONObject()
                    .put("Name", "Card 1")
                    .put("Front", ANKI_QUESTION_FORMAT)
                    .put("Back", ANKI_ANSWER_FORMAT),
            ),
        )

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
    ): AnkiSendResult {
        val unsent = unsentAnkiWords(words, ::wasSent)
        var skipped = words.distinctBy { it.id }.size - unsent.size
        if (unsent.isEmpty()) return AnkiSendResult(skipped = skipped)
        var sent = 0
        return try {
            val deck = deck(chosenDeck) ?: return AnkiSendResult(skipped = skipped, problem = AnkiProblem.FAILED)
            val (noteType, fields) = noteType() ?: return AnkiSendResult(skipped = skipped, problem = AnkiProblem.FAILED)
            for (word in unsent) {
                try {
                    call("addNote", JSONObject().put("note", note(deck, noteType, ankiConnectFields(fields, word))))
                    sent++
                } catch (error: AnkiConnectError) {
                    // Anki already has this note (sent from another phone, or before a reinstall).
                    if (error.message?.contains("duplicate", ignoreCase = true) != true) throw error
                    skipped++
                }
                sentWords.markSent(word.id)
            }
            AnkiSendResult(sent, skipped)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: IOException) {
            AnkiSendResult(sent, skipped, AnkiProblem.NOT_RUNNING)
        } catch (error: AnkiConnectError) {
            AnkiSendResult(sent, skipped, AnkiProblem.FAILED, error.message)
        } catch (_: JSONException) {
            AnkiSendResult(sent, skipped, AnkiProblem.FAILED)
        }
    }

    private suspend fun deck(chosen: String?): String? {
        val decks = ankiConnectNames(call("deckNames"))
        ankiConnectDeck(decks, chosen)?.let { return it }
        // Desktop AnkiConnect makes the deck; AnkiConnect Android cannot, so the cards go to Default.
        tryCall("createDeck", JSONObject().put("deck", ANKI_DECK_NAME))
        val after = ankiConnectNames(call("deckNames"))
        return ankiConnectDeck(after, chosen) ?: ankiConnectFallbackDeck(after)
    }

    private suspend fun noteType(): Pair<String, List<String>>? {
        var names = ankiConnectNames(call("modelNames"))
        if (ANKI_NOTE_TYPE_NAME !in names) {
            tryCall("createModel", ankiConnectNoteTypeParams())
            names = ankiConnectNames(call("modelNames"))
        }
        val name = ANKI_NOTE_TYPE_NAME.takeIf { it in names } ?: ankiConnectFallbackNoteType(names) ?: return null
        return name to ankiConnectNames(call("modelFieldNames", JSONObject().put("modelName", name)))
    }

    private fun note(
        deck: String,
        noteType: String,
        fields: Map<String, String>,
    ): JSONObject =
        JSONObject()
            .put("deckName", deck)
            .put("modelName", noteType)
            .put("fields", JSONObject(fields))
            .put("tags", JSONArray(listOf(ANKI_TAG)))
            .put("options", JSONObject().put("allowDuplicate", false).put("duplicateScope", "deck"))

    private suspend fun call(
        action: String,
        params: JSONObject? = null,
    ): Any? = ankiConnectResult(post(ankiConnectRequest(action, params)))

    /** A request AnkiConnect may not know (AnkiConnect Android has no createDeck or createModel). */
    private suspend fun tryCall(
        action: String,
        params: JSONObject,
    ) {
        try {
            call(action, params)
        } catch (_: AnkiConnectError) {
            // Checked afterwards by listing again.
        } catch (_: JSONException) {
            // Same.
        }
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
            )
        }
    }
}
