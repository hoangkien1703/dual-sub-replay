package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compilations: each song in the description gets its own synced lyrics (owner pick 2026-11-17). */
class SongChaptersTest {
    private val description =
        """
        Best chill songs 2026 🎧
        Tracklist:
        00:00 1. Ed Sheeran - Perfect
        04:23 Lewis Capaldi – Someone You Loved
        [07:25] Shape of You | Ed Sheeran
        1:01:10 - Last Song
        Follow us on Instagram!
        """.trimIndent()

    @Test
    fun readsTheSongsFromTheDescription() {
        val chapters = descriptionChapters(description, 3_900_000)
        assertEquals(
            listOf(
                SongChapter(0, 263_000, "Ed Sheeran - Perfect"),
                SongChapter(263_000, 445_000, "Lewis Capaldi – Someone You Loved"),
                SongChapter(445_000, 3_670_000, "Shape of You | Ed Sheeran"),
                SongChapter(3_670_000, 3_900_000, "Last Song"),
            ),
            chapters,
        )
    }

    @Test
    fun songsListedPastTheVideosEndAreLeftOut() {
        // The owner's playlist (2026-11-25) is 28:28 long but lists a song at 30:46.
        val description =
            "00:00 - 2002 (Anne-Marie)\n03:07 - Golden (Harry Styles)\n" +
                "27:46 - Fate of Ophelia (Taylor Swift)\n30:46 - i wasn't enough"
        assertEquals(
            listOf(
                SongChapter(0, 187_000, "2002 (Anne-Marie)"),
                SongChapter(187_000, 1_666_000, "Golden (Harry Styles)"),
                SongChapter(1_666_000, 1_708_000, "Fate of Ophelia (Taylor Swift)"),
            ),
            descriptionChapters(description, 1_708_000),
        )
    }

    @Test
    fun aDescriptionWithoutAListOfSongsHasNoChapters() {
        // Too few, not from the start, out of order, or past the end.
        assertTrue(descriptionChapters("00:00 Intro\n01:00 Song", 300_000).isEmpty())
        assertTrue(descriptionChapters("00:30 A\n01:00 B\n02:00 C", 300_000).isEmpty())
        assertTrue(descriptionChapters("00:00 A\n02:00 B\n01:00 C", 300_000).isEmpty())
        assertTrue(descriptionChapters("00:00 A\n02:00 B\n09:00 C", 300_000).isEmpty())
        assertTrue(descriptionChapters("Just a song, released 2024", 300_000).isEmpty())
    }

    @Test
    fun lookupsStartAtThePlayingSong() {
        val chapters = descriptionChapters(description, 3_900_000)
        assertEquals(listOf(445_000L, 3_670_000L, 0L, 263_000L), chaptersFromPlaying(chapters, 500_000).map { it.startMs })
        assertEquals(chapters, chaptersFromPlaying(chapters, 0))
    }

    @Test
    fun lyricsMoveToTheSongsStartAndStopAtItsEnd() {
        val lyrics =
            SyncedLyrics(
                "Perfect",
                "Ed Sheeran",
                263_000,
                listOf(RawCaptionCue(1_000, 3_000, "I found a love"), RawCaptionCue(9_000, 12_000, "Too late")),
            )
        val moved = lyricChapter(SongChapter(100_000, 110_000, "Ed Sheeran - Perfect"), lyrics)
        assertEquals(listOf(RawCaptionCue(101_000, 103_000, "I found a love"), RawCaptionCue(109_000, 110_000, "Too late")), moved.cues)
    }

    @Test
    fun geminiAsksOnlyForThePartsNoSongCovers() {
        val songs =
            listOf(
                LyricChapter(0, 400_000, listOf(RawCaptionCue(1_000, 2_000, "Song one"))),
                LyricChapter(400_000, 640_000, listOf(RawCaptionCue(401_000, 402_000, "Song two"))),
            )
        val track = AiSubtitleTrack("aaaaaaaaaaa", "en", emptyMap(), endMs = 900_000, lyricChapters = songs)
        // 0–5:00 is covered whole; 5:00–10:00 is, too (songs run to 10:40); 10:00–15:00 is not.
        assertEquals(setOf(0L, 300_000L), track.coveredParts())
        assertTrue(track.hasGaps())
        // Songs cover 10:40 of 15:00, so the header says song lyrics.
        assertTrue(track.mostlyLyrics())
        assertFalse(track.copy(endMs = 1_400_000).mostlyLyrics())
        val geminiLine = RawCaptionCue(390_000, 391_000, "Gemini inside song one")
        val later = RawCaptionCue(700_000, 701_000, "Gemini after the songs")
        val filled = track.copy(parts = mapOf(300_000L to listOf(geminiLine), 600_000L to listOf(later)))
        assertEquals(listOf("Song one", "Song two", "Gemini after the songs"), filled.cues().map { it.text })
        assertFalse(filled.hasGaps())
        val whole = track.copy(endMs = 640_000)
        assertEquals(setOf(0L, 300_000L, 600_000L), whole.coveredParts())
        assertFalse(whole.hasGaps())
        assertTrue(whole.mostlyLyrics())
        assertEquals(songs, decodeAiSubtitleTrack(encodeAiSubtitleTrack(whole))!!.lyricChapters)
    }

    @Test
    fun descriptionsAreRememberedForTheLastFewVideos() {
        VideoDescriptions.remember("bbbbbbbbbbb", "00:00 A")
        assertEquals("00:00 A", VideoDescriptions.of("bbbbbbbbbbb"))
        (0 until 10).forEach { VideoDescriptions.remember("video$it", "text") }
        assertNull(VideoDescriptions.of("bbbbbbbbbbb"))
    }
}
