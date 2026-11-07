package com.kienhoang.dualsubreplay.playback

import android.support.v4.media.session.PlaybackStateCompat
import com.kienhoang.dualsubreplay.ui.browseVideoSelection
import com.kienhoang.dualsubreplay.ui.isYouTubeWebUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.json.JSONTokener

/** How long a paused video keeps the app in the foreground so earphones can resume it. */
internal const val PAUSED_FOREGROUND_GRACE_MS = 10 * 60 * 1000L

/** The YouTube page video, as the media session and its notification show it. */
internal data class WebVideoPlayback(
    val videoId: String,
    val title: String,
    val paused: Boolean,
    val positionMs: Long,
    val durationMs: Long?,
    val playbackRate: Float,
    /** The YouTube channel's name, shown under the title; blank when the page does not show it yet. */
    val channel: String = "",
)

internal sealed interface WebVideoCommand {
    data object Play : WebVideoCommand

    data object Pause : WebVideoCommand

    data class SeekTo(
        val positionMs: Long,
    ) : WebVideoCommand
}

/**
 * Connects the one YouTube page (which owns the WebView) to the playback service (which owns the
 * media session). The page publishes the video's state and handles commands; the session reads the
 * state and sends commands from earphones and the notification.
 */
internal object WebVideoBridge {
    private val mutablePlayback = MutableStateFlow<WebVideoPlayback?>(null)
    val playback: StateFlow<WebVideoPlayback?> = mutablePlayback.asStateFlow()

    private var commandHandler: ((WebVideoCommand) -> Unit)? = null

    fun publish(playback: WebVideoPlayback?) {
        mutablePlayback.value = playback
    }

    fun bindCommands(handler: (WebVideoCommand) -> Unit): Any {
        commandHandler = handler
        return handler
    }

    fun unbindCommands(token: Any) {
        if (commandHandler !== token) return
        commandHandler = null
        mutablePlayback.value = null
    }

    fun send(command: WebVideoCommand) {
        commandHandler?.invoke(command)
    }

    /** Earphone touch and the notification button: pause a playing video, resume a paused one. */
    fun toggle() {
        val current = playback.value ?: return
        send(if (current.paused) WebVideoCommand.Play else WebVideoCommand.Pause)
    }
}

/** True while something is playing that the app must not pause when it leaves the screen. */
internal fun WebVideoPlayback?.isPlaying(): Boolean = this != null && !paused

/**
 * The service stays in the foreground while the video plays, and for [PAUSED_FOREGROUND_GRACE_MS]
 * after it pauses so an earphone touch can resume it; after that Android may stop the app.
 */
internal fun shouldStayInForeground(
    playback: WebVideoPlayback?,
    pausedSinceMs: Long?,
    nowMs: Long,
): Boolean {
    if (playback == null) return false
    if (!playback.paused) return true
    return pausedSinceMs == null || nowMs - pausedSinceMs < PAUSED_FOREGROUND_GRACE_MS
}

/**
 * The session extrapolates the position from its last state, so it needs a new state only when
 * something besides the moving position changed, or the position drifted (a seek or buffering).
 */
internal fun sessionNeedsUpdate(
    previous: WebVideoPlayback?,
    previousAtMs: Long,
    current: WebVideoPlayback,
    nowMs: Long,
): Boolean {
    if (previous == null) return true
    if (previous.copy(positionMs = current.positionMs) != current) return true
    val elapsed = if (previous.paused) 0L else ((nowMs - previousAtMs) * previous.playbackRate).toLong()
    return kotlin.math.abs(previous.positionMs + elapsed - current.positionMs) > SESSION_POSITION_DRIFT_MS
}

private const val SESSION_POSITION_DRIFT_MS = 1_500L

internal fun sessionPlaybackState(playback: WebVideoPlayback?): Int =
    when {
        playback == null -> PlaybackStateCompat.STATE_NONE
        playback.paused -> PlaybackStateCompat.STATE_PAUSED
        else -> PlaybackStateCompat.STATE_PLAYING
    }

internal fun sessionActions(playback: WebVideoPlayback?): Long {
    if (playback == null) return 0L
    val toggle = PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
    val playOrPause = if (playback.paused) PlaybackStateCompat.ACTION_PLAY else PlaybackStateCompat.ACTION_PAUSE
    val seek = if (playback.durationMs != null) PlaybackStateCompat.ACTION_SEEK_TO else 0L
    return toggle or playOrPause or seek
}

/**
 * YouTube's page title is "(3) Video name - YouTube" (the count is unread notifications); the
 * notification shows only the video's name. Blank while the page still says just "YouTube".
 */
internal fun cleanVideoTitle(pageTitle: String?): String =
    pageTitle
        .orEmpty()
        .replace(Regex("\\s+"), " ")
        .trim()
        .replace(Regex("^\\(\\d+\\+?\\) "), "")
        .removeSuffix(" - YouTube")
        .trim()
        .takeUnless { it == "YouTube" }
        .orEmpty()

internal fun cleanChannelName(name: String?): String =
    name
        .orEmpty()
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_CHANNEL_LENGTH)

/** Reads the page video's state from [WEB_VIDEO_STATE_SCRIPT]; null when no watch-page video exists. */
internal fun parseWebVideoPlayback(rawValue: String?): WebVideoPlayback? {
    if (rawValue.isNullOrBlank() || rawValue == "null") return null
    return runCatching {
        val decoded = JSONTokener(rawValue).nextValue() as? String ?: return@runCatching null
        val json = JSONObject(decoded)
        val url = json.optString("url")
        if (!isYouTubeWebUrl(url)) return@runCatching null
        val selection = browseVideoSelection(url) ?: return@runCatching null
        val positionSecond = json.optDouble("currentSecond", 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0
        val durationSecond = json.optDouble("durationSecond", Double.NaN).takeIf { it.isFinite() && it > 0 }
        val rate = json.optDouble("playbackRate", 1.0).takeIf { it.isFinite() && it > 0 } ?: 1.0
        WebVideoPlayback(
            videoId = selection.videoId,
            title = cleanVideoTitle(json.optString("title")).take(MAX_TITLE_LENGTH),
            paused = json.optBoolean("paused", true),
            positionMs = (positionSecond * 1000).toLong(),
            durationMs = durationSecond?.let { (it * 1000).toLong() },
            playbackRate = rate.toFloat(),
            channel = cleanChannelName(json.optString("channel")),
        )
    }.getOrNull()
}

private const val MAX_TITLE_LENGTH = 200
private const val MAX_CHANNEL_LENGTH = 100

/**
 * The channel under the video when the page's media session is empty: the mobile site's bold
 * "@handle" (2026-11 layout), its older channel name, then the desktop site's.
 */
private const val CHANNEL_NAME_SELECTORS =
    ".ytVideoSubtitleViewModelBoldSubstring, .slim-owner-channel-name, " +
        "ytd-watch-metadata ytd-channel-name a"

private const val YOUTUBE_ORIGIN_CHECK =
    """
      const host = window.location.hostname.toLowerCase().replace(/\.$/, '');
      if (window.location.protocol !== 'https:' ||
          !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return null;
      const videos = Array.from(document.querySelectorAll('video'));
      const video = videos.find(function(item) { return !item.paused && !item.ended; })
        || videos.find(function(item) { return item.readyState > 0; })
        || videos[0];
      if (!video) return null;
    """

/**
 * The page video's state for the media session, polled once a second. The channel comes from the
 * page's own media session (YouTube's player fills it for Chrome's notification; checked on
 * m.youtube.com 2026-11-07) while it names the open video, otherwise from the name under the video.
 */
internal val WEB_VIDEO_STATE_SCRIPT: String =
    """
    (function() {
      $YOUTUBE_ORIGIN_CHECK
      const channel = (function() {
        try {
          const meta = navigator.mediaSession && navigator.mediaSession.metadata;
          if (meta && meta.artist && meta.title && document.title.indexOf(meta.title) !== -1) return meta.artist;
        } catch (error) {}
        const owner = document.querySelector('$CHANNEL_NAME_SELECTORS');
        return owner ? owner.textContent : '';
      })();
      return JSON.stringify({
        url: window.location.href,
        title: document.title,
        channel: channel,
        paused: video.paused || video.ended,
        currentSecond: video.currentTime,
        durationSecond: isFinite(video.duration) ? video.duration : null,
        playbackRate: video.playbackRate
      });
    })();
    """.trimIndent()

internal fun webVideoCommandScript(command: WebVideoCommand): String {
    val action =
        when (command) {
            WebVideoCommand.Play ->
                "const request = video.play(); if (request && request.catch) request.catch(function() {});"
            WebVideoCommand.Pause -> "video.pause();"
            is WebVideoCommand.SeekTo -> "video.currentTime = ${command.positionMs.coerceAtLeast(0) / 1000.0};"
        }
    return """
        (function() {
          $YOUTUBE_ORIGIN_CHECK
          $action
          return true;
        })();
        """.trimIndent()
}

/**
 * YouTube's page pauses its video when the page becomes hidden (screen off, app in the
 * background) unless the account has Premium. The owner chose on 2026-10-13 that the video keeps
 * playing for every account, so the page is always told it is visible: `document.hidden` and
 * `visibilityState` report visible and `visibilitychange` never reaches the page's own listeners.
 * Runs before the page's scripts where WebView supports it, and again after each load otherwise.
 */
internal val WEB_KEEP_PLAYING_WHEN_HIDDEN_SCRIPT: String =
    """
    (function() {
      const host = window.location.hostname.toLowerCase().replace(/\.$/, '');
      if (window.location.protocol !== 'https:' ||
          !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return false;
      if (window.__dualSubAlwaysVisible) return true;
      window.__dualSubAlwaysVisible = true;
      const visible = function(name, value) {
        try {
          Object.defineProperty(Document.prototype, name, { configurable: true, get: function() { return value; } });
        } catch (error) {}
      };
      visible('hidden', false);
      visible('webkitHidden', false);
      visible('visibilityState', 'visible');
      visible('webkitVisibilityState', 'visible');
      const swallow = function(event) { event.stopImmediatePropagation(); };
      ['visibilitychange', 'webkitvisibilitychange'].forEach(function(type) {
        window.addEventListener(type, swallow, true);
      });
      return true;
    })();
    """.trimIndent()

/** Origins [WEB_KEEP_PLAYING_WHEN_HIDDEN_SCRIPT] is injected into before the page's own scripts. */
internal val YOUTUBE_DOCUMENT_START_ORIGINS = setOf("https://youtube.com", "https://*.youtube.com")
