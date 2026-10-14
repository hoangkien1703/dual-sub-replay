package com.kienhoang.dualsubreplay.data

import org.json.JSONArray
import org.json.JSONObject

/*
 * Reads the public YouTube web responses that native mode shows (search, watch details, comments,
 * channels, playlists). YouTube changes these layouts often, so every reader walks the tree for the
 * renderers it knows and skips everything else instead of following one fixed path.
 */

private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
private val CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{22}")
private val PLAYLIST_ID = Regex("[A-Za-z0-9_-]{10,64}")
private val THUMBNAIL_VIDEO_ID = Regex("/vi(?:_webp)?/([A-Za-z0-9_-]{11})/")

internal fun isNativeVideoId(value: String?): Boolean = value != null && VIDEO_ID.matches(value)

internal fun isNativeChannelId(value: String?): Boolean = value != null && CHANNEL_ID.matches(value)

internal fun isNativePlaylistId(value: String?): Boolean = value != null && PLAYLIST_ID.matches(value)

/** Text from YouTube's `simpleText`, `runs` or `content` shapes. */
internal fun nativeText(value: Any?): String =
    when (value) {
        is String -> value
        is JSONObject ->
            value.optString("simpleText").ifEmpty {
                value.optJSONArray("runs")?.let { runs ->
                    (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
                } ?: value.optString("content")
            }
        else -> ""
    }.trim()

/** Every list item in [root], in page order, and the last "load more" token found among them. */
internal fun nativePageFrom(root: Any?): NativePage {
    val items = mutableListOf<NativeItem>()
    var continuation: String? = null
    walkItems(root, items) { token -> continuation = token }
    return NativePage(items.distinctBy { it.key }, continuation)
}

private val SKIPPED_KEYS =
    setOf(
        // Shorts shelves, ads and comments are not list items in native mode.
        "reelShelfRenderer",
        "gridShelfViewModel",
        "shortsLockupViewModel",
        "adSlotRenderer",
        "promotedSparklesWebRenderer",
        "commentThreadRenderer",
        "frameworkUpdates",
        "engagementPanels",
        "topbar",
    )

private fun walkItems(
    node: Any?,
    items: MutableList<NativeItem>,
    onContinuation: (String) -> Unit,
) {
    when (node) {
        is JSONArray -> for (index in 0 until node.length()) walkItems(node.opt(index), items, onContinuation)
        is JSONObject -> {
            for (key in node.keys()) {
                val value = node.opt(key)
                if (key in SKIPPED_KEYS || value !is JSONObject && value !is JSONArray) continue
                val obj = value as? JSONObject
                when (key) {
                    "videoRenderer", "compactVideoRenderer", "gridVideoRenderer", "playlistVideoRenderer", "videoWithContextRenderer" ->
                        obj?.let(::classicVideo)?.let(items::add)
                    "lockupViewModel" -> obj?.let(::lockupItem)?.let(items::add)
                    "channelRenderer", "gridChannelRenderer" -> obj?.let(::classicChannel)?.let(items::add)
                    "playlistRenderer", "gridPlaylistRenderer" -> obj?.let(::classicPlaylist)?.let(items::add)
                    "continuationItemRenderer" -> obj?.let(::continuationToken)?.let(onContinuation)
                    else -> walkItems(value, items, onContinuation)
                }
            }
        }
    }
}

/** The first `continuationCommand.token` inside a continuation item. */
internal fun continuationToken(node: Any?): String? {
    when (node) {
        is JSONObject -> {
            node
                .optJSONObject("continuationCommand")
                ?.optString("token")
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
            for (key in node.keys()) continuationToken(node.opt(key))?.let { return it }
        }
        is JSONArray -> for (index in 0 until node.length()) continuationToken(node.opt(index))?.let { return it }
    }
    return null
}

private fun firstBrowseId(
    node: Any?,
    accept: (String) -> Boolean,
): String? {
    when (node) {
        is JSONObject -> {
            node
                .optJSONObject("browseEndpoint")
                ?.optString("browseId")
                ?.takeIf(accept)
                ?.let { return it }
            for (key in node.keys()) firstBrowseId(node.opt(key), accept)?.let { return it }
        }
        is JSONArray -> for (index in 0 until node.length()) firstBrowseId(node.opt(index), accept)?.let { return it }
    }
    return null
}

private fun firstObject(
    node: Any?,
    key: String,
): JSONObject? {
    when (node) {
        is JSONObject -> {
            node.optJSONObject(key)?.let { return it }
            for (child in node.keys()) firstObject(node.opt(child), key)?.let { return it }
        }
        is JSONArray -> for (index in 0 until node.length()) firstObject(node.opt(index), key)?.let { return it }
    }
    return null
}

/**
 * The video whose thumbnail an image URL shows. Reads string values one by one: Android's
 * JSONObject.toString() escapes every slash, so matching against the whole JSON text would fail.
 */
private fun thumbnailVideoId(node: Any?): String? {
    when (node) {
        is String -> return THUMBNAIL_VIDEO_ID.find(node)?.groupValues?.get(1)
        is JSONObject -> for (key in node.keys()) thumbnailVideoId(node.opt(key))?.let { return it }
        is JSONArray -> for (index in 0 until node.length()) thumbnailVideoId(node.opt(index))?.let { return it }
    }
    return null
}

/** The largest image URL in a `thumbnails` or `sources` list, made absolute. */
private fun bestImageUrl(node: JSONObject?): String? {
    val list = node?.optJSONArray("thumbnails") ?: node?.optJSONArray("sources") ?: return null
    val url = list.optJSONObject(list.length() - 1)?.optString("url").orEmpty()
    return when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("https://") -> url
        else -> null
    }
}

private fun joinDetails(vararg parts: String): String = parts.filter { it.isNotBlank() }.joinToString(" · ")

private fun classicVideo(node: JSONObject): NativeVideo? {
    val videoId = node.optString("videoId").takeIf(::isNativeVideoId) ?: return null
    val owner = node.optJSONObject("ownerText") ?: node.optJSONObject("shortBylineText") ?: node.optJSONObject("longBylineText")
    val badges = node.optJSONArray("badges")?.toString().orEmpty() + node.optJSONArray("thumbnailOverlays")?.toString().orEmpty()
    val duration = nativeText(node.optJSONObject("lengthText"))
    return NativeVideo(
        videoId = videoId,
        title = nativeText(node.optJSONObject("title")),
        channelName = nativeText(owner),
        channelId = firstBrowseId(owner, ::isNativeChannelId),
        durationText = duration,
        detailText =
            joinDetails(
                nativeText(node.optJSONObject("shortViewCountText")).ifEmpty { nativeText(node.optJSONObject("viewCountText")) },
                nativeText(node.optJSONObject("publishedTimeText")),
            ),
        live = duration.isEmpty() && badges.contains("LIVE"),
    ).takeIf { it.title.isNotBlank() }
}

private fun metadataParts(lockup: JSONObject): List<List<String>> {
    val rows =
        lockup
            .optJSONObject("metadata")
            ?.optJSONObject("lockupMetadataViewModel")
            ?.optJSONObject("metadata")
            ?.optJSONObject("contentMetadataViewModel")
            ?.optJSONArray("metadataRows") ?: return emptyList()
    return (0 until rows.length())
        .map { rowIndex ->
            val parts = rows.optJSONObject(rowIndex)?.optJSONArray("metadataParts") ?: JSONArray()
            (0 until parts.length()).mapNotNull { partIndex ->
                nativeText(parts.optJSONObject(partIndex)?.optJSONObject("text")).takeIf { it.isNotBlank() }
            }
        }.filter { it.isNotEmpty() }
}

private fun lockupItem(node: JSONObject): NativeItem? {
    val id = node.optString("contentId")
    val title = nativeText(node.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")?.optJSONObject("title"))
    if (title.isBlank()) return null
    val rows = metadataParts(node)
    val badge = firstObject(node.optJSONObject("contentImage"), "thumbnailBadgeViewModel")
    return when (node.optString("contentType")) {
        "LOCKUP_CONTENT_TYPE_VIDEO" -> {
            val channelId = firstBrowseId(node.optJSONObject("metadata"), ::isNativeChannelId)
            // A channel's own Videos tab leaves the channel row out: then the only row is views and age.
            val hasChannelRow = channelId != null || rows.size > 1
            val live = badge?.optString("badgeStyle").orEmpty().contains("LIVE")
            NativeVideo(
                videoId = id.takeIf(::isNativeVideoId) ?: return null,
                title = title,
                channelName = if (hasChannelRow) rows.firstOrNull()?.firstOrNull().orEmpty() else "",
                channelId = channelId,
                durationText = if (live) "" else badge?.optString("text").orEmpty(),
                detailText = (if (hasChannelRow) rows.drop(1) else rows).flatten().joinToString(" · "),
                live = live,
            )
        }
        "LOCKUP_CONTENT_TYPE_PLAYLIST", "LOCKUP_CONTENT_TYPE_PODCAST" ->
            NativePlaylist(
                playlistId = id.takeIf(::isNativePlaylistId) ?: return null,
                title = title,
                channelName = rows.firstOrNull()?.joinToString(" · ").orEmpty(),
                countText = badge?.optString("text").orEmpty(),
                thumbnailVideoId = thumbnailVideoId(node.optJSONObject("contentImage")),
            )
        else -> null
    }
}

private fun classicChannel(node: JSONObject): NativeChannel? {
    val channelId = node.optString("channelId").takeIf(::isNativeChannelId) ?: return null
    return NativeChannel(
        channelId = channelId,
        title = nativeText(node.optJSONObject("title")),
        avatarUrl = bestImageUrl(node.optJSONObject("thumbnail")),
        detailText =
            joinDetails(
                nativeText(node.optJSONObject("subscriberCountText")),
                nativeText(node.optJSONObject("videoCountText")),
            ),
    ).takeIf { it.title.isNotBlank() }
}

private fun classicPlaylist(node: JSONObject): NativePlaylist? {
    val playlistId = node.optString("playlistId").takeIf(::isNativePlaylistId) ?: return null
    return NativePlaylist(
        playlistId = playlistId,
        title = nativeText(node.optJSONObject("title")),
        channelName = nativeText(node.optJSONObject("shortBylineText")),
        countText = nativeText(node.optJSONObject("videoCountText")).ifEmpty { node.optString("videoCount") },
        thumbnailVideoId = thumbnailVideoId(node.optJSONObject("thumbnail") ?: node.optJSONObject("thumbnails") ?: node),
    ).takeIf { it.title.isNotBlank() }
}

/** Items appended by a "load more" request (search, browse or watch-next continuation). */
internal fun parseNativeContinuation(root: JSONObject): NativePage {
    val actions = JSONArray()
    for (key in listOf("onResponseReceivedCommands", "onResponseReceivedActions", "onResponseReceivedEndpoints")) {
        root.optJSONArray(key)?.let { for (index in 0 until it.length()) actions.put(it.opt(index)) }
    }
    return nativePageFrom(actions)
}

internal fun parseNativeSearch(root: JSONObject): NativePage =
    if (root.has("contents")) nativePageFrom(root.optJSONObject("contents")) else parseNativeContinuation(root)

internal fun parseNativeWatch(
    videoId: String,
    root: JSONObject,
): NativeWatchDetails {
    val results = root.optJSONObject("contents")?.optJSONObject("twoColumnWatchNextResults")
    val main = results?.optJSONObject("results")?.optJSONObject("results")?.optJSONArray("contents") ?: JSONArray()
    val primary = firstObject(main, "videoPrimaryInfoRenderer")
    val secondary = firstObject(main, "videoSecondaryInfoRenderer")
    val owner = secondary?.optJSONObject("owner")?.optJSONObject("videoOwnerRenderer")
    val channelId = firstBrowseId(owner, ::isNativeChannelId)
    val channel =
        channelId?.let {
            NativeChannel(
                channelId = it,
                title = nativeText(owner?.optJSONObject("title")),
                avatarUrl = bestImageUrl(owner?.optJSONObject("thumbnail")),
                detailText = nativeText(owner?.optJSONObject("subscriberCountText")),
            )
        }
    val views = primary?.optJSONObject("viewCount")?.optJSONObject("videoViewCountRenderer")
    return NativeWatchDetails(
        videoId = videoId,
        title = nativeText(primary?.optJSONObject("title")),
        channel = channel,
        viewsText = nativeText(views?.optJSONObject("viewCount")).ifEmpty { nativeText(views?.optJSONObject("shortViewCount")) },
        dateText = nativeText(primary?.optJSONObject("dateText")),
        description =
            nativeText(secondary?.optJSONObject("attributedDescription"))
                .ifEmpty { nativeText(secondary?.optJSONObject("description")) },
        related = nativePageFrom(results?.optJSONObject("secondaryResults")),
        commentsContinuation = continuationToken(main.let(::commentSection)),
    )
}

private fun commentSection(main: JSONArray): JSONObject? {
    for (index in 0 until main.length()) {
        val section = main.optJSONObject(index)?.optJSONObject("itemSectionRenderer") ?: continue
        if (section.optString("sectionIdentifier") == "comment-item-section" || section.toString().contains("comments-section")) {
            return section
        }
    }
    return null
}

/** Comment bodies, authors and counts, which YouTube sends apart from the comment list. */
private fun commentEntities(root: JSONObject): Map<String, JSONObject> {
    val entities = mutableMapOf<String, JSONObject>()
    val mutations = root.optJSONObject("frameworkUpdates")?.optJSONObject("entityBatchUpdate")?.optJSONArray("mutations") ?: JSONArray()
    for (index in 0 until mutations.length()) {
        val mutation = mutations.optJSONObject(index) ?: continue
        mutation.optJSONObject("payload")?.optJSONObject("commentEntityPayload")?.let { entities[mutation.optString("entityKey")] = it }
    }
    return entities
}

/** The items of every reload or append action in a continuation response. */
private fun continuationItems(root: JSONObject): List<JSONObject> {
    val items = mutableListOf<JSONObject>()
    for (key in listOf("onResponseReceivedEndpoints", "onResponseReceivedActions", "onResponseReceivedCommands")) {
        val endpoints = root.optJSONArray(key) ?: continue
        for (index in 0 until endpoints.length()) {
            val endpoint = endpoints.optJSONObject(index) ?: continue
            val action =
                endpoint.optJSONObject("reloadContinuationItemsCommand")
                    ?: endpoint.optJSONObject("appendContinuationItemsAction")
                    ?: continue
            val list = action.optJSONArray("continuationItems") ?: continue
            for (itemIndex in 0 until list.length()) list.optJSONObject(itemIndex)?.let(items::add)
        }
    }
    return items
}

private fun nativeComment(
    view: JSONObject?,
    replies: JSONObject?,
    entities: Map<String, JSONObject>,
): NativeComment? {
    val entity = view?.optString("commentKey")?.let(entities::get) ?: return null
    val properties = entity.optJSONObject("properties") ?: return null
    val author = entity.optJSONObject("author")
    val toolbar = entity.optJSONObject("toolbar")
    val text =
        properties
            .optJSONObject("content")
            ?.optString("content")
            .orEmpty()
            .trim()
    if (text.isEmpty()) return null
    return NativeComment(
        id = properties.optString("commentId"),
        author = author?.optString("displayName").orEmpty(),
        avatarUrl = author?.optString("avatarThumbnailUrl")?.takeIf { it.startsWith("https://") },
        text = text,
        publishedText = properties.optString("publishedTime"),
        likesText =
            toolbar
                ?.optString("likeCountNotliked")
                .orEmpty()
                .takeUnless { it == "0" }
                .orEmpty(),
        replyCount = toolbar?.optString("replyCount")?.toIntOrNull() ?: 0,
        repliesContinuation = continuationToken(replies),
        pinned = view.optString("pinnedText").isNotBlank(),
        byCreator = author?.optBoolean("isCreator") == true,
    )
}

/** Top-level comments (or one thread's replies) from a watch-next continuation response. */
internal fun parseNativeComments(root: JSONObject): NativeCommentPage {
    val entities = commentEntities(root)
    var countText: String? = null
    var continuation: String? = null
    val comments = mutableListOf<NativeComment>()
    for (item in continuationItems(root)) {
        item.optJSONObject("commentsHeaderRenderer")?.let { countText = nativeText(it.optJSONObject("countText")) }
        item.optJSONObject("commentThreadRenderer")?.let { thread ->
            val view = thread.optJSONObject("commentViewModel")?.optJSONObject("commentViewModel")
            nativeComment(view, thread.optJSONObject("replies"), entities)?.let(comments::add)
        }
        nativeComment(item.optJSONObject("commentViewModel"), null, entities)?.let(comments::add)
        item.optJSONObject("continuationItemRenderer")?.let { continuation = continuationToken(it) }
    }
    return NativeCommentPage(countText?.takeIf { it.isNotBlank() }, comments.distinctBy { it.id }, continuation)
}

private fun headerMetadata(header: JSONObject?): String {
    val rows = firstObject(header, "contentMetadataViewModel")?.optJSONArray("metadataRows") ?: return ""
    return (0 until rows.length())
        .flatMap { rowIndex ->
            val parts = rows.optJSONObject(rowIndex)?.optJSONArray("metadataParts") ?: JSONArray()
            (0 until parts.length()).mapNotNull { nativeText(parts.optJSONObject(it)?.optJSONObject("text")).takeIf(String::isNotBlank) }
        }.joinToString(" · ")
}

private fun selectedTab(root: JSONObject): JSONObject? {
    val tabs = root.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")?.optJSONArray("tabs") ?: return null
    for (index in 0 until tabs.length()) {
        val tab = tabs.optJSONObject(index)?.optJSONObject("tabRenderer") ?: continue
        if (tab.optBoolean("selected")) return tab.optJSONObject("content")
    }
    return null
}

internal fun parseNativeChannel(
    channelId: String,
    root: JSONObject,
): NativeChannelPage {
    val metadata = root.optJSONObject("metadata")?.optJSONObject("channelMetadataRenderer")
    val header = root.optJSONObject("header")
    val avatar = firstObject(header, "avatarViewModel")?.optJSONObject("image") ?: metadata?.optJSONObject("avatar")
    return NativeChannelPage(
        channel =
            NativeChannel(
                channelId = metadata?.optString("externalId")?.takeIf(::isNativeChannelId) ?: channelId,
                title =
                    metadata
                        ?.optString(
                            "title",
                        ).orEmpty()
                        .ifEmpty { header?.optJSONObject("pageHeaderRenderer")?.optString("pageTitle").orEmpty() },
                avatarUrl = bestImageUrl(avatar),
                detailText = headerMetadata(header),
            ),
        description = metadata?.optString("description").orEmpty().trim(),
        videos = withChannel(nativePageFrom(selectedTab(root)), metadata?.optString("title").orEmpty(), channelId),
    )
}

/** Videos on a channel's own page name that channel. */
internal fun withChannel(
    page: NativePage,
    title: String,
    channelId: String,
): NativePage =
    page.copy(
        items =
            page.items.map { item ->
                if (item is NativeVideo && item.channelName.isEmpty()) item.copy(channelName = title, channelId = channelId) else item
            },
    )

internal fun parseNativePlaylist(
    playlistId: String,
    root: JSONObject,
): NativePlaylistPage {
    val metadata = root.optJSONObject("metadata")?.optJSONObject("playlistMetadataRenderer")
    val page = nativePageFrom(selectedTab(root))
    val videos = page.copy(items = page.items.filterIsInstance<NativeVideo>())
    return NativePlaylistPage(
        playlist =
            NativePlaylist(
                playlistId = playlistId,
                title =
                    metadata?.optString("title").orEmpty().ifEmpty {
                        root
                            .optJSONObject("header")
                            ?.optJSONObject("pageHeaderRenderer")
                            ?.optString("pageTitle")
                            .orEmpty()
                    },
                channelName = headerMetadata(root.optJSONObject("header")),
                thumbnailVideoId = videos.items.firstOrNull()?.let { (it as NativeVideo).videoId },
            ),
        videos = videos,
    )
}

private val RSS_ENTRY = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
private val RSS_AUTHOR = Regex("<author>.*?<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL)

private fun rssTag(
    entry: String,
    tag: String,
): String =
    Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
        .find(entry)
        ?.groupValues
        ?.get(1)
        ?.let(::unescapeXml)
        ?.trim()
        .orEmpty()

private fun unescapeXml(value: String): String =
    value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

/** A channel's newest uploads from its public feed, each with its upload time in epoch ms. */
internal fun parseNativeChannelFeed(xml: String): List<Pair<NativeVideo, Long>> =
    RSS_ENTRY
        .findAll(xml)
        .mapNotNull { match ->
            val entry = match.groupValues[1]
            val videoId = rssTag(entry, "yt:videoId").takeIf(::isNativeVideoId) ?: return@mapNotNull null
            val published =
                runCatching {
                    java.time.OffsetDateTime
                        .parse(
                            rssTag(entry, "published"),
                        ).toInstant()
                        .toEpochMilli()
                }.getOrDefault(0L)
            NativeVideo(
                videoId = videoId,
                title = rssTag(entry, "title"),
                channelName =
                    RSS_AUTHOR
                        .find(entry)
                        ?.groupValues
                        ?.get(1)
                        ?.let(::unescapeXml)
                        ?.trim()
                        .orEmpty(),
                channelId = rssTag(entry, "yt:channelId").takeIf(::isNativeChannelId),
            ) to published
        }.toList()
