package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
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
            source.offerReply(request, PageTranscriptReply.Working("page-address"))
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
            result.cancel()
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

    @Test fun aVideoWithoutTracksStopsTheWaitButKeepsWatching() =
        runBlocking {
            var now = 0L
            val source = PageTranscriptSource(clock = { now })
            val result = async { source.transcript(VIDEO, CaptionLanguageChoice(emptyList(), null)) }
            val request = awaitRequest(source)
            source.offerTracks(request, NO_TRACKS)
            now = PAGE_NO_TRACKS_GRACE_MS - 1
            source.offerTracks(request, NO_TRACKS)
            yield()
            assertFalse("The player may still list its tracks", result.isCompleted)
            now = PAGE_NO_TRACKS_GRACE_MS
            source.offerTracks(request, NO_TRACKS)
            assertNull(result.await())
            assertEquals(PageTranscriptProblem.NO_TRACKS, source.problem(VIDEO))
            assertEquals(
                "player details 0 tracks, captions menu 0, downloaded 0, playability LOGIN_REQUIRED",
                source.problemDetail(VIDEO),
            )
            val watching = source.pending(VIDEO)!!
            assertTrue("The page keeps watching the same video", watching.late)
            assertTrue("and goes to YouTube's transcript panel", watching.target!!.panelOnly)
            assertEquals("", watching.target!!.baseUrl)
        }

    @Test fun captionsThePlayerDownloadedGiveTheTranscriptWhenItsDetailsListNone() =
        runBlocking {
            val source = PageTranscriptSource()
            val result = async { source.transcript(VIDEO, CaptionLanguageChoice(emptyList(), "en")) }
            val request = awaitRequest(source)
            source.offerTracks(request, FROM_PLAYER)
            val target = request.target!!
            assertEquals("en", target.languageCode)
            assertTrue(target.generated)
            assertEquals("No address to try: the page waits for the player's own download", "", target.baseUrl)
            source.offerReply(request, PageTranscriptReply.Done(JSON3, "en", true))
            assertEquals(listOf("你好", "再见"), result.await()!!.cues.map { it.text })
        }

    @Test fun theTranscriptPanelGivesALateTranscriptInTheLinesOwnLanguage() =
        runBlocking {
            var now = 0L
            val source = PageTranscriptSource(clock = { now })
            val choice = CaptionLanguageChoice(emptyList(), null)
            val result = async { source.transcript(VIDEO, choice) }
            val request = awaitRequest(source)
            source.offerTracks(request, NO_TRACKS)
            now = PAGE_NO_TRACKS_GRACE_MS
            source.offerTracks(request, NO_TRACKS)
            assertNull(result.await())
            val announced = async(start = CoroutineStart.UNDISPATCHED) { source.lateTranscripts.first() }
            source.offerReply(source.pending(VIDEO)!!, PageTranscriptReply.Done(PANEL_LINES, "en", false, panel = true))
            assertEquals(VIDEO, announced.await())
            val track = source.transcript(VIDEO, choice, timeoutMs = 50)!!
            assertEquals(listOf("今日は", "いい天気ですね"), track.cues.map { it.text })
            assertEquals("The guess gives way to the lines' writing system", "ja", track.languageCode)
        }

    @Test fun anUntrustedTrackAddressIsNotUsedButTheTrackIs() =
        runBlocking {
            val source = PageTranscriptSource()
            val result = async { source.transcript(VIDEO, CaptionLanguageChoice(listOf("en"), null)) }
            val request = awaitRequest(source)
            source.offerTracks(
                request,
                PAGE_TRACKS.replace(
                    "https://www.youtube.com/api/timedtext?v=$VIDEO&lang=en",
                    "https://example.com/api/timedtext?v=$VIDEO&lang=en",
                ),
            )
            assertEquals("en", request.target?.languageCode)
            assertEquals("", request.target?.baseUrl)
            result.cancel()
        }

    @Test fun anUnreadableDocumentIsNoTranscript() =
        runBlocking {
            val source = PageTranscriptSource()
            val result = async { source.transcript(VIDEO, CaptionLanguageChoice(emptyList(), null)) }
            val request = awaitRequest(source)
            source.offerTracks(request, PAGE_TRACKS)
            source.offerReply(request, PageTranscriptReply.Done("<html>sorry</html>"))
            assertNull(result.await())
            assertEquals(PageTranscriptProblem.UNREADABLE, source.problem(VIDEO))
        }

    @Test fun aPageThatNeverAnswersTimesOutButKeepsWatching() =
        runBlocking {
            val source = PageTranscriptSource()
            assertNull(source.transcript(VIDEO, CaptionLanguageChoice(emptyList(), null), timeoutMs = 50))
            assertEquals(PageTranscriptProblem.NO_ANSWER, source.problem(VIDEO))
            assertTrue("The page keeps watching the same video", source.pending(VIDEO)!!.late)
            assertNull(source.problem("bbbbbbbbbbb"))
        }

    @Test fun captionsThePageLoadsLaterAreAnnouncedAndKeptForTheNextRequest() =
        runBlocking {
            val source = PageTranscriptSource()
            val choice = CaptionLanguageChoice(emptyList(), null)
            val first = async { source.transcript(VIDEO, choice, timeoutMs = 300) }
            val request = awaitRequest(source)
            source.offerTracks(request, PAGE_TRACKS)
            source.offerReply(request, PageTranscriptReply.Working("waiting-for-captions"))
            assertNull(first.await())
            assertEquals(PageTranscriptProblem.WAITING_FOR_CAPTIONS, source.problem(VIDEO))
            val announced = async(start = CoroutineStart.UNDISPATCHED) { source.lateTranscripts.first() }
            // Live subtitles turned the page's captions on; the player downloaded the track.
            source.offerReply(source.pending(VIDEO)!!, PageTranscriptReply.Done(JSON3, "zh-Hans", true))
            assertEquals(VIDEO, announced.await())
            assertNull(source.pending(VIDEO))
            val track = source.transcript(VIDEO, choice, timeoutMs = 50)!!
            assertEquals(listOf("你好", "再见"), track.cues.map { it.text })
            assertNull("The kept transcript is used once", source.transcript(VIDEO, choice, timeoutMs = 50))
        }

    @Test fun aRetryIsANewAttemptOnThePage() =
        runBlocking {
            val source = PageTranscriptSource()
            val choice = CaptionLanguageChoice(emptyList(), null)
            val first = async { source.transcript(VIDEO, choice, timeoutMs = 50) }
            source.offerTracks(awaitRequest(source), PAGE_TRACKS)
            assertNull(first.await())
            val watching = source.pending(VIDEO)!!
            val retry = async { source.transcript(VIDEO, choice) }
            while (source.pending(VIDEO) === watching) yield()
            val request = awaitRequest(source).also { source.offerTracks(it, PAGE_TRACKS) }
            assertTrue(request.target!!.attempt > watching.target!!.attempt)
            assertFalse(request.late)
            assertNull("A new attempt clears the old reason", source.problem(VIDEO))
            retry.cancel()
        }

    @Test fun thePageMayFindTheSameLanguageAsAnotherKind() =
        runBlocking {
            val source = PageTranscriptSource()
            val result = async { source.transcript(VIDEO, CaptionLanguageChoice(listOf("en"), null)) }
            val request = awaitRequest(source)
            source.offerTracks(request, PAGE_TRACKS)
            source.offerReply(request, PageTranscriptReply.Done(JSON3, "en", true))
            val track = result.await()!!
            assertEquals("en", track.languageCode)
            assertTrue(track.isGenerated)
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
            awaitRequest(source).also { source.offerTracks(it, PAGE_TRACKS) }
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
            var now = 0L
            val source = PageTranscriptSource(clock = { now })
            val failure = CaptionUnavailableException("This video does not provide a public caption track.")
            val download = FakeDownload { throw failure }
            val result = async { runCatching { PageFirstCaptionProvider(source, download).fetch(VIDEO, emptyList(), null) } }
            val request = awaitRequest(source)
            source.offerTracks(request, """{"videoDetails":{"videoId":"$VIDEO"}}""")
            now = PAGE_NO_TRACKS_GRACE_MS
            source.offerTracks(request, """{"videoDetails":{"videoId":"$VIDEO"}}""")
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
        val NO_TRACKS =
            """{"videoDetails":{"videoId":"$VIDEO"},"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[]}},""" +
                """"found":{"details":0,"menu":0,"downloaded":0,"playability":"LOGIN_REQUIRED"}}"""

        /** What the page script sends when only the player's captions menu and its downloads list tracks. */
        val FROM_PLAYER =
            """
            {"videoDetails":{"videoId":"$VIDEO","title":"AI news","shortDescription":""},
             "captions":{"playerCaptionsTracklistRenderer":{
               "captionTracks":[{"languageCode":"en","kind":"asr","vssId":"a.en","name":{"simpleText":"English (auto-generated)"},"baseUrl":""}],
               "audioTracks":[],"defaultAudioTrackIndex":0}},
             "found":{"details":0,"menu":1,"downloaded":1,"playability":"OK"}}
            """.trimIndent()
        val PANEL_LINES = """{"panelLines":[{"startMs":0,"text":"今日は"},{"startMs":1500,"text":"いい天気ですね"}]}"""
        val JSON3 =
            """{"events":[{"tStartMs":0,"dDurationMs":1500,"segs":[{"utf8":"你好"}]},{"tStartMs":2000,"dDurationMs":1500,"segs":[{"utf8":"再见"}]}]}"""
    }
}
