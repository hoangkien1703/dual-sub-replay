package com.kienhoang.dualsubreplay.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLibraryTest {
    private val channelId = "UCabcdefghijklmnopqrstuv"

    @Test
    fun withFirstMovesRepeatsToTheFrontAndKeepsTheLimit() {
        val list = listOf("a", "b", "c")
        assertEquals(listOf("c", "a", "b"), withFirst(list, "c", 10) { it })
        assertEquals(listOf("d", "a"), withFirst(list, "d", 2) { it })
        assertEquals(listOf("B", "a", "c"), withFirst(list, "B", 10) { it.lowercase() })
    }

    @Test
    fun videosAndChannelsSurviveAJsonRoundTripAndBadIdsAreDropped() {
        val video = NativeVideo("aaaaaaaaaaa", "Title", "Teacher", channelId, "3:21")
        assertEquals(video, nativeVideoFromJson(nativeVideoJson(video)))
        assertNull(nativeVideoFromJson(nativeVideoJson(video.copy(videoId = "bad"))))
        val noChannel = video.copy(channelId = null)
        assertEquals(noChannel, nativeVideoFromJson(nativeVideoJson(noChannel)))

        val channel = NativeChannel(channelId, "Teacher", "https://yt3.ggpht.com/a")
        assertEquals(channel, nativeChannelFromJson(nativeChannelJson(channel)))
        assertNull(nativeChannelFromJson(nativeChannelJson(channel.copy(avatarUrl = "http://example.com")))?.avatarUrl)
    }

    @Test
    fun libraryKeepsHistoryWatchLaterSubscriptionsAndSearchesOnThePhone() {
        val preferences = MemoryPreferences()
        val library = NativeLibrary(preferences)
        val first = NativeVideo("aaaaaaaaaaa", "")
        val second = NativeVideo("bbbbbbbbbbb", "Second")

        library.watched(first)
        library.watched(second)
        library.watched(first)
        library.updateDetails(first.copy(title = "First", channelName = "Teacher"))
        library.toggleWatchLater(second)
        library.toggleSubscription(NativeChannel(channelId, "Teacher", detailText = "10K subscribers"))
        library.searched("  Lessons ")
        library.searched("lessons")

        val reloaded = NativeLibrary(preferences).state.value
        assertEquals(listOf("First", "Second"), reloaded.history.map { it.title })
        assertEquals(listOf("bbbbbbbbbbb"), reloaded.watchLater.map { it.videoId })
        assertEquals("", reloaded.subscriptions.single().detailText)
        assertEquals(listOf("lessons"), reloaded.searches)

        library.toggleWatchLater(second)
        library.toggleSubscription(NativeChannel(channelId, "Teacher"))
        library.removeFromHistory("bbbbbbbbbbb")
        library.forgetSearch("lessons")
        val cleared = NativeLibrary(preferences).state.value
        assertTrue(cleared.watchLater.isEmpty())
        assertTrue(cleared.subscriptions.isEmpty())
        assertEquals(listOf("aaaaaaaaaaa"), cleared.history.map { it.videoId })
        assertTrue(cleared.searches.isEmpty())
    }

    @Test
    fun unreadableSavedDataStartsEmpty() {
        val preferences = MemoryPreferences()
        preferences.edit().putString("history", "not json").apply()
        assertTrue(
            NativeLibrary(preferences)
                .state.value.history
                .isEmpty(),
        )
    }
}

/** Just enough of SharedPreferences for the library: strings, applied at once. */
private class MemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(
        key: String,
        defValue: String?,
    ): String? = values[key] as? String ?: defValue

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = defValues

    override fun getInt(
        key: String,
        defValue: Int,
    ): Int = defValue

    override fun getLong(
        key: String,
        defValue: Long,
    ): Long = defValue

    override fun getFloat(
        key: String,
        defValue: Float,
    ): Float = defValue

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean = defValue

    override fun contains(key: String): Boolean = key in values

    override fun edit(): SharedPreferences.Editor =
        object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()

            override fun putString(
                key: String,
                value: String?,
            ) = apply { pending[key] = value }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ) = this

            override fun putInt(
                key: String,
                value: Int,
            ) = this

            override fun putLong(
                key: String,
                value: Long,
            ) = this

            override fun putFloat(
                key: String,
                value: Float,
            ) = this

            override fun putBoolean(
                key: String,
                value: Boolean,
            ) = this

            override fun remove(key: String) = apply { pending[key] = null }

            override fun clear() = apply { values.clear() }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                values.putAll(pending)
                pending.clear()
            }
        }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
}
