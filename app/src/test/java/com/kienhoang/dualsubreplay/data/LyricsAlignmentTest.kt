package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Playlist songs placed by matching their free lyrics against YouTube's auto-generated captions
 * (owner pick 2026-11-24): the captions get many sung words wrong, and a cover may be slower.
 */
class LyricsAlignmentTest {
    private val vocabulary =
        (
            "love night heart fire light dream city river shadow golden summer rain dance broken stars ocean " +
                "whisper morning letter silver window promise highway thunder echo velvet garden mirror honey " +
                "wild young forever tonight lonely sweet cold paper diamond desert midnight angel ghost"
        ).split(" ")

    /** A song of [lines] lines of six words, one line every four seconds from 10 s. */
    private fun song(
        seed: Int,
        lines: Int = 24,
    ): List<RawCaptionCue> {
        val random = Random(seed)
        return (0 until lines).map { line ->
            val start = 10_000L + line * 4_000L
            RawCaptionCue(start, start + 3_500L, (1..6).joinToString(" ") { vocabulary[random.nextInt(vocabulary.size)] })
        }
    }

    /**
     * The video's auto-generated captions: [song] sung from [atMs] at [pace], with [wrong] of its words
     * misheard, cut into 3-second caption lines that don't follow the lyric lines; [noise] other
     * captions around it.
     */
    private fun captions(
        song: List<RawCaptionCue>,
        atMs: Long,
        pace: Double = 1.0,
        wrong: Double = 0.35,
        noise: List<RawCaptionCue> = emptyList(),
    ): List<RawCaptionCue> {
        val random = Random(7)
        val words =
            song.flatMap { cue ->
                val parts = cue.text.split(" ")
                parts.mapIndexed { index, word ->
                    val at = atMs + ((cue.startMs + (cue.endMs - cue.startMs) * index / parts.size) * pace).toLong()
                    at to (if (random.nextDouble() < wrong) "uh${random.nextInt(1000)}" else word)
                }
            }
        val sung =
            words.chunked(5).map { chunk ->
                RawCaptionCue(chunk.first().first, chunk.first().first + 3_000L, chunk.joinToString(" ") { it.second })
            }
        return (noise + sung).sortedBy { it.startMs }
    }

    @Test
    fun aSongIsFoundWhereTheCaptionsSingItEvenWithMisheardWords() {
        val lyrics = song(1)
        val other = song(2).map { it.copy(startMs = it.startMs + 1_000_000, endMs = it.endMs + 1_000_000) }
        val placed = alignLyrics(lyrics, captions(lyrics, atMs = 600_000L, noise = other))!!
        assertNear(610_000L, placed.cues.first().startMs)
        assertNear(610_000L + 23 * 4_000L, placed.cues.last().startMs)
        assertEquals(lyrics.map { it.text }, placed.cues.map { it.text })
        assertTrue("The song starts where its first line is sung", abs(placed.startMs - 610_000L) <= 1_500L)
    }

    @Test
    fun aSlowerCoverStillLinesUp() {
        val lyrics = song(3)
        val placed = alignLyrics(lyrics, captions(lyrics, atMs = 1_200_000L, pace = 1.15))!!
        // Line 12 is sung 15 % later into the song than on the record.
        assertNear(1_200_000L + ((10_000L + 12 * 4_000L) * 1.15).toLong(), placed.cues[12].startMs)
        assertNear(1_200_000L + ((10_000L + 23 * 4_000L) * 1.15).toLong(), placed.cues.last().startMs)
    }

    @Test
    fun aSongTheCaptionsDontSingIsNotPlaced() {
        val lyrics = song(4)
        assertNull(alignLyrics(lyrics, captions(song(5), atMs = 300_000L)))
        // Almost every word misheard: too few anchors to be sure.
        assertNull(alignLyrics(lyrics, captions(lyrics, atMs = 300_000L, wrong = 0.97)))
        assertNull(alignLyrics(lyrics, emptyList()))
    }

    @Test
    fun onlyTheGivenStretchOfTheVideoIsSearched() {
        val lyrics = song(6)
        val all = captions(lyrics, atMs = 900_000L)
        assertNull(alignLyrics(lyrics, all, fromMs = 0L, toMs = 600_000L))
        assertNear(910_000L, alignLyrics(lyrics, all, fromMs = 600_000L, toMs = 1_200_000L)!!.cues.first().startMs)
    }

    @Test
    fun overlappingSongsKeepTheBetterMatch() {
        val a = LyricChapter(0, 200_000, List(30) { RawCaptionCue(it * 5_000L, it * 5_000L + 4_000L, "a") })
        val b = LyricChapter(150_000, 400_000, List(10) { RawCaptionCue(150_000L + it * 5_000L, 154_000L + it * 5_000L, "b") })
        val c = LyricChapter(197_000, 380_000, List(20) { RawCaptionCue(197_000L + it * 5_000L, 201_000L + it * 5_000L, "c") })
        assertEquals(listOf(a, c), withoutOverlaps(listOf(c, b, a)))
    }

    @Test
    fun wordsCompareWithoutCaseOrPunctuationAndHanCharactersCountAlone() {
        assertEquals(listOf("dont", "stop", "me", "now"), matchWords("Don't stop me, NOW!"))
        assertEquals(listOf("我", "爱", "你", "baby"), matchWords("我爱你 baby"))
    }

    @Test
    fun aPlaylistDescriptionListsItsSongsWithoutTimes() {
        val description =
            """
            🎧 Top 30 Hits 2026
            Tracklist:
            01. The Fate of Ophelia - Taylor Swift
            02. Die on this hill - Sienna Spiro
            03) Bruno Mars – Die With A Smile
            #4 Olivia Dean - So Easy
            Follow us on Instagram: https://instagram.com/x
            #spotify #playlist
            """.trimIndent()
        assertEquals(
            listOf(
                "The Fate of Ophelia - Taylor Swift",
                "Die on this hill - Sienna Spiro",
                "Bruno Mars – Die With A Smile",
                "Olivia Dean - So Easy",
            ),
            descriptionSongList(description),
        )
        assertEquals(emptyList<String>(), descriptionSongList("#countrymusic #spotifyplaylist #englishsongs\nJust vibes - enjoy"))
        // Lines with times are chapters, read by descriptionChapters instead.
        assertEquals(emptyList<String>(), descriptionSongList("0:00 A - B\n3:10 C - D\n6:20 E - F"))
    }

    @Test
    fun aChapterNamingItsArtistInBracketsIsSearchedWithIt() {
        assertEquals("2002 - Anne-Marie", chapterSongName("2002 (Anne-Marie)"))
        assertEquals("Fate of Ophelia - Taylor Swift", chapterSongName("Fate of Ophelia (Taylor Swift)"))
        assertEquals("Golden (Remix)", chapterSongName("Golden (Remix)"))
        assertEquals("Havana (feat. Young Thug)", chapterSongName("Havana (feat. Young Thug)"))
        assertEquals("Camila Cabello - Havana (Live)", chapterSongName("Camila Cabello - Havana (Live)"))
    }

    private fun assertNear(
        expected: Long,
        actual: Long,
    ) = assertTrue("expected about $expected, was $actual", abs(expected - actual) <= 2_500L)
}
