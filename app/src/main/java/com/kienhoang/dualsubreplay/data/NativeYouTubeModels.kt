package com.kienhoang.dualsubreplay.data

/** One entry in a native mode list: a video, a channel or a playlist. */
internal sealed interface NativeItem {
    val key: String
}

internal data class NativeVideo(
    val videoId: String,
    val title: String,
    val channelName: String = "",
    val channelId: String? = null,
    val durationText: String = "",
    val detailText: String = "",
    val live: Boolean = false,
) : NativeItem {
    override val key: String get() = "video:$videoId"
}

internal data class NativeChannel(
    val channelId: String,
    val title: String,
    val avatarUrl: String? = null,
    val detailText: String = "",
) : NativeItem {
    override val key: String get() = "channel:$channelId"
}

internal data class NativePlaylist(
    val playlistId: String,
    val title: String,
    val channelName: String = "",
    val countText: String = "",
    val thumbnailVideoId: String? = null,
) : NativeItem {
    override val key: String get() = "playlist:$playlistId"
}

/** A page of items and the token for the next page, when there is one. */
internal data class NativePage(
    val items: List<NativeItem>,
    val continuation: String? = null,
)

internal data class NativeWatchDetails(
    val videoId: String,
    val title: String,
    val channel: NativeChannel?,
    val viewsText: String = "",
    val dateText: String = "",
    val description: String = "",
    val related: NativePage = NativePage(emptyList()),
    val commentsContinuation: String? = null,
)

internal data class NativeComment(
    val id: String,
    val author: String,
    val avatarUrl: String? = null,
    val text: String,
    val publishedText: String = "",
    val likesText: String = "",
    val replyCount: Int = 0,
    val repliesContinuation: String? = null,
    val pinned: Boolean = false,
    val byCreator: Boolean = false,
)

internal data class NativeCommentPage(
    val countText: String? = null,
    val comments: List<NativeComment>,
    val continuation: String? = null,
)

internal data class NativeChannelPage(
    val channel: NativeChannel,
    val description: String = "",
    val videos: NativePage = NativePage(emptyList()),
)

internal data class NativePlaylistPage(
    val playlist: NativePlaylist,
    val videos: NativePage,
)
