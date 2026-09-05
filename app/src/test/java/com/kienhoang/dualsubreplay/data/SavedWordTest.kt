package com.kienhoang.dualsubreplay.data

import org.junit.Assert.*
import org.junit.Test

class SavedWordTest {
    private val selection = LearningWordSelection(AnalyzedToken("Learn", 0, 5, PartOfSpeech.VERB), "en", "vi",
        "dQw4w9WgXcQ", SubtitleSegment(1, 1250, 4250, "Learn a word", "Học một từ"), false)
    private val card = savedWordFrom(selection, "học", true, false)

    @Test fun identityIncludesLanguageAndExampleButNotMeaningOrClipChoices() {
        assertEquals(card.id, savedWordFrom(selection.copy(token = selection.token.copy(text = "learn")), "new meaning", false, true).id)
        assertNotEquals(card.id, savedWordFrom(selection.copy(wordLanguage = "de"), "học", true, false).id)
        assertNotEquals(card.id, savedWordFrom(selection.copy(segment = selection.segment!!.copy(startMs = 1500)), "học", true, false).id)
        assertTrue(card.id.matches(Regex("[a-f0-9]{64}")))
    }
    @Test fun allFourClipChoicesAreIndependentAndInvalidContextCannotCreateClip() {
        for (online in listOf(true, false)) for (offline in listOf(true, false)) {
            val saved = savedWordFrom(selection, "học", online, offline)
            assertEquals(online, saved.online); assertEquals(offline, saved.offline)
        }
        val invalid = savedWordFrom(selection.copy(videoId = "../invalid"), "học", true, true)
        assertFalse(invalid.online); assertFalse(invalid.offline)
        assertFalse(validClipRange(selection.videoId, 100, 100))
        assertFalse(validClipRange(selection.videoId, -1, 100))
    }
    @Test fun reviewRatingsScheduleFromInjectedTimeAndAgainRestartsProgression() {
        val now = 1_000_000L
        assertEquals(now + 600_000, reviewWord(card, ReviewRating.AGAIN, now).dueAt)
        assertEquals(now + DAY_MS, reviewWord(card, ReviewRating.HARD, now).dueAt)
        assertEquals(now + 3 * DAY_MS, reviewWord(card, ReviewRating.GOOD, now).dueAt)
        assertEquals(now + 7 * DAY_MS, reviewWord(card, ReviewRating.EASY, now).dueAt)
        val reviewed = reviewWord(card, ReviewRating.GOOD, now)
        assertEquals(6 * DAY_MS, reviewWord(reviewed, ReviewRating.GOOD, now).intervalMs)
        val lapsed = reviewWord(reviewed, ReviewRating.AGAIN, now)
        assertEquals(3 * DAY_MS, reviewWord(lapsed, ReviewRating.GOOD, now).intervalMs)
    }
    @Test fun savedDataRoundTripsWithReviewAndDownloadState() {
        val saved = card.copy(clipStatus = "ready", clipGeneration = 4, offline = true, intervalMs = 99, dueAt = 123)
        assertEquals(saved, decodeWord(encodeWord(saved)))
        assertEquals(card.copy(videoId = null, reading = null, translatedSentence = null),
            decodeWord(encodeWord(card.copy(videoId = null, reading = null, translatedSentence = null))))
    }
    @Test fun downloadUsesExactSecondsAndNeverExpandsIntoPlaylist() {
        val arguments = clipDownloadArguments(ClipRequest(selection.videoId!!, 1250, 4250, "request"), java.io.File("clip.%(ext)s"))
        assertEquals("*1.250-4.250", arguments.toMap()["--download-sections"])
        assertTrue(arguments.any { it.first == "--no-playlist" })
        assertTrue(arguments.any { it.first == "--force-keyframes-at-cuts" })
    }
}
