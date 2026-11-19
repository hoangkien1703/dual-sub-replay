package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** TV-style live captions as ABC News Live's player drew them on 2026-11-19 (roll-up window, capitals). */
class LiveRollUpCaptionsTest {
    private var revision = 0L

    private fun window(
        text: String,
        rollUp: Boolean = true,
    ) = LiveCaptionSample(text, ++revision, 0, text.isNotBlank(), VIDEO, "en", rollUp)

    private fun RollUpCaptionAssembler.shown(
        text: String,
        nowMs: Long,
    ): String? = transform(window(text), nowMs)?.takeIf { it.present }?.text

    @Test fun otherCaptionsPassThroughUnchanged() {
        val assembler = RollUpCaptionAssembler()
        val sample = window("Hello world", rollUp = false)
        assertSame(sample, assembler.transform(sample, 0))
        assertNull(assembler.transform(null, 0))
    }

    @Test fun rollingWindowsBecomeOneFinishedSentenceAtATime() {
        val assembler = RollUpCaptionAssembler()
        assertNull("Nothing finished yet", assembler.shown("WORLD OVER A DECADE AGO WITH HER", 0))
        assertNull(assembler.shown("WORLD OVER A DECADE AGO WITH HER INFAMOUS ONE LINERS AND QUICK W", 400))
        assertNull("The last word may still be typing", assembler.shown("AGO WITH HER INFAMOUS ONE LINERS AND QUICK WIT.", 800))
        assertEquals(
            "World over a decade ago with her infamous one liners and quick wit.",
            assembler.shown("AGO WITH HER INFAMOUS ONE LINERS AND QUICK WIT. SHE", 1_200),
        )
        assertEquals(
            "The sentence stays while the next one is typed",
            "World over a decade ago with her infamous one liners and quick wit.",
            assembler.shown("ONE LINERS AND QUICK WIT. SHE SAID I", 1_600),
        )
        assertEquals("She said I did it nice.", assembler.shown("QUICK WIT. SHE SAID I DID IT NICE. >>", 2_000))
    }

    @Test fun aSpeakerMarkEndsTheSentenceBeforeIt() {
        val assembler = RollUpCaptionAssembler()
        assertNull(assembler.shown(">> HOW DO YOU REBUILD", 0))
        assertEquals("How do you rebuild", assembler.shown(">> HOW DO YOU REBUILD >> I THINK", 300))
    }

    @Test fun aPauseFinishesTheWindowWithoutAFullStop() {
        val assembler = RollUpCaptionAssembler()
        assertNull(assembler.shown("[MUSIC] BURDEN OF PROOF,", 0))
        assertNull(assembler.shown("[MUSIC] BURDEN OF PROOF,", ROLL_UP_PAUSE_MS - 1))
        assertEquals("[Music] burden of proof,", assembler.shown("[MUSIC] BURDEN OF PROOF,", ROLL_UP_PAUSE_MS))
        assertEquals(
            "The words still on screen are not repeated (names lose their capitals)",
            "Daily on abc news live.",
            assembler.shown("BURDEN OF PROOF, DAILY ON ABC NEWS LIVE. WHEREVER", ROLL_UP_PAUSE_MS + 400),
        )
    }

    @Test fun aLongSentenceIsShownBeforeItsFullStop() {
        val assembler = RollUpCaptionAssembler()
        val words = (1..ROLL_UP_MAX_SENTENCE_WORDS + 1).joinToString(" ") { "WORD$it" }
        val shown = assembler.shown(words, 0)!!
        assertTrue(shown.startsWith("Word1 word2"))
        assertFalse("The typing word waits", shown.endsWith("word${ROLL_UP_MAX_SENTENCE_WORDS + 1}"))
    }

    @Test fun aNewVideoOrASeekStartsOver() {
        val assembler = RollUpCaptionAssembler()
        assertEquals("Hello there.", assembler.shown("HELLO THERE. NEXT", 0))
        assertNull(assembler.transform(window("SOMETHING ELSE"), 100, seek = true)?.takeIf { it.present })
        assertEquals("Something else.", assembler.shown("SOMETHING ELSE. NEXT", 200))
        assertNull(assembler.transform(window("NOTHING FINISHED").copy(videoId = "zzzzzzzzzzz"), 300)?.takeIf { it.present })
    }

    @Test fun capitalsBecomeSentenceCaseOnlyWhenTheTextIsMostlyCapitals() {
        assertEquals("I think it's fine. Is it?", rollUpSentenceText(">> I THINK IT'S FINE. IS IT?", "en"))
        assertEquals("Mixed Case stays NASA", rollUpSentenceText("Mixed Case stays NASA", "en"))
        assertEquals("Ho visto i ragazzi.", rollUpSentenceText("HO VISTO I RAGAZZI.", "it"))
        assertEquals("日本語のテキスト", rollUpSentenceText("日本語のテキスト", "ja"))
    }

    @Test fun theLiveKeyTakesTheFinishedSentence() {
        val assembler = RollUpCaptionAssembler()
        val shown = assembler.transform(window("GOOD MORNING. WE"), 0)
        assertEquals("Good morning.", liveTranslationKey(shown, VIDEO, "vi")?.text)
        assertEquals("en", liveTranslationKey(shown, VIDEO, "vi")?.language)
    }

    private companion object {
        const val VIDEO = "KQhNZQ_Uag8"
    }
}
