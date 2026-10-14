package com.kienhoang.dualsubreplay.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeYouTubeParserTest {
    private val channelId = "UCabcdefghijklmnopqrstuv"

    @Test
    fun idsMustMatchYouTubeShapes() {
        assertTrue(isNativeVideoId("dQw4w9WgXcQ"))
        assertFalse(isNativeVideoId("dQw4w9WgXc"))
        assertFalse(isNativeVideoId("dQw4w9WgXc/"))
        assertTrue(isNativeChannelId(channelId))
        assertFalse(isNativeChannelId("UCshort"))
        assertTrue(isNativePlaylistId("PLabcdefghij"))
        assertFalse(isNativePlaylistId("PL<script>"))
    }

    @Test
    fun textReadsSimpleTextRunsAndContent() {
        assertEquals("Hello", nativeText(JSONObject("""{"simpleText":" Hello "}""")))
        assertEquals("Hello world", nativeText(JSONObject("""{"runs":[{"text":"Hello "},{"text":"world"}]}""")))
        assertEquals("Body", nativeText(JSONObject("""{"content":"Body"}""")))
        assertEquals("", nativeText(null))
    }

    @Test
    fun searchReadsVideosChannelsPlaylistsAndSkipsShortsAndAds() {
        val root =
            JSONObject(
                """
                {"contents":{"sectionListRenderer":{"contents":[
                  {"itemSectionRenderer":{"contents":[
                    {"videoRenderer":{"videoId":"aaaaaaaaaaa","title":{"runs":[{"text":"Classic video"}]},
                      "ownerText":{"runs":[{"text":"Teacher","navigationEndpoint":{"browseEndpoint":{"browseId":"$channelId"}}}]},
                      "lengthText":{"simpleText":"12:34"},"shortViewCountText":{"simpleText":"1.2M views"},
                      "publishedTimeText":{"simpleText":"2 years ago"}}},
                    {"reelShelfRenderer":{"items":[{"videoRenderer":{"videoId":"sssssssssss","title":{"simpleText":"A short"}}}]}},
                    {"adSlotRenderer":{"videoRenderer":{"videoId":"ddddddddddd","title":{"simpleText":"An ad"}}}},
                    {"channelRenderer":{"channelId":"$channelId","title":{"simpleText":"Teacher"},
                      "thumbnail":{"thumbnails":[{"url":"//yt3.ggpht.com/small"},{"url":"//yt3.ggpht.com/large"}]},
                      "subscriberCountText":{"simpleText":"10K subscribers"}}},
                    {"lockupViewModel":{"contentId":"bbbbbbbbbbb","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
                      "contentImage":{"thumbnailViewModel":{"overlays":[{"thumbnailOverlayBadgeViewModel":{"thumbnailBadges":[
                        {"thumbnailBadgeViewModel":{"text":"3:21","badgeStyle":"THUMBNAIL_OVERLAY_BADGE_STYLE_DEFAULT"}}]}}]}},
                      "metadata":{"lockupMetadataViewModel":{"title":{"content":"Lockup video"},
                        "metadata":{"contentMetadataViewModel":{"metadataRows":[
                          {"metadataParts":[{"text":{"content":"Teacher","commandRuns":[{"onTap":{"innertubeCommand":{"browseEndpoint":{"browseId":"$channelId"}}}}]}}]},
                          {"metadataParts":[{"text":{"content":"5K views"}},{"text":{"content":"1 day ago"}}]}]}}}}}},
                    {"lockupViewModel":{"contentId":"PLabcdefghij","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
                      "contentImage":{"collectionThumbnailViewModel":{"primaryThumbnail":{"thumbnailViewModel":{
                        "image":{"sources":[{"url":"https://i.ytimg.com/vi/ccccccccccc/hqdefault.jpg"}]},
                        "overlays":[{"thumbnailOverlayBadgeViewModel":{"thumbnailBadges":[{"thumbnailBadgeViewModel":{"text":"9 videos"}}]}}]}}}},
                      "metadata":{"lockupMetadataViewModel":{"title":{"content":"Lessons"},
                        "metadata":{"contentMetadataViewModel":{"metadataRows":[{"metadataParts":[{"text":{"content":"Teacher"}}]}]}}}}}}
                  ]}},
                  {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next-page"}}}}
                ]}}}
                """.trimIndent(),
            )

        val page = parseNativeSearch(root)

        assertEquals(
            listOf("video:aaaaaaaaaaa", "channel:$channelId", "video:bbbbbbbbbbb", "playlist:PLabcdefghij"),
            page.items.map { it.key },
        )
        val classic = page.items[0] as NativeVideo
        assertEquals("Classic video", classic.title)
        assertEquals("Teacher", classic.channelName)
        assertEquals(channelId, classic.channelId)
        assertEquals("12:34", classic.durationText)
        assertEquals("1.2M views · 2 years ago", classic.detailText)
        val channel = page.items[1] as NativeChannel
        assertEquals("https://yt3.ggpht.com/large", channel.avatarUrl)
        assertEquals("10K subscribers", channel.detailText)
        val lockup = page.items[2] as NativeVideo
        assertEquals("Teacher", lockup.channelName)
        assertEquals("3:21", lockup.durationText)
        assertEquals("5K views · 1 day ago", lockup.detailText)
        val playlist = page.items[3] as NativePlaylist
        assertEquals("9 videos", playlist.countText)
        assertEquals("ccccccccccc", playlist.thumbnailVideoId)
        assertEquals("next-page", page.continuation)
    }

    @Test
    fun channelPageVideosWithoutAChannelRowAreNamedAfterTheChannel() {
        val lockup =
            JSONObject(
                """
                {"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[
                  {"richItemRenderer":{"content":{"lockupViewModel":{"contentId":"eeeeeeeeeee","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
                    "metadata":{"lockupMetadataViewModel":{"title":{"content":"Upload"},
                      "metadata":{"contentMetadataViewModel":{"metadataRows":[
                        {"metadataParts":[{"text":{"content":"348 views"}},{"text":{"content":"2 days ago"}}]}]}}}}}}}}
                ]}}]}
                """.trimIndent(),
            )

        val page = parseNativeContinuation(lockup)
        val video = page.items.single() as NativeVideo
        assertEquals("", video.channelName)
        assertEquals("348 views · 2 days ago", video.detailText)

        val named = withChannel(page, "Teacher", channelId).items.single() as NativeVideo
        assertEquals("Teacher", named.channelName)
        assertEquals(channelId, named.channelId)
        assertNull(page.continuation)
    }

    @Test
    fun watchReadsDetailsRelatedVideosAndTheCommentsToken() {
        val root =
            JSONObject(
                """
                {"contents":{"twoColumnWatchNextResults":{
                  "results":{"results":{"contents":[
                    {"videoPrimaryInfoRenderer":{"title":{"runs":[{"text":"Watch title"}]},
                      "viewCount":{"videoViewCountRenderer":{"viewCount":{"simpleText":"1,234 views"}}},
                      "dateText":{"simpleText":"Oct 1, 2026"}}},
                    {"videoSecondaryInfoRenderer":{"owner":{"videoOwnerRenderer":{"title":{"runs":[{"text":"Teacher",
                        "navigationEndpoint":{"browseEndpoint":{"browseId":"$channelId"}}}]},
                      "thumbnail":{"thumbnails":[{"url":"https://yt3.ggpht.com/avatar"}]},
                      "subscriberCountText":{"simpleText":"10K subscribers"}}},
                      "attributedDescription":{"content":"About this video"}}},
                    {"itemSectionRenderer":{"sectionIdentifier":"comment-item-section","contents":[
                      {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"comments-token"}}}}]}}
                  ]}},
                  "secondaryResults":{"secondaryResults":{"results":[
                    {"compactVideoRenderer":{"videoId":"fffffffffff","title":{"simpleText":"Related"},
                      "shortBylineText":{"runs":[{"text":"Other"}]},"lengthText":{"simpleText":"4:00"}}},
                    {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"related-token"}}}}
                  ]}}
                }}}
                """.trimIndent(),
            )

        val details = parseNativeWatch("aaaaaaaaaaa", root)

        assertEquals("Watch title", details.title)
        assertEquals("1,234 views", details.viewsText)
        assertEquals("Oct 1, 2026", details.dateText)
        assertEquals("About this video", details.description)
        assertEquals(channelId, details.channel?.channelId)
        assertEquals("Teacher", details.channel?.title)
        assertEquals("https://yt3.ggpht.com/avatar", details.channel?.avatarUrl)
        assertEquals(listOf("video:fffffffffff"), details.related.items.map { it.key })
        assertEquals("related-token", details.related.continuation)
        assertEquals("comments-token", details.commentsContinuation)
    }

    @Test
    fun commentsJoinThreadsWithTheirEntityPayloads() {
        val root =
            JSONObject(
                """
                {"onResponseReceivedEndpoints":[
                  {"reloadContinuationItemsCommand":{"continuationItems":[{"commentsHeaderRenderer":{"countText":{"runs":[{"text":"224"},{"text":" Comments"}]}}}]}},
                  {"reloadContinuationItemsCommand":{"continuationItems":[
                    {"commentThreadRenderer":{"commentViewModel":{"commentViewModel":{"commentKey":"key-1","pinnedText":"Pinned by Teacher"}},
                      "replies":{"commentRepliesRenderer":{"contents":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"replies-token"}}}}]}}}},
                    {"commentThreadRenderer":{"commentViewModel":{"commentViewModel":{"commentKey":"missing"}}}},
                    {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"more-comments"}}}}
                  ]}}
                ],
                "frameworkUpdates":{"entityBatchUpdate":{"mutations":[
                  {"entityKey":"key-1","payload":{"commentEntityPayload":{
                    "properties":{"commentId":"c1","content":{"content":"Great lesson"},"publishedTime":"1 day ago"},
                    "author":{"displayName":"@teacher","avatarThumbnailUrl":"https://yt3.ggpht.com/me","isCreator":true},
                    "toolbar":{"likeCountNotliked":"12","replyCount":"3"}}}}
                ]}}}
                """.trimIndent(),
            )

        val page = parseNativeComments(root)

        assertEquals("224 Comments", page.countText)
        val comment = page.comments.single()
        assertEquals("c1", comment.id)
        assertEquals("@teacher", comment.author)
        assertEquals("Great lesson", comment.text)
        assertEquals("1 day ago", comment.publishedText)
        assertEquals("12", comment.likesText)
        assertEquals(3, comment.replyCount)
        assertEquals("replies-token", comment.repliesContinuation)
        assertTrue(comment.pinned)
        assertTrue(comment.byCreator)
        assertEquals("more-comments", page.continuation)
    }

    @Test
    fun playlistKeepsOnlyVideosAndUsesTheFirstAsItsThumbnail() {
        val root =
            JSONObject(
                """
                {"metadata":{"playlistMetadataRenderer":{"title":"Lessons"}},
                 "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"selected":true,"content":{
                   "sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[{"playlistVideoListRenderer":{"contents":[
                     {"playlistVideoRenderer":{"videoId":"ggggggggggg","title":{"runs":[{"text":"Lesson 1"}]},"lengthText":{"simpleText":"10:00"}}},
                     {"playlistVideoRenderer":{"videoId":"hhhhhhhhhhh","title":{"runs":[{"text":"Lesson 2"}]}}},
                     {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"playlist-more"}}}}
                   ]}}]}}]}}}}]}}}
                """.trimIndent(),
            )

        val page = parseNativePlaylist("PLabcdefghij", root)

        assertEquals("Lessons", page.playlist.title)
        assertEquals("ggggggggggg", page.playlist.thumbnailVideoId)
        assertEquals(listOf("Lesson 1", "Lesson 2"), page.videos.items.map { (it as NativeVideo).title })
        assertEquals("playlist-more", page.videos.continuation)
    }

    @Test
    fun channelFeedReadsUploadsWithTheirTimes() {
        val xml =
            """
            <feed><author><name>Feed owner</name></author>
            <entry><yt:videoId>iiiiiiiiiii</yt:videoId><yt:channelId>$channelId</yt:channelId>
              <title>Tom &amp; Jerry</title><author><name>Teacher</name></author>
              <published>2026-10-01T12:00:00+00:00</published></entry>
            <entry><yt:videoId>bad</yt:videoId><title>Skipped</title></entry>
            </feed>
            """.trimIndent()

        val uploads = parseNativeChannelFeed(xml)

        val (video, published) = uploads.single()
        assertEquals("iiiiiiiiiii", video.videoId)
        assertEquals("Tom & Jerry", video.title)
        assertEquals("Teacher", video.channelName)
        assertEquals(channelId, video.channelId)
        assertEquals(1_790_856_000_000L, published)
    }

    @Test
    fun requestBodyUsesTheWebClientAndTheDeviceLanguage() {
        val body = nativeRequestBody(mapOf("query" to "lesson"), java.util.Locale.forLanguageTag("pt-BR"))
        val client = body.getJSONObject("context").getJSONObject("client")
        assertEquals("WEB", client.getString("clientName"))
        assertEquals("pt-BR", client.getString("hl"))
        assertEquals("BR", client.getString("gl"))
        assertEquals("lesson", body.getString("query"))
    }
}
