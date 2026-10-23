package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AnkiConnectNotesTest {
    private fun word(id: String) =
        SavedWord(
            id = id,
            word = "猫",
            reading = "ねこ",
            wordLanguage = "ja",
            meaningLanguage = "en",
            meaning = "cat",
            sentence = "猫が好き",
            translatedSentence = "I like cats",
            videoId = "abcdefghijk",
            startMs = 83_400,
            endMs = 85_000,
            translated = true,
        )

    private class MemorySentWords : SentWords {
        val ids = mutableSetOf<String>()

        override fun wasSent(id: String) = id in ids

        override fun markSent(id: String) {
            ids += id
        }
    }

    /**
     * A pretend AnkiConnect: [canMake] is false for AnkiConnect Android, which answers createDeck and
     * createModel with its version text instead of making anything.
     */
    private class FakeAnkiConnect(
        decks: List<String>,
        models: Map<String, List<String>>,
        private val canMake: Boolean,
    ) {
        val decks = decks.toMutableList()
        val models = models.toMutableMap()
        val notes = mutableListOf<JSONObject>()
        val duplicates = mutableSetOf<String>()

        fun answer(body: String): String {
            val request = JSONObject(body)
            assertEquals(6, request.getInt("version"))
            val params = request.optJSONObject("params")
            val result: Any? =
                when (request.getString("action")) {
                    "deckNames" -> JSONArray(decks)
                    "modelNames" -> JSONArray(models.keys.toList())
                    "modelFieldNames" -> JSONArray(models.getValue(params!!.getString("modelName")))
                    "createDeck" -> if (canMake) 1L.also { decks += params!!.getString("deck") } else "AnkiConnect v.6"
                    "createModel" ->
                        if (canMake) {
                            val fields = params!!.getJSONArray("inOrderFields")
                            models[params.getString("modelName")] = (0 until fields.length()).map { fields.getString(it) }
                            JSONObject()
                        } else {
                            "AnkiConnect v.6"
                        }
                    "addNote" -> {
                        val note = params!!.getJSONObject("note")
                        if (note.getJSONObject("fields").optString("Front") in duplicates) {
                            return JSONObject()
                                .put(
                                    "result",
                                    JSONObject.NULL,
                                ).put("error", "cannot create note because it is a duplicate")
                                .toString()
                        }
                        notes += note
                        notes.size.toLong()
                    }
                    else -> return JSONObject().put("result", JSONObject.NULL).put("error", "unsupported action").toString()
                }
            return JSONObject().put("result", result).put("error", JSONObject.NULL).toString()
        }
    }

    @Test
    fun aRequestAndItsReplyFollowAnkiConnectsVersion6() {
        val request = JSONObject(ankiConnectRequest("deckNames"))
        assertEquals("deckNames", request.getString("action"))
        assertEquals(6, request.getInt("version"))
        assertFalse(request.has("params"))
        assertEquals(listOf("Default"), ankiConnectNames(ankiConnectResult("""{"result":["Default"],"error":null}""")))
        val error = runCatching { ankiConnectResult("""{"result":null,"error":"model was not found: X"}""") }.exceptionOrNull()
        assertTrue(error is AnkiConnectError)
        assertEquals("model was not found: X", error?.message)
        assertTrue(runCatching { ankiConnectResult("<html>not AnkiConnect</html>") }.exceptionOrNull() is AnkiConnectError)
    }

    @Test
    fun theDeckIsTheChosenOneThenDualSubReplayThenDefault() {
        assertEquals("Mining", ankiConnectDeck(listOf("Default", "Mining"), "Mining"))
        assertEquals(ANKI_DECK_NAME, ankiConnectDeck(listOf("Default", ANKI_DECK_NAME), "Gone"))
        assertNull(ankiConnectDeck(listOf("Default"), null))
        assertEquals("Default", ankiConnectFallbackDeck(listOf("Japanese", "Default")))
        assertEquals("Japanese", ankiConnectFallbackDeck(listOf("Japanese")))
        assertEquals("Basic", ankiConnectFallbackNoteType(listOf("Cloze", "Basic (and reversed card)", "Basic")))
        assertEquals("Basic (and reversed card)", ankiConnectFallbackNoteType(listOf("Cloze", "Basic (and reversed card)")))
    }

    @Test
    fun anotherNoteTypeGetsTheWordAndSentenceOnTheFrontAndTheRestOnTheBack() {
        val fields = ankiConnectFields(listOf("Front", "Back"), word("1"))
        assertEquals("猫<br><br><b>猫</b>が好き", fields["Front"])
        assertTrue(fields.getValue("Back").startsWith("ねこ<br>cat<br>I like cats<br><a href="))
        assertEquals(ANKI_FIELDS, ankiConnectFields(ANKI_FIELDS, word("1")).keys.toList())
    }

    @Test
    fun desktopAnkiConnectGetsTheDualSubReplayDeckAndNoteType() =
        runBlocking {
            val anki = FakeAnkiConnect(listOf("Default"), mapOf("Basic" to listOf("Front", "Back")), canMake = true)
            val notes = AnkiConnectNotes(MemorySentWords(), { anki.answer(it) })
            val result = notes.send(listOf(word("1"), word("2")), chosenDeck = null)
            assertEquals(AnkiSendResult(sent = 2), result)
            val note = anki.notes.first()
            assertEquals(ANKI_DECK_NAME, note.getString("deckName"))
            assertEquals(ANKI_NOTE_TYPE_NAME, note.getString("modelName"))
            assertEquals("猫", note.getJSONObject("fields").getString("Word"))
            assertEquals(ANKI_TAG, note.getJSONArray("tags").getString(0))
        }

    @Test
    fun ankiConnectAndroidFallsBackToDefaultAndBasic() =
        runBlocking {
            val anki = FakeAnkiConnect(listOf("Default"), mapOf("Basic" to listOf("Front", "Back")), canMake = false)
            val notes = AnkiConnectNotes(MemorySentWords(), { anki.answer(it) })
            assertEquals(AnkiSendResult(sent = 1), notes.send(listOf(word("1")), chosenDeck = null))
            val note = anki.notes.single()
            assertEquals("Default", note.getString("deckName"))
            assertEquals("Basic", note.getString("modelName"))
            assertEquals(
                setOf("Front", "Back"),
                note
                    .getJSONObject("fields")
                    .keys()
                    .asSequence()
                    .toSet(),
            )
        }

    @Test
    fun theChosenDeckIsUsedAndAWordIsSentOnce() =
        runBlocking {
            val anki = FakeAnkiConnect(listOf("Default", "Mining"), mapOf("Basic" to listOf("Front", "Back")), canMake = false)
            val sent = MemorySentWords()
            val notes = AnkiConnectNotes(sent, { anki.answer(it) })
            notes.send(listOf(word("1")), chosenDeck = "Mining")
            assertEquals("Mining", anki.notes.single().getString("deckName"))
            assertTrue(notes.wasSent("1"))
            assertEquals(AnkiSendResult(skipped = 1), notes.send(listOf(word("1")), chosenDeck = "Mining"))
            assertEquals(1, anki.notes.size)
        }

    @Test
    fun aNoteAnkiAlreadyHasCountsAsSentBefore() =
        runBlocking {
            val anki = FakeAnkiConnect(listOf("Default"), mapOf("Basic" to listOf("Front", "Back")), canMake = false)
            anki.duplicates += ankiConnectFields(listOf("Front", "Back"), word("1")).getValue("Front")
            val sent = MemorySentWords()
            val result = AnkiConnectNotes(sent, { anki.answer(it) }).send(listOf(word("1"), word("2")), chosenDeck = null)
            // word 2 has the same text, so it is a duplicate too.
            assertEquals(AnkiSendResult(sent = 0, skipped = 2), result)
            assertEquals(setOf("1", "2"), sent.ids)
        }

    @Test
    fun nothingAnsweringIsNotRunningAndARefusalKeepsAnkiConnectsWords() =
        runBlocking {
            val silent = AnkiConnectNotes(MemorySentWords(), { throw IOException("Connection refused") })
            assertEquals(AnkiProblem.NOT_RUNNING, silent.send(listOf(word("1")), chosenDeck = null).problem)

            val refusing =
                AnkiConnectNotes(
                    MemorySentWords(),
                    { body ->
                        if (JSONObject(body).getString("action") == "addNote") {
                            """{"result":null,"error":"collection is not available\nTraceback..."}"""
                        } else {
                            FakeAnkiConnect(listOf("Default"), mapOf("Basic" to listOf("Front", "Back")), false).answer(body)
                        }
                    },
                )
            val result = refusing.send(listOf(word("1")), chosenDeck = null)
            assertEquals(AnkiProblem.FAILED, result.problem)
            assertEquals("collection is not available", result.detail)
            assertFalse(refusing.wasSent("1"))
        }
}
