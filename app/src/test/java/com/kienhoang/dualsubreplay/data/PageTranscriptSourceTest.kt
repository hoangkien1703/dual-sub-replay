package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTranscriptSourceTest {
    @Test fun thePagePicksTheTrackTheDownloadWouldPick() =
        runBlocking {
            val source = PageTranscriptSource()
            val result = async { source.transcript(VIDEO, CaptionLanguageChoice(emptyList(), null)) }
            val request = awaitRequest(source)
            source.offerTracks(request, PAGE_TRACKS)
            val expected =
                selectCaptionTrack(
                    JSONObject(PAGE_TRACKS).getJSONObject("captions").getJSONObject("playerCaptionsTracklistRenderer"),
                    emptyList(),
                )
            assertEquals("zh-Hans", request.target?.languageCode)
            assertEquals(expected?.optString("languageCode"), request.target?.languageCode)
            assertTrue(request.target!!.generated)
            source.offerReply(request, PageTranscriptReply.Working)
            assertFalse(result.isCompleted)
            source.offerReply(request, PageTranscriptReply.Done(JSON3))
            val track = result.await()!!
            assertEquals("zh-Hans", track.languageCode)
            assertTrue(track.isGenerated)
            assertEquals(listOf("你好", "再见"), track.cues.map { it.text })
            assertEquals(listOf("en", "zh-Hans"), track.availableLanguages.map { it.code })
            assertNull("A finished request is closed", source.pending(VIDEO))
        }

    @Test fun anExplicitChoiceWinsOnThePageToo() =
        runBlocking {
            val source = PageTranscriptSource()
            val result = async { source.transcript(VIDEO, CaptionLanguageChoice(listOf("en"), null)) }
            val request = awaitRequest(source)
            source.offerTracks(request, PAGE_TRACKS)
            assertEquals("en", request.target?.languageCode)
            assertFalse(request.target!!.generated)
            source.offerReply(request, PageTranscriptReply.Failed("empty-with-token"))
            assertNull(result.await())
        }

    @Test fun anotherVideoOnThePageIsIgnored() =
        runBlocking {
            val source = PageTranscriptSource()
            val result = async { source.transcript(VIDEO, CaptionLanguageChoice(emptyList(), null)) }
            val request = awaitRequest(source)
            assertNull(source.pending("zzzzzzzzzzz"))
            source.offerTracks(request, PAGE_TRACKS.replace(VIDEO, "zzzzzzzzzzz"))
            assertNull(request.target)
            source.offerReply(request, PageTranscriptReply.Done(JSON3))
            assertFalse("No track was picked, so a document cannot be accepted", result.isCompleted)
            result.cancel()
        }

    @Test fun aVideoWithoutTracksEndsTheRequestAtOnce() =
        runBlocking {
            val source = PageTranscriptSource()
            val result = async { source.transcript(VIDEO, CaptionLanguageChoice(emptyList(), null)) }
            source.offerTracks(awaitRequest(source), """{"videoDetails":{"videoId":"$VIDEO"},"captions":null}""")
            assertNull(result.await())
        }

    @Test fun anUnreadableDocumentIsNoTranscript() =
        runBlocking {
            val source = PageTranscriptSource()
            val result = async { source.transcript(VIDEO, CaptionLanguageChoice(emptyList(), null)) }
            val request = awaitRequest(source)
            source.offerTracks(request, PAGE_TRACKS)
            source.offerReply(request, PageTranscriptReply.Done("<html>sorry</html>"))
            assertNull(result.await())
        }

    @Test fun aPageThatNeverAnswersTimesOut() =
        runBlocking {
            val source = PageTranscriptSource()
            assertNull(source.transcript(VIDEO, CaptionLanguageChoice(emptyList(), null), timeoutMs = 50))
            assertNull(source.pending(VIDEO))
        }

    @Test fun aNewRequestReplacesTheOldOne() =
        runBlocking {
            val source = PageTranscriptSource()
            val first = async { source.transcript(VIDEO, CaptionLanguageChoice(emptyList(), null)) }
            val old = awaitRequest(source)
            val second = async { source.transcript("bbbbbbbbbbb", CaptionLanguageChoice(emptyList(), null)) }
            val current = awaitRequest(source, "bbbbbbbbbbb")
            assertNull(first.await())
            source.offerTracks(old, PAGE_TRACKS)
            assertNull("Answers for a replaced request are ignored", old.target)
            assertSame(current, source.pending("bbbbbbbbbbb"))
            second.cancel()
        }

    @Test fun thePageWinsWhileTheDownloadIsStillWaiting() =
        runBlocking {
            val source = PageTranscriptSource()
            val download = FakeDownload { awaitCancellation() }
            val result = async { PageFirstCaptionProvider(source, download).fetch(VIDEO, emptyList(), null) }
            val request = awaitRequest(source)
            source.offerTracks(request, PAGE_TRACKS)
            source.offerReply(request, PageTranscriptReply.Done(JSON3))
            assertEquals("zh-Hans", result.await().languageCode)
            assertTrue("The download is stopped once the page wins", download.cancelled.await())
        }

    @Test fun theDownloadWinsWhenThePageCannotHelp() =
        runBlocking {
            val source = PageTranscriptSource()
            val gate = CompletableDeferred<Unit>()
            val result =
                async {
                    PageFirstCaptionProvider(
                        source,
                        FakeDownload {
                            gate.await()
                            DOWNLOADED
                        },
                    ).fetch(VIDEO, emptyList(), null)
                }
            source.offerReply(
                awaitRequest(source).also { source.offerTracks(it, PAGE_TRACKS) },
                PageTranscriptReply.Failed("no-page-request"),
            )
            gate.complete(Unit)
            assertSame(DOWNLOADED, result.await())
        }

    @Test fun aFastDownloadDoesNotWaitForThePage() =
        runBlocking {
            val source = PageTranscriptSource()
            val result = PageFirstCaptionProvider(source, FakeDownload { DOWNLOADED }).fetch(VIDEO, emptyList(), null)
            assertSame(DOWNLOADED, result)
            assertNull("The page request is closed", source.pending(VIDEO))
        }

    @Test fun whenBothFailTheDownloadsErrorStands() =
        runBlocking {
            val source = PageTranscriptSource()
            val failure = CaptionUnavailableException("This video does not provide a public caption track.")
            val download = FakeDownload { throw failure }
            val result = async { runCatching { PageFirstCaptionProvider(source, download).fetch(VIDEO, emptyList(), null) } }
            source.offerTracks(awaitRequest(source), """{"videoDetails":{"videoId":"$VIDEO"}}""")
            // Coroutines may hand back a copy of the exception with a recovered stack trace.
            val error = result.await().exceptionOrNull()
            assertTrue(error is CaptionUnavailableException)
            assertEquals(failure.message, error?.message)
        }

    private suspend fun awaitRequest(
        source: PageTranscriptSource,
        videoId: String = VIDEO,
    ): PageTranscriptSource.Request {
        repeat(200) {
            source.pending(videoId)?.let { return it }
            yield()
        }
        throw AssertionError("No page request for $videoId")
    }

    private class FakeDownload(
        private val body: suspend () -> CaptionTrackResult,
    ) : CaptionProvider {
        val cancelled = CompletableDeferred<Boolean>()

        override suspend fun fetch(
            videoId: String,
            preferredLanguages: List<String>,
            learningLanguage: String?,
        ): CaptionTrackResult =
            try {
                body()
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                cancelled.complete(true)
                throw cancel
            }
    }

    private companion object {
        const val VIDEO = "abcdefghijk"
        val DOWNLOADED = CaptionTrackResult("en", false, listOf(RawCaptionCue(0, 1000, "Hello")))
        val PAGE_TRACKS =
            """
            {"videoDetails":{"videoId":"$VIDEO","title":"每天中文","shortDescription":""},
             "captions":{"playerCaptionsTracklistRenderer":{
               "captionTracks":[
                 {"baseUrl":"https://www.youtube.com/api/timedtext?v=$VIDEO&lang=en&exp=xpe","languageCode":"en","name":{"simpleText":"English"}},
                 {"baseUrl":"https://www.youtube.com/api/timedtext?v=$VIDEO&lang=zh-Hans&kind=asr&exp=xpe","languageCode":"zh-Hans","kind":"asr","vssId":"a.zh-Hans","name":{"simpleText":"Chinese (auto-generated)"}}
               ],
               "audioTracks":[],"defaultAudioTrackIndex":0}}}
            """.trimIndent()
        val JSON3 =
            """{"events":[{"tStartMs":0,"dDurationMs":1500,"segs":[{"utf8":"你好"}]},{"tStartMs":2000,"dDurationMs":1500,"segs":[{"utf8":"再见"}]}]}"""
    }
}
