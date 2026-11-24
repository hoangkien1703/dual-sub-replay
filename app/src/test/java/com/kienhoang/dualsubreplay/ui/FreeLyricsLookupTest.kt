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

/** Free lyrics for a playlist that lists its songs only in its picture (owner pick 2026-11-24). */
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
    fun songsListedInThePictureAreFoundWhereTheCaptionsSingThem() =
        runBlocking {
            val picture =
                listOf(
                    "TOP HITS SPOTIFY",
                    "01. Risk It All — Bruno Mars",
                    "02. Ordinary — Alex warren",
                    "03. Opalite — Taylor Swift",
                    "Risk It All — Bruno Mars .01",
                    "Ordinary — Alex warren .02",
                    "Opalite — Taylor Swift .03",
                    "JS.VIBES MUSIC",
                )
            val searched = mutableListOf<String>()
            val lookup =
                FreeLyricsLookup(search = { title, _ -> replies[title].also { searched += title } }, pictureText = { picture })
            val found = lookup.find(video()) as FreeLyrics.Playlist
            assertEquals(3, found.total)
            assertEquals(replies.keys, searched.toSet())
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
    fun aDescriptionListComesBeforeThePicture() =
        runBlocking {
            var pictureRead = false
            val lookup =
                FreeLyricsLookup(search = { title, _ -> replies[title] }, pictureText = {
                    pictureRead = true
                    emptyList()
                })
            val description = "Tracklist:\n1. Risk It All — Bruno Mars\n2. Ordinary — Alex warren\n3. Opalite — Taylor Swift"
            val found = lookup.find(video(description = description)) as FreeLyrics.Playlist
            assertEquals(2, found.songs.size)
            assertTrue(!pictureRead)
        }

    @Test
    fun withoutCaptionsThePictureIsNotRead() =
        runBlocking {
            var pictureRead = false
            val lookup =
                FreeLyricsLookup(search = { _, _ -> null }, pictureText = {
                    pictureRead = true
                    emptyList()
                })
            assertEquals(FreeLyrics.None, lookup.find(video(captions = null)))
            assertTrue(!pictureRead)
        }

    private fun assertNear(
        expected: Long,
        actual: Long,
    ) = assertTrue("expected about $expected, was $actual", abs(expected - actual) <= 2_500L)
}
