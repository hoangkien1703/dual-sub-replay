package com.kienhoang.dualsubreplay.data

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** What native mode keeps on the phone in place of a YouTube account: history, Watch later, subscriptions, searches. */
internal data class NativeLibraryState(
    val history: List<NativeVideo> = emptyList(),
    val watchLater: List<NativeVideo> = emptyList(),
    val subscriptions: List<NativeChannel> = emptyList(),
    val searches: List<String> = emptyList(),
)

internal const val NATIVE_HISTORY_LIMIT = 200
internal const val NATIVE_WATCH_LATER_LIMIT = 500
internal const val NATIVE_SUBSCRIPTION_LIMIT = 300
internal const val NATIVE_SEARCH_LIMIT = 12

/** Puts [item] first, drops older copies with the same key, and keeps at most [limit]. */
internal fun <T> withFirst(
    list: List<T>,
    item: T,
    limit: Int,
    key: (T) -> Any,
): List<T> = (listOf(item) + list.filterNot { key(it) == key(item) }).take(limit)

internal fun nativeVideoJson(video: NativeVideo): JSONObject =
    JSONObject()
        .put("videoId", video.videoId)
        .put("title", video.title)
        .put("channelName", video.channelName)
        .put("channelId", video.channelId ?: JSONObject.NULL)
        .put("durationText", video.durationText)

internal fun nativeVideoFromJson(json: JSONObject?): NativeVideo? {
    val videoId = json?.optString("videoId")?.takeIf(::isNativeVideoId) ?: return null
    return NativeVideo(
        videoId = videoId,
        title = json.optString("title"),
        channelName = json.optString("channelName"),
        channelId = json.optString("channelId").takeIf(::isNativeChannelId),
        durationText = json.optString("durationText"),
    )
}

internal fun nativeChannelJson(channel: NativeChannel): JSONObject =
    JSONObject()
        .put("channelId", channel.channelId)
        .put("title", channel.title)
        .put("avatarUrl", channel.avatarUrl ?: JSONObject.NULL)

internal fun nativeChannelFromJson(json: JSONObject?): NativeChannel? {
    val channelId = json?.optString("channelId")?.takeIf(::isNativeChannelId) ?: return null
    return NativeChannel(
        channelId = channelId,
        title = json.optString("title"),
        avatarUrl = json.optString("avatarUrl").takeIf { it.startsWith("https://") },
    )
}

private fun <T> decodeList(
    raw: String?,
    decode: (JSONObject?) -> T?,
): List<T> =
    runCatching {
        val array = JSONArray(raw ?: return emptyList())
        (0 until array.length()).mapNotNull { decode(array.optJSONObject(it)) }
    }.getOrDefault(emptyList())

private fun <T> encodeList(
    list: List<T>,
    encode: (T) -> JSONObject,
): String = JSONArray().apply { list.forEach { put(encode(it)) } }.toString()

internal class NativeLibrary(
    private val preferences: SharedPreferences,
) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<NativeLibraryState> = _state.asStateFlow()

    private fun load() =
        NativeLibraryState(
            history = decodeList(preferences.getString(HISTORY, null), ::nativeVideoFromJson),
            watchLater = decodeList(preferences.getString(WATCH_LATER, null), ::nativeVideoFromJson),
            subscriptions = decodeList(preferences.getString(SUBSCRIPTIONS, null), ::nativeChannelFromJson),
            searches =
                runCatching {
                    val array = JSONArray(preferences.getString(SEARCHES, null) ?: "[]")
                    (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
                }.getOrDefault(emptyList()),
        )

    private fun save(change: (NativeLibraryState) -> NativeLibraryState) {
        _state.update(change)
        val current = _state.value
        preferences
            .edit()
            .putString(HISTORY, encodeList(current.history, ::nativeVideoJson))
            .putString(WATCH_LATER, encodeList(current.watchLater, ::nativeVideoJson))
            .putString(SUBSCRIPTIONS, encodeList(current.subscriptions, ::nativeChannelJson))
            .putString(SEARCHES, JSONArray(current.searches).toString())
            .apply()
    }

    fun watched(video: NativeVideo) = save { it.copy(history = withFirst(it.history, video, NATIVE_HISTORY_LIMIT) { v -> v.videoId }) }

    /** Fills in a history entry that started with only a video id once its details load. */
    fun updateDetails(video: NativeVideo) =
        save { state ->
            state.copy(history = state.history.map { if (it.videoId == video.videoId && it.title.isBlank()) video else it })
        }

    fun clearHistory() = save { it.copy(history = emptyList()) }

    fun removeFromHistory(videoId: String) = save { it.copy(history = it.history.filterNot { v -> v.videoId == videoId }) }

    fun toggleWatchLater(video: NativeVideo) =
        save { state ->
            if (state.watchLater.any { it.videoId == video.videoId }) {
                state.copy(watchLater = state.watchLater.filterNot { it.videoId == video.videoId })
            } else {
                state.copy(watchLater = withFirst(state.watchLater, video, NATIVE_WATCH_LATER_LIMIT) { it.videoId })
            }
        }

    fun toggleSubscription(channel: NativeChannel) =
        save { state ->
            if (state.subscriptions.any { it.channelId == channel.channelId }) {
                state.copy(subscriptions = state.subscriptions.filterNot { it.channelId == channel.channelId })
            } else {
                state.copy(
                    subscriptions =
                        withFirst(
                            state.subscriptions,
                            channel.copy(detailText = ""),
                            NATIVE_SUBSCRIPTION_LIMIT,
                        ) { it.channelId },
                )
            }
        }

    fun searched(query: String) {
        val clean = query.trim().take(200)
        if (clean.isEmpty()) return
        save { it.copy(searches = withFirst(it.searches, clean, NATIVE_SEARCH_LIMIT) { s -> s.lowercase() }) }
    }

    fun forgetSearch(query: String) = save { it.copy(searches = it.searches.filterNot { s -> s == query }) }

    private companion object {
        const val HISTORY = "history"
        const val WATCH_LATER = "watch_later"
        const val SUBSCRIPTIONS = "subscriptions"
        const val SEARCHES = "searches"
    }
}
