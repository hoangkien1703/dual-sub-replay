package com.kienhoang.dualsubreplay.translation

import com.kienhoang.dualsubreplay.data.SentenceSlice
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.ui.translationTexts
import com.kienhoang.dualsubreplay.ui.withTranslation
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class AiSubtitleTranslatorTest {
    private val sentence = "The reality is that two different things are happening at once right now."
    private val cut = sentence.indexOf("happening")

    private val rows =
        listOf(
            SubtitleSegment(0, 0, 2_000, sentence.substring(0, cut).trim(), sentence = SentenceSlice(sentence, listOf(cut), 0)),
            SubtitleSegment(1, 2_000, 4_000, sentence.substring(cut), sentence = SentenceSlice(sentence, listOf(cut), 1)),
            SubtitleSegment(2, 4_000, 5_000, "On one side, we have the CVEs."),
        )

    private class FakeModel(
        private val answer: (String) -> String,
    ) : AiTranslationModel {
        override val scope = "fake/model"
        val requests = mutableListOf<String>()

        override suspend fun ask(
            system: String,
            user: String,
        ): String {
            requests += user
            return answer(user)
        }
    }

    @Test fun theTextsThePlayerAsksForGroupBackIntoSentencesWithRows() {
        val sentences = aiSentences(translationTexts(rows))
        assertEquals(2, sentences.size)
        assertEquals(listOf("The reality is that two different things are", "happening at once right now."), sentences[0].rows)
        assertEquals(listOf("The reality is that two different things are"), sentences[0].prefixes)
        assertEquals(listOf("On one side, we have the CVEs."), sentences[1].rows)
    }

    @Test fun theRequestNumbersSentencesAndCarriesContextSeparately() {
        val request = JSONObject(aiTranslationRequest(listOf("Earlier line."), aiSentences(translationTexts(rows))))
        assertEquals("Earlier line.", request.getJSONArray("context").getString(0))
        val first = request.getJSONArray("sentences").getJSONObject(0)
        assertEquals(1, first.getInt("id"))
        assertEquals(2, first.getJSONArray("rows").length())
    }

    @Test fun aReplyInACodeFenceIsStillRead() {
        val sentences = aiSentences(translationTexts(rows))
        val parsed =
            parseAiTranslationReply(
                "Here you go:\n```json\n{\"sentences\":[{\"id\":2,\"rows\":[\"Ở một phía, ta có các CVE.\"]}]}\n```",
                sentences,
            )
        assertNull(parsed[0])
        assertEquals(listOf("Ở một phía, ta có các CVE."), parsed[1])
    }

    @Test fun rowsTheModelMergedBecomeOneWholeTranslation() {
        val sentences = aiSentences(translationTexts(rows))
        val parsed =
            parseAiTranslationReply(
                "{\"sentences\":[{\"id\":1,\"rows\":[\"Thực tế là có hai chuyện khác nhau đang diễn ra.\"]}]}",
                sentences,
            )
        assertEquals(listOf("Thực tế là có hai chuyện khác nhau đang diễn ra."), parsed[0])
        assertEquals(mapOf(sentence to "Thực tế là có hai chuyện khác nhau đang diễn ra."), aiTranslationEntries(sentences[0], parsed[0]!!))
    }

    @Test fun aReplyWithoutJsonIsAFailure() {
        try {
            parseAiTranslationReply("Sorry, I can't help with that.", aiSentences(translationTexts(rows)))
            fail("expected a failure")
        } catch (_: AiTranslationException) {
        }
    }

    @Test fun eachRowGetsExactlyTheWordsTheModelGaveIt() =
        runBlocking {
            val model =
                FakeModel {
                    """{"sentences":[
                        {"id":1,"rows":["Thực tế là có hai chuyện khác nhau","đang diễn ra cùng lúc ngay lúc này."]},
                        {"id":2,"rows":["Ở một phía, ta có các CVE."]}]}"""
                }
            val translator = AiSubtitleTranslator(cacheDirectory = null)
            val texts = translationTexts(rows)
            translator.translateAll(model, "en", "vi", texts)
            val whole = translator.cachedTranslation(model, "en", "vi", sentence)!!
            val prefix = translator.cachedTranslation(model, "en", "vi", rows[0].originalText)!!
            val shown = withTranslation(rows, 0, whole, listOf(prefix))
            assertEquals("Thực tế là có hai chuyện khác nhau", shown[0].translatedText)
            assertEquals("đang diễn ra cùng lúc ngay lúc này.", shown[1].translatedText)
            assertEquals("Ở một phía, ta có các CVE.", translator.cachedTranslation(model, "en", "vi", rows[2].originalText))
            // Everything is cached: a second call sends nothing.
            translator.translateAll(model, "en", "vi", texts)
            assertEquals(1, model.requests.size)
        }

    @Test fun theNextRequestCarriesTheLastSentencesAsContext() =
        runBlocking {
            val model = FakeModel { """{"sentences":[{"id":1,"rows":["Một."]}]}""" }
            val translator = AiSubtitleTranslator(cacheDirectory = null)
            translator.translateAll(model, "en", "vi", listOf("One."))
            translator.translateAll(model, "en", "vi", listOf("Two."))
            assertEquals("One.", JSONObject(model.requests[1]).getJSONArray("context").getString(0))
        }

    @Test fun aSentenceTheModelLeftOutStaysForGoogle() =
        runBlocking {
            val model = FakeModel { """{"sentences":[{"id":2,"rows":["Ở một phía, ta có các CVE."]}]}""" }
            val translator = AiSubtitleTranslator(cacheDirectory = null)
            translator.translateAll(model, "en", "vi", translationTexts(rows))
            assertNull(translator.cachedTranslation(model, "en", "vi", sentence))
        }

    @Test fun aFailedRequestThrowsSoGoogleTakesOver() =
        runBlocking {
            val model = FakeModel { throw IOException("HTTP 429") }
            val translator = AiSubtitleTranslator(cacheDirectory = null)
            try {
                translator.translateAll(model, "en", "vi", translationTexts(rows))
                fail("expected a failure")
            } catch (_: IOException) {
            }
            assertNull(translator.cachedTranslation(model, "en", "vi", sentence))
        }

    @Test fun theSameLanguageSendsNothing() =
        runBlocking {
            val model = FakeModel { error("must not be asked") }
            AiSubtitleTranslator(cacheDirectory = null).translateAll(model, "en", "en", translationTexts(rows))
            assertTrue(model.requests.isEmpty())
        }

    @Test fun longWindowsSplitIntoSeveralRequests() {
        val sentences = List(100) { AiSentence("Sentence number $it.", listOf("Sentence number $it.")) }
        val batches = aiBatches(sentences)
        assertTrue(batches.size >= 2)
        assertTrue(batches.all { it.size <= MAX_AI_BATCH_SENTENCES })
        assertEquals(sentences, batches.flatten())
    }
}
