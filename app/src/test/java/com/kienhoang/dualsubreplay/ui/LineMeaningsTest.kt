package com.kienhoang.dualsubreplay.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LineMeaningsTest {
    private val line = MeaningContext("I go to school.", "Tôi đi học.")
    private val meanings = HashMap<String, String>()
    private val asked = mutableListOf<String>()

    private fun key(word: String) = contextMeaningKey(word, line, "en", "vi")

    private fun lineMeanings(
        scope: kotlinx.coroutines.CoroutineScope,
        reply: suspend (user: String) -> String?,
    ) = LineMeanings(
        scope = scope,
        cached = meanings::get,
        store = { key, meaning -> meanings[key] = meaning },
        words = { sentence, _ -> sentence.split(' ', '.').filter(String::isNotEmpty) },
        ask = { _, user, _ ->
            asked += user
            reply(user)
        },
    )

    @Test
    fun oneRequestAnswersEveryOtherWordOfTheLine() =
        runTest(UnconfinedTestDispatcher()) {
            meanings[key("go")] = "đi"
            val lines = lineMeanings(this) { "1. tôi\n2. đến\n3) trường" }
            lines.prepare(line, "en", "vi")
            assertEquals(1, asked.size)
            // The tapped word already has its meaning, so only the others are asked about.
            assertTrue(asked.single().endsWith("Words:\n1. I\n2. to\n3. school"))
            assertEquals("tôi", meanings[key("I")])
            assertEquals("trường", meanings[key("school")])
            assertEquals("đi", meanings[key("go")])
            // A line already answered is not asked again.
            lines.prepare(line, "en", "vi")
            assertEquals(1, asked.size)
        }

    @Test
    fun aFailedRequestIsAskedAgainLaterAndNoKeyAsksNothing() =
        runTest(UnconfinedTestDispatcher()) {
            var reply: String? = ""
            val lines = lineMeanings(this) { reply }
            lines.prepare(line, "en", "vi")
            lines.prepare(line, "en", "vi")
            assertEquals(2, asked.size)
            assertNull(meanings[key("school")])
            reply = null
            lines.prepare(line, "en", "vi")
            assertTrue(meanings.isEmpty())
        }

    @Test
    fun whileARequestRunsOnlyTheNewestLineWaits() =
        runTest(UnconfinedTestDispatcher()) {
            val answers = mutableListOf<CompletableDeferred<String>>()
            val lines =
                lineMeanings(this) {
                    CompletableDeferred<String>().also(answers::add).await()
                }
            lines.prepare(MeaningContext("one"), "en", "vi")
            lines.prepare(MeaningContext("two"), "en", "vi")
            lines.prepare(MeaningContext("three"), "en", "vi")
            assertEquals(1, asked.size)
            answers[0].complete("1. một")
            assertEquals(2, asked.size)
            assertTrue(asked[1].endsWith("1. three"))
            answers[1].complete("1. ba")
            assertEquals(2, asked.size)
            assertEquals("ba", meanings[contextMeaningKey("three", MeaningContext("three"), "en", "vi")])
        }

    @Test
    fun aTapWaitsForTheRequestAboutItsLine() =
        runTest(UnconfinedTestDispatcher()) {
            val answer = CompletableDeferred<String>()
            val lines = lineMeanings(this) { answer.await() }
            lines.prepare(line, "en", "vi")
            var found: String? = null
            val tap =
                launch {
                    lines.awaitRunning(line, "en", "vi")
                    found = meanings[key("school")]
                }
            assertNull(found)
            answer.complete("1. tôi\n2. đi\n3. đến\n4. trường")
            tap.join()
            assertEquals("trường", found)
        }

    @Test
    fun repliesMapBackToTheirWords() {
        val words = listOf("I", "go", "school")
        assertEquals(
            mapOf("I" to "tôi", "school" to "trường"),
            parseLineMeanings("Here:\n1. tôi\n 3: trường\n9. extra\n2.", words),
        )
        assertEquals(emptyMap<String, String>(), parseLineMeanings(null, words))
        assertEquals(listOf("I", "go", "school"), lineMeaningWords(listOf("I", " go", ",", "I", "school", "…")))
    }
}
