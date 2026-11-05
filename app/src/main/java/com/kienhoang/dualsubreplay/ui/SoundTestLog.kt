package com.kienhoang.dualsubreplay.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kienhoang.dualsubreplay.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * TEMPORARY, test build only (owner request 2026-11-05), with SoundTest.kt: a log of the moments the
 * page video stalls, seeks, pauses or changes speed, and of every command the app sends it, so a
 * glitch the owner hears can be matched to what happened at that moment. Kept in memory only.
 */

private const val SOUND_LOG_MAX_LINES = 80
private const val SOUND_LOG_READ_MS = 2_000L

internal object SoundTestLog {
    private val mutableLines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = mutableLines.asStateFlow()

    private var lastText: String? = null
    private var repeats = 1

    /** The same text again in a row only counts up on the newest line, so a repeating read cannot fill the log. */
    @Synchronized
    fun add(
        text: String,
        atEpochMs: Long = System.currentTimeMillis(),
    ) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date(atEpochMs))
        val old = mutableLines.value
        if (text == lastText && old.isNotEmpty()) {
            repeats++
            mutableLines.value = listOf("$time $text (x$repeats)") + old.drop(1)
            return
        }
        lastText = text
        repeats = 1
        mutableLines.value = (listOf("$time $text") + old).take(SOUND_LOG_MAX_LINES)
    }

    /** What the app itself asked for. */
    fun app(text: String) = add("app: $text")

    @Synchronized
    fun clear() {
        lastText = null
        mutableLines.value = emptyList()
    }
}

/**
 * Installs the page's listeners once (they only listen, they never change the video) and returns the
 * events seen since the last read, as `[[epochMs, "text"], ...]`.
 */
internal val WEB_SOUND_LOG_SCRIPT: String =
    """
    (function() {
      const host = window.location.hostname.toLowerCase().replace(/\.${'$'}/, '');
      if (window.location.protocol !== 'https:' || !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return [];
      let log = window.__dualSubSoundLog;
      if (!log) {
        log = window.__dualSubSoundLog = { events: [], last: {} };
        const ahead = function(video) {
          try {
            for (let i = 0; i < video.buffered.length; i++) {
              if (video.buffered.start(i) <= video.currentTime && video.currentTime <= video.buffered.end(i)) {
                return (video.buffered.end(i) - video.currentTime).toFixed(1) + 's buffered';
              }
            }
          } catch (_) {}
          return 'nothing buffered';
        };
        const note = function(text) {
          log.events.push([Date.now(), text]);
          if (log.events.length > 60) log.events.shift();
        };
        ['waiting', 'stalled', 'seeking', 'seeked', 'ratechange', 'pause', 'play', 'playing', 'emptied', 'error',
          'volumechange', 'loadstart', 'ended'].forEach(function(type) {
          window.addEventListener(type, function(event) {
            const video = event.target;
            if (!(video instanceof HTMLMediaElement)) return;
            let text = 'page: ' + type + ' at ' + video.currentTime.toFixed(2);
            if (type === 'seeking') text += ' (was ' + (typeof log.last.time === 'number' ? log.last.time.toFixed(2) : '?') + ')';
            if (type === 'ratechange') text += ' speed ' + video.playbackRate;
            if (type === 'volumechange') text += (video.muted ? ' muted' : ' volume ' + video.volume.toFixed(2));
            if (type === 'waiting' || type === 'stalled') text += ', ' + ahead(video);
            if (type === 'error' && video.error) text += ' code ' + video.error.code;
            note(text);
          }, true);
        });
        window.addEventListener('timeupdate', function(event) {
          if (event.target instanceof HTMLMediaElement) log.last.time = event.target.currentTime;
        }, true);
        document.addEventListener('visibilitychange', function() { note('page: visibility ' + document.visibilityState); }, true);
        note('page: log started');
      }
      const out = log.events;
      log.events = [];
      return out;
    })();
    """.trimIndent()

internal fun addSoundLogReply(rawValue: String?) {
    val events = runCatching { JSONTokener(rawValue ?: "[]").nextValue() as? JSONArray }.getOrNull() ?: return
    for (i in 0 until events.length()) {
        val event = events.optJSONArray(i) ?: continue
        SoundTestLog.add(event.optString(1), event.optLong(0, System.currentTimeMillis()))
    }
}

/** Reads the page's events every 2 s, also with the screen off; not on the Plain page stage. */
@Composable
internal fun SoundTestPageLog(
    webView: WebView,
    enabled: Boolean,
) {
    LaunchedEffect(webView, enabled) {
        if (!enabled) return@LaunchedEffect
        while (isActive) {
            if (isYouTubeWebUrl(webView.url.orEmpty())) webView.evaluateJavascript(WEB_SOUND_LOG_SCRIPT, ::addSoundLogReply)
            delay(SOUND_LOG_READ_MS)
        }
    }
}

@Composable
internal fun SoundTestLogList() {
    val lines by SoundTestLog.lines.collectAsState()
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(stringResource(R.string.sound_test_log_title))
        Text(
            stringResource(R.string.sound_test_log_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = SoundTestLog::clear) { Text(stringResource(R.string.sound_test_log_clear)) }
        Text(
            if (lines.isEmpty()) stringResource(R.string.sound_test_log_empty) else lines.joinToString("\n"),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
        )
    }
}
