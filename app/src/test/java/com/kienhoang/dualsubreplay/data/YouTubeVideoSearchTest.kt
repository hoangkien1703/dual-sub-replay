package com.kienhoang.dualsubreplay.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeVideoSearchTest {
    private fun video(
        id: String,
        title: String,
        cc: Boolean = false,
    ) = """
        {"videoRenderer":{"videoId":"$id","title":{"runs":[{"text":"$title"}]},
        "ownerText":{"runs":[{"text":"Channel "},{"text":"One"}]},"lengthText":{"simpleText":"12:34"},
        "viewCountText":{"simpleText":"1,234 views"},"publishedTimeText":{"simpleText":"2 years ago"},
        "badges":[${if (cc) """{"metadataBadgeRenderer":{"label":"CC"}}""" else ""}]}}
        """.trimIndent()

    private fun reply(vararg items: String) =
        """
        {"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
        {"itemSectionRenderer":{"contents":[${items.joinToString(",")}]}}]}}}}}
        """.trimIndent()

    @Test
    fun videosAreReadInOrderWithTheirDetails() {
        val videos =
            parseYouTubeSearchReply(
                reply(
                    video("dQw4w9WgXcQ", "Japanese for beginners", cc = true),
                    """{"channelRenderer":{"channelId":"UC123"}}""",
                    """{"shelfRenderer":{"content":{"items":[${video("abcdefghijk", "Inside a shelf")}]}}}""",
                ),
            )
        assertEquals(listOf("dQw4w9WgXcQ", "abcdefghijk"), videos.map { it.videoId })
        val first = videos.first()
        assertEquals("Japanese for beginners", first.title)
        assertEquals("Channel One", first.channel)
        assertEquals("12:34", first.length)
        assertEquals("1,234 views", first.views)
        assertEquals("2 years ago", first.published)
        assertTrue(first.subtitles)
        assertFalse(videos[1].subtitles)
    }

    @Test
    fun badIdsUntitledVideosAndRepeatsAreSkippedAndTheListIsCapped() {
        val many = (0 until 15).map { video("video${it.toString().padStart(6, '0')}", "Video $it") }
        val videos =
            parseYouTubeSearchReply(
                reply(
                    video("short", "Bad ID"),
                    video("dQw4w9WgXcQ", ""),
                    *many.toTypedArray(),
                    many.first(),
                ),
            )
        assertEquals(MAX_YOUTUBE_SEARCH_RESULTS, videos.size)
        assertEquals("video000000", videos.first().videoId)
        assertTrue(parseYouTubeSearchReply("not json").isEmpty())
        assertTrue(parseYouTubeSearchReply("{}").isEmpty())
    }

    @Test
    fun theRequestAsksForVideosInEnglishWithTheChosenFilter() {
        val body = JSONObject(youTubeSearchRequestBody("猫 日本語", subtitlesOnly = true, country = "VN"))
        assertEquals("猫 日本語", body.getString("query"))
        assertEquals("EgQQASgB", body.getString("params"))
        val client = body.getJSONObject("context").getJSONObject("client")
        assertEquals("WEB", client.getString("clientName"))
        assertEquals("en", client.getString("hl"))
        assertEquals("VN", client.getString("gl"))

        val plain = JSONObject(youTubeSearchRequestBody("cats", subtitlesOnly = false, country = ""))
        assertEquals("EgIQAQ==", plain.getString("params"))
        assertFalse(plain.getJSONObject("context").getJSONObject("client").has("gl"))
        assertEquals("www.youtube.com", YOUTUBE_SEARCH_URL.host)
        assertTrue(YOUTUBE_SEARCH_URL.isHttps)
    }
}
