package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PageTranslationSourceTest {
    @Test fun thePagesTranslationBecomesCuesAndIsKept() =
        runBlocking {
            val source = PageTranslationSource()
            val result = async { source.translation(VIDEO, "ja", true, "vi") }
            val request = awaitRequest(source)
            assertEquals(PageTranslationTarget(VIDEO, "ja", true, "vi", request.target.attempt), request.target)
            source.offer(request, PageTranslationReply.Working("page-address"))
            assertFalse(result.isCompleted)
            source.offer(request, PageTranslationReply.Done(SRV1))
            assertEquals(listOf("Xin chào mọi người!", "Tôi là Ken!"), result.await()!!.map { it.text })
            assertNull("A finished request is closed", source.pending(VIDEO))
            // The same video again does not ask the page twice.
            assertEquals(2, source.translation(VIDEO, "ja", true, "vi", timeoutMs = 50)!!.size)
            assertNull(source.translation(VIDEO, "ja", true, "en", timeoutMs = 50))
        }

    @Test fun aTrackYouTubeDoesNotTranslateEndsAtOnce() =
        runBlocking {
            val source = PageTranslationSource()
            val result = async { source.translation(VIDEO, "ja", false, "vi") }
            source.offer(awaitRequest(source), PageTranslationReply.Unavailable)
            assertNull(result.await())
        }

    @Test fun aPageThatNeverAnswersTimesOut() =
        runBlocking {
            assertNull(PageTranslationSource().translation(VIDEO, "ja", false, "vi", timeoutMs = 50))
        }

    @Test fun anUnreadableDocumentIsNoTranslation() =
        runBlocking {
            val source = PageTranslationSource()
            val result = async { source.translation(VIDEO, "ja", false, "vi") }
            source.offer(awaitRequest(source), PageTranslationReply.Done("<html>sorry</html>"))
            assertNull(result.await())
        }

    @Test fun anotherVideoAndReplacedRequestsAreIgnored() =
        runBlocking {
            val source = PageTranslationSource()
            val first = async { source.translation(VIDEO, "ja", false, "vi") }
            val old = awaitRequest(source)
            assertNull(source.pending("zzzzzzzzzzz"))
            val second = async { source.translation("bbbbbbbbbbb", "en", false, "vi") }
            val current = awaitRequest(source, "bbbbbbbbbbb")
            assertNull(first.await())
            source.offer(old, PageTranslationReply.Done(SRV1))
            assertSame(current, source.pending("bbbbbbbbbbb"))
            second.cancel()
        }

    private suspend fun awaitRequest(
        source: PageTranslationSource,
        videoId: String = VIDEO,
    ): PageTranslationSource.Request {
        repeat(200) {
            source.pending(videoId)?.let { return it }
            yield()
        }
        throw AssertionError("No page request for $videoId")
    }

    private companion object {
        const val VIDEO = "abcdefghijk"
        const val SRV1 =
            """<?xml version="1.0" encoding="utf-8" ?><transcript><text start="0.5" dur="2">Xin chào mọi người!</text>""" +
                """<text start="2.6" dur="2">Tôi là Ken!</text></transcript>"""
    }
}
