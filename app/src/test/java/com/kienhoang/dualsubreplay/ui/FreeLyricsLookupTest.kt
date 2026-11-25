package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.RawCaptionCue
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** Free lyrics for a playlist video (owner picks 2026-11-24 and 2026-11-25). */
class FreeLyricsLookupTest {
    private val vocabulary =
        (
            "love night heart fire light dream city river shadow golden summer rain dance broken stars ocean " +
                "whisper morning letter silver window promise highway thunder echo velvet garden mirror honey"
        ).split(" ")

    /** A song's lines: 24 lines of six words, one every four seconds from 10 s. */
    private fun lines(seed: Int): List<Pair<Long, String>> {
        val random = Random(seed)
        return (0 until 24).map { line ->
            (10_000L + line * 4_000L) to
                (1..6).joinToString(" ") { vocabulary[random.nextInt(vocabulary.size)] }
        }
    }

    private fun lrc(lines: List<Pair<Long, String>>): String =
        lines.joinToString("\n") { (ms, text) -> "[%02d:%02d.%02d] %s".format(ms / 60_000, ms / 1000 % 60, ms % 1000 / 10, text) }

    /** LRCLIB's reply for one song of 3:30. */
    private fun reply(
        track: String,
        artist: String,
        lines: List<Pair<Long, String>>,
    ) = JSONArray()
        .put(
            JSONObject()
                .put("trackName", track)
                .put("artistName", artist)
                .put("duration", 210.0)
                .put("instrumental", false)
                .put("syncedLyrics", lrc(lines)),
        ).toString()

    /** Auto-generated captions singing [lines] from [atMs], five words a caption. */
    private fun sung(
        lines: List<Pair<Long, String>>,
        atMs: Long,
    ): List<RawCaptionCue> =
        lines
            .flatMap { (ms, text) -> text.split(" ").mapIndexed { index, word -> (atMs + ms + index * 500L) to word } }
            .chunked(5)
            .map { chunk -> RawCaptionCue(chunk.first().first, chunk.first().first + 3_000L, chunk.joinToString(" ") { it.second }) }

    private val first = lines(11)
    private val second = lines(12)
    private val third = lines(13)

    private val replies =
        mapOf(
            "Risk It All — Bruno Mars" to reply("Risk It All", "Bruno Mars", first),
            "Ordinary — Alex warren" to reply("Ordinary", "Alex Warren", second),
            "Opalite — Taylor Swift" to reply("Opalite", "Taylor Swift", third),
        )

    private fun video(
        description: String = "Best songs of the year. Subscribe for more!",
        captions: List<RawCaptionCue>? = sung(first, 300_000L) + sung(second, 720_000L),
    ) = VideoForLyrics("TOP HITS SPOTIFY 2026", "JS Vibes", 3_600_000L, description, captions, playingMs = 0L)

    @Test
    fun songsListedWithoutTimesAreFoundWhereTheCaptionsSingThem() =
        runBlocking {
            val searched = mutableListOf<String>()
            val lookup = FreeLyricsLookup(search = { title, _ -> replies[title].also { searched += title } })
            val description = "Tracklist:\n01. Risk It All — Bruno Mars\n02. Ordinary — Alex warren\n03. Opalite — Taylor Swift\n#pop #hits"
            val found = lookup.find(video(description = description)) as FreeLyrics.Playlist
            assertEquals(3, found.total)
            assertEquals(replies.keys, searched.toSet())
            // Opalite isn't sung in the captions, so it is left out rather than guessed.
            assertEquals(2, found.songs.size)
            assertNear(
                310_000L,
                found.songs[0]
                    .cues
                    .first()
                    .startMs,
            )
            assertNear(
                730_000L,
                found.songs[1]
                    .cues
                    .first()
                    .startMs,
            )
            assertEquals(first.map { it.second }, found.songs[0].cues.map { it.text })
        }

    @Test
    fun chaptersNamingTheArtistInBracketsAreFoundEvenWhenTheListRunsPastTheEnd() =
        runBlocking {
            // As in the owner's playlist (2026-11-25): "00:00 - 2002 (Anne-Marie)", and a last song the video was cut short of.
            val description =
                """
                Tracklist:
                00:00 - Risk It All (Bruno Mars)
                03:30 - Ordinary (Alex warren)
                07:00 - Opalite (Taylor Swift)
                10:30 - i wasn't enough (Elsie Music)
                """.trimIndent()
            val searched = mutableListOf<String>()
            val lookup = FreeLyricsLookup(search = { title, _ -> replies[title.replace(" - ", " — ")].also { searched += title } })
            val captions = sung(third, 420_000L)
            val found =
                lookup.find(
                    video(description = description, captions = captions).copy(durationMs = 600_000L),
                ) as FreeLyrics.Playlist
            assertEquals(3, found.total)
            assertEquals(setOf("Risk It All - Bruno Mars", "Ordinary - Alex warren", "Opalite - Taylor Swift"), searched.toSet())
            // The first two fit their chapters' length; the third (3:00 chapter, 3:30 song) is placed by the captions.
            assertEquals(3, found.songs.size)
            assertEquals(
                210_000L + 10_000L,
                found.songs[1]
                    .cues
                    .first()
                    .startMs,
            )
            assertNear(
                430_000L,
                found.songs[2]
                    .cues
                    .first()
                    .startMs,
            )
        }

    @Test
    fun withoutCaptionsAListWithoutTimesFindsNothing() =
        runBlocking {
            val lookup = FreeLyricsLookup(search = { title, _ -> replies[title] })
            val description = "Tracklist:\n01. Risk It All — Bruno Mars\n02. Ordinary — Alex warren\n03. Opalite — Taylor Swift"
            assertEquals(FreeLyrics.None, lookup.find(video(description = description, captions = null)))
        }

    @Test
    fun aSlowedEditGetsItsSongsLyricsStretched() =
        runBlocking {
            // "[Lyrics + Vietsub] double take - dhruv (slowed)" is 3:06; the song is 2:50 (owner's report 2026-11-25).
            val lookup = FreeLyricsLookup(search = { _, _ -> reply("double take", "dhruv", first) })
            val slowed = VideoForLyrics("double take - dhruv (slowed + reverb)", "1106", 231_000L, "", captions = null, playingMs = 0L)
            val found = lookup.find(slowed) as FreeLyrics.Song
            // 3:51 for a 3:30 song: every time 10 % later.
            assertEquals(11_000L, found.cues.first().startMs)
            assertEquals(first.map { it.second }, found.cues.map { it.text })
            // Without "slowed" or "sped up" in the title, a song of another length is not guessed.
            assertEquals(FreeLyrics.None, lookup.find(slowed.copy(title = "double take - dhruv")))
        }

    private fun assertNear(
        expected: Long,
        actual: Long,
    ) = assertTrue("expected about $expected, was $actual", abs(expected - actual) <= 2_500L)
}
