package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorSegmentsTest {
    @Test
    fun theRequestNamesOnlyTheHashPrefixAndAsksForSponsorsToSkip() {
        assertEquals("5f6b", sponsorHashPrefix(VIDEO))
        val url = sponsorSegmentsUrl(VIDEO)
        assertEquals("https", url.scheme)
        assertEquals(SPONSORBLOCK_HOST, url.host)
        assertEquals("/api/skipSegments/5f6b", url.encodedPath)
        assertEquals("""["sponsor"]""", url.queryParameter("categories"))
        assertEquals("""["skip"]""", url.queryParameter("actionTypes"))
        assertTrue(VIDEO !in url.toString())
    }

    @Test
    fun onlyTheOpenVideosWellFormedSponsorPartsAreKept() {
        val segments = parseSponsorSegments(REPLY, VIDEO)
        assertEquals(
            listOf(
                SponsorSegment("abc123", 12.5, 40.0, 600.2),
                SponsorSegment("def456", 300.0, 330.0, 0.0),
            ),
            segments,
        )
        assertEquals(emptyList<SponsorSegment>(), parseSponsorSegments(REPLY, "zzzzzzzzzzz"))
        assertEquals(emptyList<SponsorSegment>(), parseSponsorSegments("<html>", VIDEO))
        assertEquals(emptyList<SponsorSegment>(), parseSponsorSegments("{}", VIDEO))
    }

    @Test
    fun aMissingVideoIsCachedAndAFailureIsAskedAgain() =
        runBlocking {
            var asked = 0
            val missing =
                SponsorSegmentSource(
                    client {
                        asked++
                        404 to "Not Found"
                    },
                )
            assertEquals(emptyList<SponsorSegment>(), missing.segments(VIDEO))
            assertEquals(emptyList<SponsorSegment>(), missing.segments(VIDEO))
            assertEquals("404 means SponsorBlock has nothing, which is kept", 1, asked)

            var code = 500
            asked = 0
            val source =
                SponsorSegmentSource(
                    client {
                        asked++
                        code to REPLY
                    },
                )
            assertEquals(emptyList<SponsorSegment>(), source.segments(VIDEO))
            code = 200
            assertEquals(2, source.segments(VIDEO).size)
            assertEquals(2, source.segments(VIDEO).size)
            assertEquals(2, asked)
            assertEquals("A bad video ID is never asked", emptyList<SponsorSegment>(), source.segments("../x"))
            assertEquals(2, asked)
        }

    private fun client(reply: () -> Pair<Int, String>): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val (code, body) = reply()
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("test")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            ).build()

    private companion object {
        const val VIDEO = "dQw4w9WgXcQ"

        val REPLY =
            """
            [
              {"videoID":"otherVideo1","segments":[{"category":"sponsor","actionType":"skip","segment":[1,2],"UUID":"zzz"}]},
              {"videoID":"$VIDEO","hash":"5f6b...","segments":[
                {"category":"sponsor","actionType":"skip","segment":[300,330],"UUID":"def456"},
                {"category":"sponsor","actionType":"skip","segment":[12.5,40],"UUID":"abc123","videoDuration":600.2},
                {"category":"selfpromo","actionType":"skip","segment":[50,60],"UUID":"ghi789"},
                {"category":"sponsor","actionType":"mute","segment":[70,80],"UUID":"jkl012"},
                {"category":"sponsor","actionType":"skip","segment":[90,85],"UUID":"backwards"},
                {"category":"sponsor","actionType":"skip","segment":[95,99],"UUID":"<script>"},
                {"category":"sponsor","actionType":"skip","segment":["a","b"],"UUID":"notnumbers"}
              ]}
            ]
            """.trimIndent()
    }
}
