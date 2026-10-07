package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecentCaptionTracksTest {
    private val track =
        CaptionTrackResult(
            languageCode = "ja",
            isGenerated = true,
            cues =
                listOf(
                    RawCaptionCue(0, 1_200, "こんにちは \"世界\"", listOf(SubtitleWord("こんにちは", 0, 600), SubtitleWord("世界", 600, 1_200))),
                    RawCaptionCue(1_500, 2_000, "No words\nsecond line"),
                ),
            availableLanguages = listOf(CaptionLanguage("ja", "Japanese"), CaptionLanguage("en", "English")),
        )

    @Test
    fun reopeningAfterTheAppWasClosedReusesTheTrackWithoutDownloading() =
        withDirectory { directory ->
            val network = CountingProvider(track)
            runBlocking { RecentCaptionTracks(network, directory).fetch("video1", listOf("ja", "en")) }
            // A new instance is what a restarted app process creates.
            val restored = runBlocking { RecentCaptionTracks(network, directory).fetch("video1", listOf("ja", "en")) }
            assertEquals(track, restored)
            assertEquals(1, network.calls)
        }

    @Test
    fun anotherVideoOrLanguageChoiceStillDownloads() =
        withDirectory { directory ->
            val network = CountingProvider(track)
            val tracks = RecentCaptionTracks(network, directory)
            runBlocking {
                tracks.fetch("video1", listOf("ja"))
                tracks.fetch("video2", listOf("ja"))
                tracks.fetch("video1", listOf("en"))
            }
            assertEquals(3, network.calls)
        }

    @Test
    fun anExpiredTrackIsDownloadedAgain() =
        withDirectory { directory ->
            val network = CountingProvider(track)
            var now = 1_000L
            val tracks = RecentCaptionTracks(network, directory, maxAgeMs = 60_000, now = { now })
            runBlocking { tracks.fetch("video1", listOf("ja")) }
            now += 60_001
            runBlocking { tracks.fetch("video1", listOf("ja")) }
            assertEquals(2, network.calls)
        }

    @Test
    fun aFailedDownloadIsNotRemembered() =
        withDirectory { directory ->
            val network = CountingProvider(track, failures = 1)
            val tracks = RecentCaptionTracks(network, directory)
            val failure = runCatching { runBlocking { tracks.fetch("video1", listOf("ja")) } }.exceptionOrNull()
            assertTrue(failure is CaptionUnavailableException)
            assertEquals(track, runBlocking { tracks.fetch("video1", listOf("ja")) })
            assertEquals(2, network.calls)
        }

    @Test
    fun aDamagedEntryIsReplacedByADownload() =
        withDirectory { directory ->
            val network = CountingProvider(track)
            runBlocking { RecentCaptionTracks(network, directory).fetch("video1", listOf("ja")) }
            directory.listFiles()!!.single().writeText("{\"version\":1,\"cues\":[")
            val tracks = RecentCaptionTracks(network, directory)
            assertEquals(track, runBlocking { tracks.fetch("video1", listOf("ja")) })
            assertEquals(track, runBlocking { tracks.fetch("video1", listOf("ja")) })
            assertEquals(2, network.calls)
        }

    @Test
    fun onlyTheMostRecentlyUsedTracksAreKept() =
        withDirectory { directory ->
            val network = CountingProvider(track)
            var now = 1_000L
            val tracks = RecentCaptionTracks(network, directory, maxEntries = 2, now = { now++ })
            runBlocking {
                tracks.fetch("a", listOf("ja"))
                tracks.fetch("b", listOf("ja"))
                tracks.fetch("a", listOf("ja")) // Reuse makes "a" the most recent.
                tracks.fetch("c", listOf("ja"))
            }
            assertEquals(3, network.calls)
            assertEquals(2, directory.listFiles()!!.size)
            runBlocking { tracks.fetch("a", listOf("ja")) }
            assertEquals(3, network.calls)
            runBlocking { tracks.fetch("b", listOf("ja")) }
            assertEquals(4, network.calls)
        }

    @Test
    fun aTrackLargerThanTheLimitIsNotStored() =
        withDirectory { directory ->
            val network = CountingProvider(track)
            val tracks = RecentCaptionTracks(network, directory, maxBytes = 64)
            runBlocking {
                tracks.fetch("video1", listOf("ja"))
                tracks.fetch("video1", listOf("ja"))
            }
            assertEquals(2, network.calls)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun aDifferentLearningLanguageIsADifferentEntry() =
        withDirectory { directory ->
            val network = CountingProvider(track)
            val tracks = RecentCaptionTracks(network, directory)
            runBlocking {
                tracks.fetch("video1", emptyList(), "ja")
                tracks.fetch("video1", emptyList(), "ja")
                tracks.fetch("video1", emptyList(), "ko")
            }
            assertEquals(2, network.calls)
        }

    private class CountingProvider(
        private val track: CaptionTrackResult,
        private var failures: Int = 0,
    ) : CaptionProvider {
        var calls = 0

        override suspend fun fetch(
            videoId: String,
            preferredLanguages: List<String>,
            learningLanguage: String?,
        ): CaptionTrackResult {
            calls += 1
            if (failures > 0) {
                failures -= 1
                throw CaptionUnavailableException("No captions right now")
            }
            return track
        }
    }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("recent-captions").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
