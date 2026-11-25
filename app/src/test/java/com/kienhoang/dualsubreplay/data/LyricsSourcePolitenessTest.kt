package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** LRCLIB's API terms (registered 2026-11-25): name the app, and wait when it says "too many requests". */
class LyricsSourcePolitenessTest {
    private val asked = mutableListOf<Request>()
    private var clock = 1_000_000L
    private val waited = mutableListOf<Long>()

    /** LRCLIB answering each request with the next of [answers] (a status and a Retry-After). */
    private fun source(vararg answers: Pair<Int, String?>): SyncedLyricsSource {
        val queue = ArrayDeque(answers.toList())
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    asked += chain.request()
                    val (code, retryAfter) = queue.removeFirst()
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("")
                        .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
                        .body("[]".toResponseBody("application/json".toMediaType()))
                        .build()
                }.build()
        return SyncedLyricsSource(client, now = { clock }, wait = {
            waited += it
            clock += it
        }, jitter = { it / 10 })
    }

    @Test
    fun everyLookupNamesTheApp() =
        runBlocking {
            val lyrics = source(200 to null)
            assertEquals("[]", lyrics.search("Never Gonna Give You Up - Rick Astley", ""))
            assertEquals(LRCLIB_APP_ID, asked.single().header("Lrclib-App-Id"))
        }

    @Test
    fun aShortWaitIsWaitedOutOnceThenAsksAgain() =
        runBlocking {
            val lyrics = source(429 to "2", 200 to null)
            assertEquals("[]", lyrics.search("Never Gonna Give You Up - Rick Astley", ""))
            // 2 s plus a little at random.
            assertEquals(listOf(2_200L), waited)
            assertEquals(2, asked.size)
        }

    @Test
    fun aLongWaitSkipsLookupsUntilItEnds() =
        runBlocking {
            val lyrics = source(503 to "60", 200 to null)
            assertNull(lyrics.search("Song one - Artist", ""))
            // Nothing is sent while LRCLIB asked to be left alone.
            clock += 30_000L
            assertNull(lyrics.search("Song two - Artist", ""))
            assertEquals(1, asked.size)
            clock += 40_000L
            assertEquals("[]", lyrics.search("Song two - Artist", ""))
            assertEquals(emptyList<Long>(), waited)
        }

    @Test
    fun retryAfterInSecondsOrTheDefault() {
        assertEquals(5_000L, lyricsRetryAfterMs("5"))
        assertEquals(30_000L, lyricsRetryAfterMs(null))
        assertEquals(30_000L, lyricsRetryAfterMs("Wed, 25 Nov 2026 11:00:00 GMT"))
    }
}
