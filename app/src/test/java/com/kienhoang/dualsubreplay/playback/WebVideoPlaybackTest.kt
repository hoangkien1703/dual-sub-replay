package com.kienhoang.dualsubreplay.playback

import android.support.v4.media.session.PlaybackStateCompat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebVideoPlaybackTest {
    private val playing =
        WebVideoPlayback(
            videoId = "dQw4w9WgXcQ",
            title = "Video",
            paused = false,
            positionMs = 10_000,
            durationMs = 60_000,
            playbackRate = 1f,
        )
    private val paused = playing.copy(paused = true)

    private fun scriptResult(json: JSONObject): String = JSONObject.quote(json.toString())

    @Test
    fun parsesTheWatchPageVideo() {
        val raw =
            scriptResult(
                JSONObject()
                    .put("url", "https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=3")
                    .put("title", "(3) Learn Japanese - YouTube")
                    .put("paused", false)
                    .put("currentSecond", 12.5)
                    .put("durationSecond", 300.0)
                    .put("playbackRate", 1.5),
            )

        assertEquals(
            WebVideoPlayback("dQw4w9WgXcQ", "Learn Japanese", false, 12_500, 300_000, 1.5f),
            parseWebVideoPlayback(raw),
        )
    }

    @Test
    fun readsTheChannelNameForTheNotification() {
        val raw =
            scriptResult(
                JSONObject()
                    .put("url", "https://m.youtube.com/watch?v=dQw4w9WgXcQ")
                    .put("title", "Learn Japanese - YouTube")
                    .put("channel", "  Japanese\n  with Kien "),
            )

        assertEquals("Japanese with Kien", parseWebVideoPlayback(raw)!!.channel)
        val noChannel = scriptResult(JSONObject().put("url", "https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("", parseWebVideoPlayback(noChannel)!!.channel)
        // The page's media session names the channel only while it describes the open video.
        assertTrue(WEB_VIDEO_STATE_SCRIPT.contains("document.title.indexOf(meta.title) !== -1) return meta.artist;"))
    }

    @Test
    fun ignoresPagesWithoutAVideoAndOtherSites() {
        assertNull(parseWebVideoPlayback("null"))
        assertNull(parseWebVideoPlayback(null))
        assertNull(parseWebVideoPlayback("not json"))
        assertNull(parseWebVideoPlayback(scriptResult(JSONObject().put("url", "https://m.youtube.com/results?q=a"))))
        assertNull(parseWebVideoPlayback(scriptResult(JSONObject().put("url", "https://example.com/watch?v=dQw4w9WgXcQ"))))
    }

    @Test
    fun liveVideosHaveNoDurationAndNoSeek() {
        val raw =
            scriptResult(
                JSONObject()
                    .put("url", "https://m.youtube.com/watch?v=dQw4w9WgXcQ")
                    .put("paused", true)
                    .put("currentSecond", 4.0)
                    .put("durationSecond", JSONObject.NULL),
            )
        val live = parseWebVideoPlayback(raw)!!

        assertNull(live.durationMs)
        assertEquals(0L, sessionActions(live) and PlaybackStateCompat.ACTION_SEEK_TO)
    }

    @Test
    fun titleDropsYouTubeSuffixAndUnreadCount() {
        assertEquals("Learn Japanese", cleanVideoTitle("Learn Japanese - YouTube"))
        assertEquals("Learn Japanese", cleanVideoTitle("(12) Learn  Japanese - YouTube"))
        assertEquals("(Live) News", cleanVideoTitle("(Live) News - YouTube"))
        assertEquals("", cleanVideoTitle("YouTube"))
        assertEquals("", cleanVideoTitle(null))
    }

    @Test
    fun sessionStateAndActionsFollowThePageVideo() {
        assertEquals(PlaybackStateCompat.STATE_NONE, sessionPlaybackState(null))
        assertEquals(PlaybackStateCompat.STATE_PLAYING, sessionPlaybackState(playing))
        assertEquals(PlaybackStateCompat.STATE_PAUSED, sessionPlaybackState(paused))
        assertEquals(0L, sessionActions(null))

        val playingActions = sessionActions(playing)
        assertTrue(playingActions and PlaybackStateCompat.ACTION_PAUSE != 0L)
        assertTrue(playingActions and PlaybackStateCompat.ACTION_PLAY_PAUSE != 0L)
        assertTrue(playingActions and PlaybackStateCompat.ACTION_SEEK_TO != 0L)
        assertTrue(sessionActions(paused) and PlaybackStateCompat.ACTION_PLAY != 0L)
    }

    @Test
    fun foregroundWhilePlayingAndTenMinutesAfterPausing() {
        assertFalse(shouldStayInForeground(null, null, 0))
        assertTrue(shouldStayInForeground(playing, null, 0))
        assertTrue(shouldStayInForeground(paused, 1_000, 1_000 + PAUSED_FOREGROUND_GRACE_MS - 1))
        assertFalse(shouldStayInForeground(paused, 1_000, 1_000 + PAUSED_FOREGROUND_GRACE_MS))
    }

    @Test
    fun appPausesOnlyVideosThatAreNotPlaying() {
        assertTrue(playing.isPlaying())
        assertFalse(paused.isPlaying())
        assertFalse((null as WebVideoPlayback?).isPlaying())
    }

    @Test
    fun sessionUpdatesOnlyWhenStateChangesOrPositionDrifts() {
        assertTrue(sessionNeedsUpdate(null, 0, playing, 0))
        // One second later, one second further: the session already extrapolates this.
        assertFalse(sessionNeedsUpdate(playing, 0, playing.copy(positionMs = 11_000), 1_000))
        assertFalse(sessionNeedsUpdate(paused, 0, paused, 5_000))
        assertTrue(sessionNeedsUpdate(playing, 0, playing.copy(positionMs = 40_000), 1_000))
        assertTrue(sessionNeedsUpdate(playing, 0, paused.copy(positionMs = 11_000), 1_000))
        assertTrue(sessionNeedsUpdate(playing, 0, playing.copy(title = "Next", positionMs = 11_000), 1_000))
        assertTrue(sessionNeedsUpdate(playing, 0, playing.copy(channel = "Channel", positionMs = 11_000), 1_000))
    }

    @Test
    fun everyScriptChecksTheYouTubeOrigin() {
        val scripts =
            listOf(
                WEB_VIDEO_STATE_SCRIPT,
                webVideoCommandScript(WebVideoCommand.Play),
                webVideoCommandScript(WebVideoCommand.Pause),
                webVideoCommandScript(WebVideoCommand.SeekTo(5_000)),
                WEB_KEEP_PLAYING_WHEN_HIDDEN_SCRIPT,
            )
        scripts.forEach { script ->
            assertTrue(script.contains("window.location.protocol !== 'https:'"))
            assertTrue(script.contains("host === 'youtube.com' || host.endsWith('.youtube.com')"))
        }
        assertTrue(webVideoCommandScript(WebVideoCommand.SeekTo(5_000)).contains("video.currentTime = 5.0;"))
        assertTrue(webVideoCommandScript(WebVideoCommand.SeekTo(-1)).contains("video.currentTime = 0.0;"))
    }

    @Test
    fun earphoneToggleSendsTheOppositeCommand() {
        val sent = mutableListOf<WebVideoCommand>()
        val token = WebVideoBridge.bindCommands { sent += it }
        try {
            WebVideoBridge.publish(playing)
            WebVideoBridge.toggle()
            WebVideoBridge.publish(paused)
            WebVideoBridge.toggle()
            assertEquals(listOf(WebVideoCommand.Pause, WebVideoCommand.Play), sent)
        } finally {
            WebVideoBridge.unbindCommands(token)
        }
        assertNull(WebVideoBridge.playback.value)
    }

    @Test
    fun pageIsAlwaysToldItIsVisibleOnYouTubeOnly() {
        val script = WEB_KEEP_PLAYING_WHEN_HIDDEN_SCRIPT
        assertTrue(script.contains("visible('hidden', false);"))
        assertTrue(script.contains("visible('visibilityState', 'visible');"))
        assertTrue(script.contains("window.addEventListener(type, swallow, true);"))
        assertTrue(script.contains("'visibilitychange', 'webkitvisibilitychange'"))
        assertEquals(setOf("https://youtube.com", "https://*.youtube.com"), YOUTUBE_DOCUMENT_START_ORIGINS)
    }
}
