package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SoundCuesTest {
    @Test
    fun removesBracketedDescriptionsAndKeepsTheSpeech() {
        assertEquals("", SoundCues.strip("[Music]"))
        assertEquals("", SoundCues.strip("[Applause] [Laughter]"))
        assertEquals("Thank you so much.", SoundCues.strip("[Applause] Thank you so much."))
        assertEquals("Thank you.", SoundCues.strip("Thank you [applause]."))
        assertEquals("Welcome back.", SoundCues.strip("[DOOR CLOSES]: Welcome back."))
        assertEquals("", SoundCues.strip("［音楽］"))
    }

    @Test
    fun removesParenthesesOnlyWhenTheyNameASound() {
        assertEquals("That was great.", SoundCues.strip("(laughs) That was great."))
        assertEquals("Okay.", SoundCues.strip("(audience cheering) Okay."))
        assertEquals("Hello.", SoundCues.strip("(SIGHS) Hello."))
        assertEquals("", SoundCues.strip("（拍手）"))
        assertEquals("It's (I think) the best one.", SoundCues.strip("It's (I think) the best one."))
        assertEquals("We moved here (since 2010).", SoundCues.strip("We moved here (since 2010)."))
        assertEquals("Learn it (in English).", SoundCues.strip("Learn it (in English)."))
    }

    @Test
    fun keepsLyricsWithoutTheirMusicNotes() {
        assertEquals("Let it go, let it go", SoundCues.strip("♪ Let it go, let it go ♪"))
        assertEquals("", SoundCues.strip("♪♪"))
        assertEquals("", SoundCues.strip("♪ ~ ♪"))
    }

    @Test
    fun leavesOrdinaryLinesExactlyAsTheyWere() {
        val line = "  Plain   line, no cues.  "
        assertSame(line, SoundCues.strip(line))
        assertEquals("...", SoundCues.strip("..."))
    }

    @Test
    fun dropsCuesThatHeldOnlyASoundAndTheirTimedWords() {
        val cues =
            listOf(
                RawCaptionCue(0, 1_000, "[Music]", listOf(SubtitleWord("[Music]", 0, 1_000))),
                RawCaptionCue(
                    1_000,
                    3_000,
                    "[Bird chirping] hello there",
                    listOf(
                        SubtitleWord("[Bird", 1_000, 1_300),
                        SubtitleWord(" chirping]", 1_300, 1_600),
                        SubtitleWord(" hello", 1_600, 2_200),
                        SubtitleWord(" there", 2_200, 3_000),
                    ),
                ),
                RawCaptionCue(
                    3_000,
                    4_000,
                    "♪ la la ♪",
                    listOf(SubtitleWord("♪", 3_000, 3_200), SubtitleWord(" la", 3_200, 3_600), SubtitleWord(" la", 3_600, 4_000)),
                ),
            )

        val filtered = SoundCues.filter(cues)

        assertEquals(listOf("hello there", "la la"), filtered.map { it.text })
        assertEquals(listOf(" hello", " there"), filtered[0].words.map { it.text })
        assertEquals(listOf(" la", " la"), filtered[1].words.map { it.text })
    }

    @Test
    fun mergedSubtitlesNoLongerShowTheCues() {
        val cues =
            listOf(
                RawCaptionCue(0, 2_000, "[Music]"),
                RawCaptionCue(2_000, 4_000, "Good morning everyone."),
                RawCaptionCue(4_000, 5_000, "[Applause]"),
            )

        val segments = SubtitleMerger.merge(SoundCues.filter(cues))

        assertEquals(listOf("Good morning everyone."), segments.map { it.originalText })
    }
}
