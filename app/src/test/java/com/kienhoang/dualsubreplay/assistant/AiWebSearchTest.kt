package com.kienhoang.dualsubreplay.assistant

import com.kienhoang.dualsubreplay.data.YouTubeVideoResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiWebSearchTest {
    @Test
    fun onlyPlainWebAddressesBecomeSources() {
        assertEquals(AiWebSource("NHK News", "https://www3.nhk.or.jp/news/"), aiWebSource(" NHK\n News ", "https://www3.nhk.or.jp/news/"))
        assertEquals("www3.nhk.or.jp", aiWebSource("", "https://www3.nhk.or.jp/news/")?.title)
        assertEquals("example.com", aiWebSource("x", "https://www.example.com/a")?.site)
        assertNull(aiWebSource("x", "javascript:alert(1)"))
        assertNull(aiWebSource("x", "file:///sdcard/a"))
        assertNull(aiWebSource("x", "https://user:pass@example.com/"))
        assertNull(aiWebSource("x", null))
    }

    @Test
    fun urlCitationsAreReadFromBothShapesOnce() {
        val message =
            JSONObject(
                """
                {"content":"Answer","annotations":[
                 {"type":"url_citation","url_citation":{"title":"One","url":"https://one.example/"}},
                 {"type":"url_citation","title":"Two","url":"https://two.example/"},
                 {"type":"url_citation","url_citation":{"title":"One again","url":"https://one.example/"}},
                 {"type":"file_citation","url":"https://three.example/"}]}
                """.trimIndent(),
            )
        assertEquals(listOf("https://one.example/", "https://two.example/"), urlCitations(message).map { it.url })
        assertTrue(urlCitations(null).isEmpty())
    }

    @Test
    fun tavilyKeysAreRecognisedAndItsRepliesBecomeNumberedExcerpts() {
        assertTrue(looksLikeTavilyKey(" tvly-dev-abc123XYZ "))
        assertFalse(looksLikeTavilyKey("tvly-"))
        assertFalse(looksLikeTavilyKey("AIzaSyExampleGeminiKey"))
        assertFalse(looksLikeTavilyKey("tvly-abc def ghi jkl"))

        val body = JSONObject(tavilyRequestBody("JLPT N3 date"))
        assertEquals("JLPT N3 date", body.getString("query"))
        assertEquals(5, body.getInt("max_results"))
        assertFalse(body.getBoolean("include_answer"))

        val result =
            parseTavilyReply(
                """
                {"results":[
                 {"title":"JLPT","url":"https://www.jlpt.jp/e/","content":"The test is held\n twice a year."},
                 {"title":"Bad","url":"ftp://x/","content":"skip"}]}
                """.trimIndent(),
            )
        assertEquals("[1] JLPT (jlpt.jp)\nThe test is held twice a year.", result.text)
        assertEquals(listOf("https://www.jlpt.jp/e/"), result.sources.map { it.url })
        assertThrows(AiSearchException::class.java) { parseTavilyReply("<html>") }
        assertTrue(tavilyFailure(401).startsWith("Tavily refused the search key"))
        assertTrue("no searches left" in tavilyFailure(432))
    }

    @Test
    fun openAiSearchUsesItsSearchModelAndKeepsTheCitations() {
        val body = JSONObject(openAiSearchRequestBody("weather Tokyo"))
        assertEquals(OPENAI_SEARCH_MODEL, body.getString("model"))
        assertTrue(body.has("web_search_options"))
        assertEquals("weather Tokyo", body.getJSONArray("messages").getJSONObject(1).getString("content"))
        val result =
            parseOpenAiSearchReply(
                """
                {"choices":[{"message":{"role":"assistant","content":"Sunny, 21°C.","annotations":[
                 {"type":"url_citation","url_citation":{"title":"Weather","url":"https://weather.example/tokyo"}}]}}]}
                """.trimIndent(),
            )
        assertEquals("Sunny, 21°C.", result.text)
        assertEquals("weather.example", result.sources.single().site)
    }

    @Test
    fun resultsReachTheModelAsQuotedData() {
        val web = aiWebResultText("q", AiWebResult("[1] Page (site)\nText", emptyList()))
        assertTrue("quoted data" in web)
        assertEquals("The web search for \"q\" found nothing.", aiWebResultText("q", AiWebResult(" ", emptyList())))

        val video = YouTubeVideoResult("dQw4w9WgXcQ", "Cats", "Channel", "3:12", "10 views", "1 day ago", subtitles = true)
        val text = aiYouTubeResultText("cats", listOf(video))
        assertTrue("quoted data" in text)
        assertTrue(AI_OPEN_VIDEO_TOOL in text)
        assertTrue("1. dQw4w9WgXcQ \"Cats\" · Channel · 3:12 · 10 views · 1 day ago · has subtitles (CC)" in text)
        assertEquals("Found YouTube videos for \"cats\": dQw4w9WgXcQ", aiFoundVideosNote("cats", listOf(video)))
        assertEquals("Found YouTube videos for \"cats\": none", aiFoundVideosNote("cats", emptyList()))
    }

    @Test
    fun eachServiceSearchesTheWebItsOwnWay() {
        assertEquals(AiWebSearchRoute.NONE, aiWebSearchRoute(AiWebSearchMode.OFF, AiProvider.CLAUDE, hasTavilyKey = true))
        assertEquals(AiWebSearchRoute.IN_REQUEST, aiWebSearchRoute(AiWebSearchMode.SERVICE, AiProvider.CLAUDE, false))
        assertEquals(AiWebSearchRoute.IN_REQUEST, aiWebSearchRoute(AiWebSearchMode.SERVICE, AiProvider.OPENROUTER, false))
        assertEquals(AiWebSearchRoute.SEARCH_MODEL, aiWebSearchRoute(AiWebSearchMode.SERVICE, AiProvider.OPENAI, false))
        assertEquals(AiWebSearchRoute.NONE, aiWebSearchRoute(AiWebSearchMode.SERVICE, AiProvider.GEMINI, true))
        assertEquals(AiWebSearchRoute.TAVILY, aiWebSearchRoute(AiWebSearchMode.TAVILY, AiProvider.GEMINI, true))
        assertEquals(AiWebSearchRoute.NONE, aiWebSearchRoute(AiWebSearchMode.TAVILY, AiProvider.GEMINI, false))
        assertEquals(AiWebSearchMode.OFF, storedAiWebSearchMode("nonsense"))
        assertEquals(AiWebSearchMode.TAVILY, storedAiWebSearchMode("tavily"))
    }

    @Test
    fun theOfferedToolsFollowTheSettings() {
        val defaults = AiAssistantSettings()
        assertEquals(
            AI_TOOLS + AI_FIND_VIDEOS_TOOLS + AI_SCREEN_TOOLS,
            aiOfferedTools(defaults, AiMemoryUse.OFF_IN_SETTINGS, AiWebSearchRoute.NONE),
        )
        val off = defaults.copy(youtubeSearch = false, screenLook = false)
        assertEquals(AI_TOOLS, aiOfferedTools(off, AiMemoryUse.OFF_IN_SETTINGS, AiWebSearchRoute.IN_REQUEST))
        assertEquals(AI_TOOLS + AI_WEB_SEARCH_TOOLS, aiOfferedTools(off, AiMemoryUse.OFF_IN_SETTINGS, AiWebSearchRoute.TAVILY))
        assertEquals(AI_TOOLS + AI_WEB_SEARCH_TOOLS, aiOfferedTools(off, AiMemoryUse.OFF_IN_SETTINGS, AiWebSearchRoute.SEARCH_MODEL))
        val names =
            (AI_TOOLS + AI_MEMORY_TOOLS + AI_FULL_ACCESS_TOOLS + AI_FIND_VIDEOS_TOOLS + AI_WEB_SEARCH_TOOLS + AI_SCREEN_TOOLS).map {
                it.name
            }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun openRouterAndClaudeGetTheirOwnSearchToolInTheRequest() {
        val messages = listOf(AiWireMessage(AiRole.USER, "News?"))
        val openRouter = JSONObject(chatRequestBody("m", messages, webSearch = true)).getJSONArray("tools")
        assertEquals(1, openRouter.length())
        assertEquals("openrouter:web_search", openRouter.getJSONObject(0).getString("type"))
        assertFalse(JSONObject(chatRequestBody("m", messages)).has("tools"))

        val claude = JSONObject(anthropicRequestBody("m", messages, tools = AI_TOOLS, webSearch = true)).getJSONArray("tools")
        val search = claude.getJSONObject(claude.length() - 1)
        assertEquals("web_search_20250305", search.getString("type"))
        assertEquals("web_search", search.getString("name"))
        assertEquals(3, search.getInt("max_uses"))
        assertEquals(AI_TOOLS.size + 1, claude.length())
    }

    @Test
    fun claudesSearchesCitationsAndPausesAreRead() {
        val reply =
            parseAnthropicReply(
                """
                {"stop_reason":"pause_turn","content":[
                 {"type":"server_tool_use","id":"srv1","name":"web_search","input":{"query":"JLPT 2026"}},
                 {"type":"web_search_tool_result","tool_use_id":"srv1","content":[]},
                 {"type":"text","text":"The JLPT is in July.","citations":[
                  {"type":"web_search_result_location","title":"JLPT","url":"https://www.jlpt.jp/e/","cited_text":"July"}]}]}
                """.trimIndent(),
            )
        assertTrue(reply.paused)
        assertEquals(listOf("JLPT 2026"), reply.webQueries)
        assertEquals("https://www.jlpt.jp/e/", reply.sources.single().url)
        assertEquals("The JLPT is in July.", reply.text)
        assertTrue(reply.rawContent!!.contains("server_tool_use"))
    }

    @Test
    fun aRefusedSearchToolIsToldApartFromOtherBadRequests() {
        assertEquals(
            AiErrorKind.UNSUPPORTED_WEB_SEARCH,
            aiErrorKindForStatus(400, "web_search is not supported for this model", sentWebSearch = true),
        )
        assertTrue(aiErrorKindForStatus(400, "web_search is not supported", sentWebSearch = false) != AiErrorKind.UNSUPPORTED_WEB_SEARCH)
        assertTrue(aiErrorKindForStatus(401, "web search", sentWebSearch = true) != AiErrorKind.UNSUPPORTED_WEB_SEARCH)
    }

    @Test
    fun theTavilyKeyIsSealedAndRemovedTogetherWithItsHint() {
        val storage = MapStorage()
        val store = AiSearchKeyStore(storage, FakeCipher())
        assertNull(store.hint())
        assertEquals(StoredAiKey.Missing, store.load())
        store.save(" tvly-dev-secret1234 ")
        assertEquals("1234", store.hint())
        assertEquals(StoredAiKey.Found("tvly-dev-secret1234"), store.load())
        assertFalse(storage.values.values.any { "secret1234" in it })
        store.remove()
        assertNull(store.hint())
    }

    @Test
    fun youTubeSearchAndTheScreenAreOnAndWebSearchIsOffByDefault() {
        val settings = AiAssistantSettings()
        assertTrue(settings.youtubeSearch)
        assertTrue(settings.screenLook)
        assertEquals(AiWebSearchMode.OFF, settings.webSearch)
    }
}
