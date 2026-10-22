package com.kienhoang.dualsubreplay.ui

import android.app.Application
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.CaptionProvider
import com.kienhoang.dualsubreplay.data.CaptionTrackResult
import com.kienhoang.dualsubreplay.data.RawCaptionCue
import com.kienhoang.dualsubreplay.data.SubtitleWord
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val CPU_LOG_TAG = "PageCpuUse"
private const val VIDEO_ID = "cpubudget01"

/**
 * CPU budget for the app's own work around the YouTube page (owner request 2026-10-22, see
 * docs/specs/2026-10-22-lighter-cpu-use.md). Runs the complete player on an offline YouTube-like
 * page and measures the CPU time of the app's process and WebView's page (renderer) process while
 * a video plays, while it is paused, and on a page without a video. The page fakes its video, so
 * the numbers are our scripts, polling and screen updates, not video decoding. Each result is
 * logged with the [CPU_LOG_TAG] tag; CI prints those lines in the job summary.
 */
class PageCpuUseTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun appWorkAroundThePageStaysWithinItsCpuBudget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val preferences = application.getSharedPreferences("dual_sub_preferences", 0)
        val saved = preferences.all
        preferences
            .edit()
            .putBoolean("onboarding_completed", true)
            .putBoolean(GUIDE_COMPLETED_PREFERENCE, true)
            .putBoolean(PRELOAD_MODELS_ENABLED_PREFERENCE, false)
            .putBoolean(WORD_HIGHLIGHT_ENABLED_PREFERENCE, true)
            .putBoolean(WORD_LEARNING_ENABLED_PREFERENCE, true)
            .putBoolean(TAP_TO_LEARN_PREFERENCE, true)
            .putString(PLAYER_EXPERIENCE_MODE_PREFERENCE, PlayerExperienceMode.TRANSCRIPT_PANEL.storageValue)
            .putString("last_browser_url", "https://m.youtube.com/watch?v=$VIDEO_ID")
            .putString("preferred_caption_language", "en")
            .putString("target_language", "en")
            .commit()
        lateinit var model: AppViewModel
        instrumentation.runOnMainSync { model = AppViewModel(application, LongTranscript()) }
        val page = AtomicReference<WebView>()
        try {
            compose.setContent {
                CompositionLocalProvider(
                    LocalYouTubeRequestInterceptor provides { view, request ->
                        page.compareAndSet(null, view)
                        val html =
                            when {
                                !request.isForMainFrame -> ""
                                request.url.path == "/watch" -> WATCH_PAGE
                                else -> HOME_PAGE
                            }
                        WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                    },
                ) {
                    LearningPlayerRoot(model)
                }
            }
            compose.waitUntil(20_000) {
                model.state.value.segments
                    .isNotEmpty() && model.state.value.currentIndex >= 0
            }
            // Let the page, the first translation pass and the word splitting settle first.
            Thread.sleep(SETTLE_MS)

            val playing = measure("playing", PLAYING_MS)
            javascript(page.get(), "window.setPaused(true); true")
            Thread.sleep(SETTLE_MS)
            val paused = measure("paused", IDLE_MS)
            javascript(page.get(), "window.location.href = 'https://m.youtube.com/'; true")
            compose.waitUntil(10_000) { javascript(page.get(), "window.location.pathname") == "\"/\"" }
            Thread.sleep(SETTLE_MS)
            val noVideo = measure("no_video", IDLE_MS)

            assertTrue("Playing used ${playing.total} ms of CPU a second", playing.total <= PLAYING_BUDGET_MS_PER_SECOND)
            assertTrue("Paused used ${paused.total} ms of CPU a second", paused.total <= IDLE_BUDGET_MS_PER_SECOND)
            assertTrue("A page without a video used ${noVideo.total} ms of CPU a second", noVideo.total <= IDLE_BUDGET_MS_PER_SECOND)
        } finally {
            instrumentation.runOnMainSync { model.viewModelScope.cancel() }
            val editor = preferences.edit().clear()
            saved.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                }
            }
            editor.commit()
        }
    }

    /** CPU milliseconds a second, for the app's process and WebView's page processes. */
    private data class CpuUse(
        val app: Double,
        val page: Double,
    ) {
        val total get() = app + page
    }

    private fun measure(
        scenario: String,
        durationMs: Long,
    ): CpuUse {
        val appBefore = Process.getElapsedCpuTime()
        val pageBefore = pageProcessCpuMs()
        val started = System.nanoTime()
        Thread.sleep(durationMs)
        val seconds = (System.nanoTime() - started) / 1e9
        val use =
            CpuUse(
                app = (Process.getElapsedCpuTime() - appBefore) / seconds,
                page = pageProcessCpuMs()?.let { after -> pageBefore?.let { (after - it) / seconds } } ?: 0.0,
            )
        Log.i(
            CPU_LOG_TAG,
            "scenario=$scenario app_ms_per_s=${use.app.oneDecimal()} page_ms_per_s=${use.page.oneDecimal()} " +
                "total_ms_per_s=${use.total.oneDecimal()}" +
                (if (pageBefore == null) " page_process=unreadable" else ""),
        )
        return use
    }

    /** CPU time of every WebView renderer process, read through the shell; null when it cannot be read. */
    private fun pageProcessCpuMs(): Long? {
        val ticksPerSecond = Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0 } ?: 100L
        val pids =
            shell("ps -A -o PID,NAME")
                .lines()
                .filter { it.contains("sandboxed_process") }
                .mapNotNull { it.trim().substringBefore(' ').toIntOrNull() }
        if (pids.isEmpty()) return null
        var ticks = 0L
        for (pid in pids) {
            // utime and stime are fields 14 and 15; the name in field 2 may hold spaces, so count after ")".
            val fields = shell("cat /proc/$pid/stat").substringAfterLast(')').trim().split(' ')
            ticks += (fields.getOrNull(11)?.toLongOrNull() ?: return null) + (fields.getOrNull(12)?.toLongOrNull() ?: return null)
        }
        return ticks * 1000 / ticksPerSecond
    }

    private fun Double.oneDecimal(): String = String.format(Locale.ROOT, "%.1f", this)

    private fun shell(command: String): String =
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
            .let { descriptor ->
                android.os.ParcelFileDescriptor
                    .AutoCloseInputStream(descriptor)
                    .use { it.readBytes().decodeToString() }
            }

    private fun javascript(
        page: WebView,
        script: String,
    ): String {
        val done = CountDownLatch(1)
        val result = AtomicReference<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            page.evaluateJavascript(script) {
                result.set(it)
                done.countDown()
            }
        }
        assertTrue("WebView script did not complete", done.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    /** Ten minutes of two-second lines with timed words, like a long auto-generated transcript. */
    private class LongTranscript : CaptionProvider {
        override suspend fun fetch(
            videoId: String,
            preferredLanguages: List<String>,
            learningLanguage: String?,
        ): CaptionTrackResult =
            CaptionTrackResult(
                "en",
                true,
                List(300) { line ->
                    val start = line * 2_000L
                    val words = listOf("this", "is", "line", "number", "$line", "of", "the", "video")
                    RawCaptionCue(
                        start,
                        start + 2_000L,
                        words.joinToString(" "),
                        words.mapIndexed { index, word -> SubtitleWord(word, start + index * 250L, start + (index + 1) * 250L) },
                    )
                },
            )
    }

    companion object {
        private const val SETTLE_MS = 5_000L
        private const val PLAYING_MS = 30_000L
        private const val IDLE_MS = 30_000L

        // Loose until CI has measured both versions; the spec records the numbers they come from.
        private const val PLAYING_BUDGET_MS_PER_SECOND = 600.0
        private const val IDLE_BUDGET_MS_PER_SECOND = 600.0

        /** YouTube's churn: a feed, a time display and progress bar every 250 ms, a caption every 2 s. */
        private val FEED =
            """
            <div id="feed"></div>
            <script>
            const feed = document.getElementById('feed');
            for (let i = 0; i < 150; i++) {
              const item = document.createElement('div');
              item.className = 'item';
              item.innerHTML = '<a href="#"><img alt=""><h3>Video title ' + i + '</h3><span>Channel</span>' +
                '<span>1M views</span></a><button aria-label="Action menu">more</button>';
              feed.appendChild(item);
            }
            </script>
            """.trimIndent()

        private val WATCH_PAGE =
            """
            <!doctype html>
            <html><head><meta name="viewport" content="width=device-width"></head><body>
            <div id="movie_player" class="html5-video-player ytp-autohide" style="position:relative;height:200px">
              <video style="width:100%;height:200px"></video>
              <div class="ytp-chrome-bottom" style="opacity:0">
                <div class="ytp-progress-bar-container"><div class="ytp-play-progress" style="height:3px;background:red"></div></div>
                <span class="ytp-time-current">0:00</span>
              </div>
              <div class="ytp-caption-window-container"><span class="ytp-caption-segment">this is line number 0</span></div>
              <button class="ytmClosedCaptioningButtonButton" aria-pressed="true" aria-label="Captions"></button>
            </div>
            $FEED
            <script>
            const video = document.querySelector('video');
            let base = 0;
            let since = performance.now();
            window.__paused = false;
            Object.defineProperties(video, {
              currentTime: {
                get: function() { return window.__paused ? base : base + (performance.now() - since) / 1000; },
                set: function(value) { base = value; since = performance.now(); }
              },
              readyState: {get: () => 4}, paused: {get: () => window.__paused}, ended: {get: () => false},
              seeking: {get: () => false}, duration: {get: () => 600}
            });
            video.play = function() { window.setPaused(false); return Promise.resolve(); };
            video.pause = function() { window.setPaused(true); };
            window.setPaused = function(paused) {
              if (paused) base = video.currentTime; else since = performance.now();
              window.__paused = paused;
            };
            const time = document.querySelector('.ytp-time-current');
            const progress = document.querySelector('.ytp-play-progress');
            setInterval(function() {
              const t = video.currentTime;
              time.textContent = Math.floor(t / 60) + ':' + String(Math.floor(t % 60)).padStart(2, '0');
              progress.style.width = (t / 6) + '%';
            }, 250);
            const caption = document.querySelector('.ytp-caption-segment');
            setInterval(function() {
              if (!window.__paused) caption.textContent = 'this is line number ' + Math.floor(video.currentTime / 2);
            }, 2000);
            const track = {languageCode: 'en', kind: 'asr', vssId: 'a.en'};
            const player = document.getElementById('movie_player');
            player.getOption = function(module, key) { return key === 'tracklist' ? [track] : track; };
            player.setOption = function() {};
            player.loadModule = function() {};
            player.getPlayerResponse = function() {
              return {
                videoDetails: {videoId: '$VIDEO_ID', title: 'A long video', shortDescription: 'About this video. '.repeat(150)},
                captions: {playerCaptionsTracklistRenderer: {captionTracks: [track], audioTracks: []}}
              };
            };
            </script></body></html>
            """.trimIndent()

        private val HOME_PAGE =
            """
            <!doctype html>
            <html><head><meta name="viewport" content="width=device-width"></head><body>
            $FEED
            </body></html>
            """.trimIndent()
    }
}
