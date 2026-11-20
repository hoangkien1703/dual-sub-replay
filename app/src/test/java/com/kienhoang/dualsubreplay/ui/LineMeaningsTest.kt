package com.kienhoang.dualsubreplay.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
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
        ask = { _, user, _, onText ->
            asked += user
            reply(user)?.also(onText)
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
                    found = lines.awaitWord("school", line, "en", "vi", maxMs = 2_000)
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

    /** lion737 (#81, 2026-11-20): a word near the start of the reply shows before the rest is written. */
    @Test
    fun eachWordIsStoredAsSoonAsItsLineArrives() =
        runTest(UnconfinedTestDispatcher()) {
            val rest = CompletableDeferred<Unit>()
            val lines =
                LineMeanings(
                    scope = this,
                    cached = meanings::get,
                    store = { key, meaning -> meanings[key] = meaning },
                    words = { sentence, _ -> sentence.split(' ', '.').filter(String::isNotEmpty) },
                    ask = { _, _, _, onText ->
                        onText("1. tôi\n2. đ")
                        rest.await()
                        onText("i\n3. đến\n4. trường")
                        "1. tôi\n2. đi\n3. đến\n4. trường"
                    },
                )
            lines.prepare(line, "en", "vi", urgent = true)
            assertEquals("tôi", meanings[key("I")])
            assertNull(meanings[key("go")])
            assertEquals("tôi", lines.awaitWord("I", line, "en", "vi", maxMs = 2_000))
            rest.complete(Unit)
            assertEquals("đi", meanings[key("go")])
            // The last line had no line break: the whole reply still stores it.
            assertEquals("trường", meanings[key("school")])
        }

    /** lion737 (#81, 2026-11-20): a tap waits briefly for the line's request, then asks on its own. */
    @Test
    fun aTapWaitsOnlyBrieflyForTheLinesRequest() =
        runTest {
            val never = CompletableDeferred<String>()
            val lines = lineMeanings(this) { never.await() }
            lines.prepare(line, "en", "vi")
            val start = currentTime
            assertNull(lines.awaitWord("school", line, "en", "vi", maxMs = 2_000))
            assertEquals(2_000L, currentTime - start)
            // No request about another line: nothing to wait for.
            assertNull(lines.awaitWord("one", MeaningContext("one"), "en", "vi", maxMs = 2_000))
            assertEquals(2_000L, currentTime - start)
            never.complete("")
        }
}
