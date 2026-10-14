package com.kienhoang.dualsubreplay.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kienhoang.dualsubreplay.data.InnertubeNativeYouTubeSource
import com.kienhoang.dualsubreplay.data.NativeChannel
import com.kienhoang.dualsubreplay.data.NativeChannelPage
import com.kienhoang.dualsubreplay.data.NativeComment
import com.kienhoang.dualsubreplay.data.NativeCommentPage
import com.kienhoang.dualsubreplay.data.NativeItem
import com.kienhoang.dualsubreplay.data.NativeLibrary
import com.kienhoang.dualsubreplay.data.NativePage
import com.kienhoang.dualsubreplay.data.NativePlaylistPage
import com.kienhoang.dualsubreplay.data.NativeVideo
import com.kienhoang.dualsubreplay.data.NativeWatchDetails
import com.kienhoang.dualsubreplay.data.NativeYouTubeSource
import com.kienhoang.dualsubreplay.data.isNativeVideoId
import com.kienhoang.dualsubreplay.data.withChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Where native mode is. The watch screen shows the page video above native details. */
internal sealed interface NativeScreen {
    data object Home : NativeScreen

    data class Search(
        val query: String,
    ) : NativeScreen

    data class Watch(
        val videoId: String,
    ) : NativeScreen

    data class Channel(
        val channelId: String,
    ) : NativeScreen

    data class Playlist(
        val playlistId: String,
    ) : NativeScreen

    data object Library : NativeScreen
}

/** A list or page that loads from YouTube: what it has so far, whether it is loading, and whether the last load failed. */
internal data class NativeLoad<T>(
    val value: T? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
)

internal data class NativeHomeFeed(
    val subscriptionVideos: List<NativeVideo> = emptyList(),
    val forYou: List<NativeVideo> = emptyList(),
    /** The watched video [forYou] is based on. */
    val basedOnVideoId: String? = null,
)

internal data class NativeCommentsState(
    val page: NativeLoad<NativeCommentPage> = NativeLoad(),
    val replies: Map<String, NativeLoad<NativeCommentPage>> = emptyMap(),
)

internal const val NATIVE_MODE_ENABLED_PREFERENCE = "native_mode_enabled"

private const val MAX_NATIVE_SCREENS = 30

/**
 * Pushes [screen] unless it is already on top; a new watch screen replaces a watch screen on top.
 * A long stack drops its oldest screens but keeps the first one, so back always ends at Home.
 */
internal fun pushNativeScreen(
    stack: List<NativeScreen>,
    screen: NativeScreen,
): List<NativeScreen> {
    if (stack.lastOrNull() == screen) return stack
    val base = if (screen is NativeScreen.Watch && stack.lastOrNull() is NativeScreen.Watch) stack.dropLast(1) else stack
    val next = base.filterNot { it == screen && it is NativeScreen.Watch } + screen
    return if (next.size <= MAX_NATIVE_SCREENS) next else listOf(next.first()) + next.takeLast(MAX_NATIVE_SCREENS - 1)
}

internal fun popNativeScreen(stack: List<NativeScreen>): List<NativeScreen> = if (stack.size > 1) stack.dropLast(1) else stack

/** Newest uploads first across channels, at most [limit]. */
internal fun mergeSubscriptionUploads(
    uploads: List<List<Pair<NativeVideo, Long>>>,
    limit: Int = 60,
): List<NativeVideo> =
    uploads
        .flatten()
        .sortedByDescending { it.second }
        .map { it.first }
        .distinctBy { it.videoId }
        .take(limit)

internal class NativeModeViewModel internal constructor(
    application: Application,
    private val source: NativeYouTubeSource,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, InnertubeNativeYouTubeSource())

    private val preferences = application.getSharedPreferences("dual_sub_preferences", 0)
    val library = NativeLibrary(application.getSharedPreferences("native_mode_library", 0))

    private val _enabled = MutableStateFlow(preferences.getBoolean(NATIVE_MODE_ENABLED_PREFERENCE, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _stack = MutableStateFlow<List<NativeScreen>>(listOf(NativeScreen.Home))
    val stack: StateFlow<List<NativeScreen>> = _stack.asStateFlow()

    private val _search = MutableStateFlow<Map<String, NativeLoad<NativePage>>>(emptyMap())
    val search: StateFlow<Map<String, NativeLoad<NativePage>>> = _search.asStateFlow()

    private val _home = MutableStateFlow(NativeLoad<NativeHomeFeed>())
    val home: StateFlow<NativeLoad<NativeHomeFeed>> = _home.asStateFlow()

    private val _watch = MutableStateFlow<Map<String, NativeLoad<NativeWatchDetails>>>(emptyMap())
    val watch: StateFlow<Map<String, NativeLoad<NativeWatchDetails>>> = _watch.asStateFlow()

    private val _comments = MutableStateFlow<Map<String, NativeCommentsState>>(emptyMap())
    val comments: StateFlow<Map<String, NativeCommentsState>> = _comments.asStateFlow()

    private val _channels = MutableStateFlow<Map<String, NativeLoad<NativeChannelPage>>>(emptyMap())
    val channels: StateFlow<Map<String, NativeLoad<NativeChannelPage>>> = _channels.asStateFlow()

    private val _playlists = MutableStateFlow<Map<String, NativeLoad<NativePlaylistPage>>>(emptyMap())
    val playlists: StateFlow<Map<String, NativeLoad<NativePlaylistPage>>> = _playlists.asStateFlow()

    private val _failedPages = MutableStateFlow<Set<String>>(emptySet())

    /** Continuation tokens whose next page failed to load, so the list shows Try again instead of a spinner. */
    val failedPages: StateFlow<Set<String>> = _failedPages.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    /** Turns native mode on or off; turning it on while a video plays opens that video's watch screen. */
    fun setEnabled(
        enabled: Boolean,
        activeVideoId: String? = null,
    ) {
        preferences.edit().putBoolean(NATIVE_MODE_ENABLED_PREFERENCE, enabled).apply()
        _enabled.value = enabled
        if (!enabled) return
        _stack.value = listOf(NativeScreen.Home)
        activeVideoId?.takeIf(::isNativeVideoId)?.let { open(NativeScreen.Watch(it)) }
    }

    fun open(screen: NativeScreen) {
        _stack.update { pushNativeScreen(it, screen) }
        when (screen) {
            NativeScreen.Home -> loadHome()
            is NativeScreen.Search -> {
                library.searched(screen.query)
                loadSearch(screen.query)
            }
            is NativeScreen.Watch -> loadWatch(screen.videoId)
            is NativeScreen.Channel -> loadChannel(screen.channelId)
            is NativeScreen.Playlist -> loadPlaylist(screen.playlistId)
            NativeScreen.Library -> Unit
        }
    }

    fun goHome() {
        _stack.value = listOf(NativeScreen.Home)
        loadHome()
    }

    /** Returns false when there is nothing to go back to inside native mode. */
    fun back(): Boolean {
        val before = _stack.value
        _stack.update(::popNativeScreen)
        return _stack.value != before
    }

    fun openVideo(video: NativeVideo) {
        library.watched(video)
        open(NativeScreen.Watch(video.videoId))
    }

    /** The page moved to another video by itself (autoplay, end screen); follow it. */
    fun followPageVideo(videoId: String) {
        if (!isNativeVideoId(videoId)) return
        val top = _stack.value.lastOrNull()
        if (top is NativeScreen.Watch && top.videoId != videoId) {
            library.watched(NativeVideo(videoId = videoId, title = ""))
            open(NativeScreen.Watch(videoId))
        }
    }

    private fun launchOnce(
        key: String,
        block: suspend () -> Unit,
    ) {
        if (jobs[key]?.isActive == true) return
        jobs[key] = viewModelScope.launch { block() }
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }

    private fun pageLoaded(
        token: String,
        ok: Boolean,
    ) {
        _failedPages.update { if (ok) it - token else it + token }
    }

    /** Clears a failed next page so the list asks for it again. */
    fun retryPage(token: String) {
        _failedPages.update { it - token }
    }

    /** Loads the home feed, again whenever the most recently watched video changed since the last load. */
    fun loadHome(force: Boolean = false) {
        val current = _home.value
        val lastWatchedId =
            library.state.value.history
                .firstOrNull()
                ?.videoId
        val upToDate = current.value != null && current.value.basedOnVideoId == lastWatchedId
        if (!force && (current.loading || upToDate)) return
        launchOnce("home") {
            _home.update { it.copy(loading = true, failed = false) }
            val subscriptions =
                library.state.value.subscriptions
                    .take(40)
            val gate = Semaphore(6)
            val uploads =
                subscriptions
                    .map { channel ->
                        viewModelScope.async { gate.withPermit { attempt { source.latestUploads(channel.channelId) }.orEmpty() } }
                    }.awaitAll()
            val lastWatched =
                library.state.value.history
                    .firstOrNull()
            val forYou =
                lastWatched
                    ?.let { video ->
                        attempt { source.watch(video.videoId) }?.related?.items?.filterIsInstance<NativeVideo>()
                    }.orEmpty()
            val feed = NativeHomeFeed(mergeSubscriptionUploads(uploads), forYou, lastWatched?.videoId)
            val failed = subscriptions.isNotEmpty() && uploads.all { it.isEmpty() } && forYou.isEmpty() && lastWatched != null
            _home.value = NativeLoad(feed, loading = false, failed = failed)
        }
    }

    fun loadSearch(
        query: String,
        force: Boolean = false,
    ) {
        val current = _search.value[query]
        if (!force && current != null && (current.loading || current.value != null)) return
        launchOnce("search:$query") {
            _search.update { it + (query to NativeLoad(loading = true)) }
            val page = attempt { source.search(query) }
            _search.update { it + (query to NativeLoad(page, failed = page == null)) }
        }
    }

    fun moreSearch(query: String) {
        val current = _search.value[query] ?: return
        val token = current.value?.continuation ?: return
        if (current.loading) return
        launchOnce("search-more:$query") {
            _search.update { it + (query to current.copy(loading = true)) }
            val next = attempt { source.moreSearch(token) }
            pageLoaded(token, next != null)
            _search.update { map ->
                val existing = map[query]?.value ?: NativePage(emptyList())
                map + (query to NativeLoad(appendPage(existing, next), failed = next == null))
            }
        }
    }

    fun loadWatch(
        videoId: String,
        force: Boolean = false,
    ) {
        val current = _watch.value[videoId]
        if (!force && current != null && (current.loading || current.value != null)) return
        launchOnce("watch:$videoId") {
            _watch.update { it + (videoId to NativeLoad(loading = true)) }
            val details = attempt { source.watch(videoId) }
            _watch.update { it + (videoId to NativeLoad(details, failed = details == null)) }
            details?.let {
                library.updateDetails(
                    NativeVideo(videoId, it.title, it.channel?.title.orEmpty(), it.channel?.channelId),
                )
            }
        }
    }

    fun moreRelated(videoId: String) {
        val current = _watch.value[videoId] ?: return
        val details = current.value ?: return
        val token = details.related.continuation ?: return
        launchOnce("related:$videoId") {
            val next = attempt { source.moreRelated(token) }
            pageLoaded(token, next != null)
            if (next == null) return@launchOnce
            _watch.update { map ->
                val latest = map[videoId]?.value ?: return@update map
                map + (videoId to NativeLoad(latest.copy(related = appendPage(latest.related, next))))
            }
        }
    }

    fun loadComments(
        videoId: String,
        more: Boolean = false,
    ) {
        val state = _comments.value[videoId] ?: NativeCommentsState()
        if (state.page.loading) return
        if (!more && state.page.value != null) return
        val token =
            if (more) {
                state.page.value?.continuation
            } else {
                _watch.value[videoId]?.value?.commentsContinuation
            } ?: return
        launchOnce("comments:$videoId:$more") {
            _comments.update { it + (videoId to state.copy(page = state.page.copy(loading = true, failed = false))) }
            val next = attempt { source.comments(token) }
            if (more) pageLoaded(token, next != null)
            _comments.update { map ->
                val latest = map[videoId] ?: NativeCommentsState()
                val merged =
                    if (next == null) {
                        latest.page.copy(loading = false, failed = true)
                    } else {
                        val existing = latest.page.value
                        NativeLoad(
                            NativeCommentPage(
                                countText = existing?.countText ?: next.countText,
                                comments = (existing?.comments.orEmpty() + next.comments).distinctBy { it.id },
                                continuation = next.continuation,
                            ),
                        )
                    }
                map + (videoId to latest.copy(page = merged))
            }
        }
    }

    fun loadReplies(
        videoId: String,
        comment: NativeComment,
    ) {
        val token = comment.repliesContinuation ?: return
        val existing = _comments.value[videoId]?.replies?.get(comment.id)
        if (existing?.loading == true || existing?.value != null) return
        launchOnce("replies:${comment.id}") {
            _comments.update { map ->
                val latest = map[videoId] ?: NativeCommentsState()
                map + (videoId to latest.copy(replies = latest.replies + (comment.id to NativeLoad(loading = true))))
            }
            val replies = attempt { source.comments(token) }
            _comments.update { map ->
                val latest = map[videoId] ?: NativeCommentsState()
                map + (videoId to latest.copy(replies = latest.replies + (comment.id to NativeLoad(replies, failed = replies == null))))
            }
        }
    }

    fun loadChannel(
        channelId: String,
        force: Boolean = false,
    ) {
        val current = _channels.value[channelId]
        if (!force && current != null && (current.loading || current.value != null)) return
        launchOnce("channel:$channelId") {
            _channels.update { it + (channelId to NativeLoad(loading = true)) }
            val page = attempt { source.channel(channelId) }
            _channels.update { it + (channelId to NativeLoad(page, failed = page == null)) }
        }
    }

    fun moreChannelVideos(channelId: String) {
        val page = _channels.value[channelId]?.value ?: return
        val token = page.videos.continuation ?: return
        launchOnce("channel-more:$channelId") {
            val next = attempt { source.moreBrowse(token) }
            pageLoaded(token, next != null)
            if (next == null) return@launchOnce
            _channels.update { map ->
                val latest = map[channelId]?.value ?: return@update map
                val named = withChannel(next, latest.channel.title, latest.channel.channelId)
                map + (channelId to NativeLoad(latest.copy(videos = appendPage(latest.videos, named))))
            }
        }
    }

    fun loadPlaylist(
        playlistId: String,
        force: Boolean = false,
    ) {
        val current = _playlists.value[playlistId]
        if (!force && current != null && (current.loading || current.value != null)) return
        launchOnce("playlist:$playlistId") {
            _playlists.update { it + (playlistId to NativeLoad(loading = true)) }
            val page = attempt { source.playlist(playlistId) }
            _playlists.update { it + (playlistId to NativeLoad(page, failed = page == null)) }
        }
    }

    fun morePlaylistVideos(playlistId: String) {
        val page = _playlists.value[playlistId]?.value ?: return
        val token = page.videos.continuation ?: return
        launchOnce("playlist-more:$playlistId") {
            val next = attempt { source.moreBrowse(token) }
            pageLoaded(token, next != null)
            if (next == null) return@launchOnce
            _playlists.update { map ->
                val latest = map[playlistId]?.value ?: return@update map
                val videos = next.copy(items = next.items.filterIsInstance<NativeVideo>())
                map + (playlistId to NativeLoad(latest.copy(videos = appendPage(latest.videos, videos))))
            }
        }
    }

    fun toggleSubscription(channel: NativeChannel) {
        library.toggleSubscription(channel)
        _home.value = NativeLoad()
    }
}

internal fun appendPage(
    existing: NativePage,
    next: NativePage?,
): NativePage =
    if (next == null) {
        existing.copy(continuation = existing.continuation)
    } else {
        NativePage((existing.items + next.items).distinctBy(NativeItem::key), next.continuation)
    }
