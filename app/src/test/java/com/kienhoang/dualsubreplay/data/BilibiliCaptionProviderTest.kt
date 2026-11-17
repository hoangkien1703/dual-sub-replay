package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BilibiliCaptionProviderTest {
    private val asked = mutableListOf<String>()

    private fun provider(answers: Map<String, String>) =
        BilibiliCaptionProvider(
            get = { url, _ ->
                asked += url
                answers.entries.firstOrNull { url.contains(it.key) }?.value ?: error("unexpected $url")
            },
            now = { 1_702_204_169_000L },
        )

    private val view = """{"code":0,"data":{"aid":11,"cid":21,"pages":[{"page":1,"cid":21},{"page":2,"cid":22}]}}"""
    private val nav =
        """{"code":-101,"message":"账号未登录","data":{"wbi_img":{""" +
            """"img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png",""" +
            """"sub_url":"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"}}}"""

    @Test
    fun theSignatureMatchesBilibilisPublishedExample() {
        val key = wbiMixinKey("7cd084941338484aae1ad9425b84077c", "4932caff0ff746eab6f01bf08b70ac45")
        assertEquals("ea1db124af3c7062474693fa704f4ff8", key)
        assertEquals(
            "bar=514&foo=114&wts=1702204169&zab=1919810&w_rid=8f6f2b5b3d485fe1886cec6a0be8c5d4",
            signWbi(mapOf("foo" to "114", "bar" to "514", "zab" to "1919810"), key, 1_702_204_169),
        )
    }

    @Test
    fun aSignedInUserGetsTheChinaSubtitlesOfTheRightPage() =
        runBlocking {
            val player =
                """{"code":0,"data":{"login_mid":5,"subtitle":{"subtitles":[""" +
                    """{"lan":"ai-zh","lan_doc":"中文（自动生成）","subtitle_url":"//aisubtitle.hdslb.com/ai.json","ai_type":0},""" +
                    """{"lan":"en-US","lan_doc":"English","subtitle_url":"//aisubtitle.hdslb.com/en.json"}]}}}"""
            val body = """{"body":[{"from":1.5,"to":3.25,"content":"大家好"},{"from":0.2,"to":1.0,"content":"欢迎"}]}"""
            val result =
                provider(
                    mapOf("view?bvid" to view, "/nav" to nav, "player/wbi/v2" to player, "ai.json" to body),
                ).fetch("bili-BV1kYsW6NE6E-p2", emptyList(), "zh", null)
            assertEquals("zh", result.languageCode)
            assertTrue(result.isGenerated)
            assertEquals(listOf("欢迎", "大家好"), result.cues.map { it.text })
            assertEquals(1_500L, result.cues[1].startMs)
            assertEquals(3_250L, result.cues[1].endMs)
            assertEquals(listOf("a.zh", ".en"), result.availableLanguages.map { it.trackId })
            val signed = asked.first { "player/wbi/v2" in it }
            assertTrue(signed, "cid=22" in signed && "w_rid=" in signed && "wts=1702204169" in signed)
            assertEquals("https://aisubtitle.hdslb.com/ai.json", asked.last())
        }

    @Test
    fun signedOutChinaVideosAskForASignIn() =
        runBlocking {
            val player = """{"code":0,"data":{"login_mid":0,"need_login_subtitle":true,"subtitle":{"subtitles":[]}}}"""
            try {
                provider(mapOf("view?bvid" to view, "/nav" to nav, "player/wbi/v2" to player))
                    .fetch("bili-BV1kYsW6NE6E", emptyList(), null, null)
                fail("expected a sign-in error")
            } catch (expected: BilibiliSignInNeededException) {
                assertTrue(expected.meansNoPublicCaptions())
            }
        }

    @Test
    fun internationalEpisodesPickTheLearningLanguageFromAss() =
        runBlocking {
            val list =
                """{"code":0,"data":{"subtitles":[""" +
                    """{"url":"https://s.bstarstatic.com/en.ass","lang":"English","lang_key":"en"},""" +
                    """{"url":"https://s.bstarstatic.com/zh.ass","lang":"中文（简体）","lang_key":"zh-Hans"},""" +
                    """{"url":"https://s.bstarstatic.com/tw.ass","lang":"中文（繁体）","lang_key":"zh-Hant"}],"video_subtitle":null}}"""
            val ass =
                """
                [Script Info]
                Title: Bilibili Subtitle

                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,{\an8}第一句,还有逗号
                Dialogue: 0,1:02:03.04,1:02:04.00,Default,,0,0,0,,上\N下
                """.trimIndent()
            val result =
                provider(mapOf("v2/subtitle" to list, "zh.ass" to ass))
                    .fetch("bilitv-ss34695-ep342144", emptyList(), "zh", null)
            assertTrue(asked.first().contains("episode_id=342144"))
            assertEquals("zh", result.languageCode)
            assertEquals(listOf("第一句,还有逗号", "上 下"), result.cues.map { it.text })
            assertEquals(3_723_040L, result.cues[1].startMs)
            assertEquals(listOf("en", "zh", "zh-TW"), result.availableLanguages.map { it.code })
        }

    @Test
    fun aSeasonPageUsesItsFirstEpisodeAndUploadsReadJson() =
        runBlocking {
            val episodes = """{"code":0,"data":{"sections":[{"episodes":[{"episode_id":"342144"},{"episode_id":"342145"}]}]}}"""
            val list = """{"code":0,"data":{"subtitles":[{"url":"https://s.bstarstatic.com/en.ass","lang":"English","lang_key":"en"}]}}"""
            provider(
                mapOf(
                    "ogv/play/episodes" to episodes,
                    "v2/subtitle" to list,
                    "en.ass" to "[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,D,,0,0,0,,Hi",
                ),
            ).fetch("bilitv-ss34695", emptyList(), null, null)
            assertTrue(asked[1].contains("episode_id=342144"))

            val uploads =
                JSONObject(
                    """{"video_subtitle":[{"lang_key":"id","lang":"Indonesia","text_subtitle":{"url":"//s.bstarstatic.com/id.json"}}]}""",
                )
            assertEquals("https://s.bstarstatic.com/id.json", parseTvTracks(uploads).single().url)
        }

    @Test
    fun theUsersPickedTrackAndOriginalLanguageWin() {
        val tracks =
            listOf(
                BilibiliTrack("zh", "AI", "https://a", generated = true),
                BilibiliTrack("zh", "人工", "https://b", generated = false),
                BilibiliTrack("en", "English", "https://c", generated = false),
            )
        assertEquals("https://b", pickBilibiliTrack(tracks, emptyList(), null, null)?.url)
        assertEquals("https://c", pickBilibiliTrack(tracks, listOf("en"), "zh", null)?.url)
        assertEquals("https://a", pickBilibiliTrack(tracks, listOf("en"), null, "a.zh")?.url)
        assertEquals("zh", bilibiliLanguageCode("ai-zh"))
        assertEquals("zh-TW", bilibiliLanguageCode("zh-Hant"))
        assertEquals("pt", bilibiliLanguageCode("pt-BR"))
    }
}
