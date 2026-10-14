package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.NativePage
import com.kienhoang.dualsubreplay.data.NativeVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeModeStateTest {
    @Test
    fun watchScreensReplaceEachOtherInsteadOfPilingUp() {
        val home = listOf<NativeScreen>(NativeScreen.Home)
        val search = pushNativeScreen(home, NativeScreen.Search("lessons"))
        val watchA = pushNativeScreen(search, NativeScreen.Watch("aaaaaaaaaaa"))
        val watchB = pushNativeScreen(watchA, NativeScreen.Watch("bbbbbbbbbbb"))

        assertEquals(listOf(NativeScreen.Home, NativeScreen.Search("lessons"), NativeScreen.Watch("bbbbbbbbbbb")), watchB)
        assertEquals(watchB, pushNativeScreen(watchB, NativeScreen.Watch("bbbbbbbbbbb")))
        assertEquals(search, popNativeScreen(watchB))
        assertEquals(home, popNativeScreen(home))
    }

    @Test
    fun reopeningAnEarlierWatchScreenMovesItToTheTop() {
        val stack =
            listOf(NativeScreen.Home, NativeScreen.Watch("aaaaaaaaaaa"), NativeScreen.Channel("UCabcdefghijklmnopqrstuv"))
        val reopened = pushNativeScreen(stack, NativeScreen.Watch("aaaaaaaaaaa"))
        assertEquals(
            listOf(NativeScreen.Home, NativeScreen.Channel("UCabcdefghijklmnopqrstuv"), NativeScreen.Watch("aaaaaaaaaaa")),
            reopened,
        )
    }

    @Test
    fun theStackStaysShort() {
        var stack = listOf<NativeScreen>(NativeScreen.Home)
        repeat(50) { stack = pushNativeScreen(stack, NativeScreen.Search("q$it")) }
        assertEquals(30, stack.size)
        assertEquals(NativeScreen.Home, stack.first())
        assertEquals(NativeScreen.Search("q21"), stack[1])
        assertEquals(NativeScreen.Search("q49"), stack.last())
    }

    @Test
    fun subscriptionUploadsMergeNewestFirstWithoutRepeats() {
        val a = NativeVideo("aaaaaaaaaaa", "A")
        val b = NativeVideo("bbbbbbbbbbb", "B")
        val c = NativeVideo("ccccccccccc", "C")
        val merged = mergeSubscriptionUploads(listOf(listOf(a to 10L, b to 30L), listOf(c to 20L, b to 30L)), limit = 2)
        assertEquals(listOf("B", "C"), merged.map { it.title })
    }

    @Test
    fun nextPagesAppendWithoutRepeatsAndKeepTheirToken() {
        val a = NativeVideo("aaaaaaaaaaa", "A")
        val b = NativeVideo("bbbbbbbbbbb", "B")
        val first = NativePage(listOf(a), "one")
        val appended = appendPage(first, NativePage(listOf(a, b), "two"))
        assertEquals(listOf(a, b), appended.items)
        assertEquals("two", appended.continuation)
        assertEquals(first, appendPage(first, null))
        assertNull(appendPage(first, NativePage(emptyList(), null)).continuation)
    }

    @Test
    fun imagesLoadOnlyFromYouTubeImageHostsOverHttps() {
        assertTrue(isTrustedNativeImageUrl(nativeThumbnailUrl("aaaaaaaaaaa")))
        assertTrue(isTrustedNativeImageUrl("https://i9.ytimg.com/vi/aaaaaaaaaaa/mqdefault.jpg"))
        assertTrue(isTrustedNativeImageUrl("https://yt3.ggpht.com/avatar=s88"))
        assertTrue(isTrustedNativeImageUrl("https://yt3.googleusercontent.com/avatar"))
        assertFalse(isTrustedNativeImageUrl("http://i.ytimg.com/vi/aaaaaaaaaaa/mqdefault.jpg"))
        assertFalse(isTrustedNativeImageUrl("https://i.ytimg.com:8443/vi/aaaaaaaaaaa/mqdefault.jpg"))
        assertFalse(isTrustedNativeImageUrl("https://i.ytimg.com.example.com/x.jpg"))
        assertFalse(isTrustedNativeImageUrl("https://user@i.ytimg.com/x.jpg"))
        assertFalse(isTrustedNativeImageUrl(null))
    }
}
