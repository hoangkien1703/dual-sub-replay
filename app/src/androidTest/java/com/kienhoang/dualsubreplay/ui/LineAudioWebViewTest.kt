package com.kienhoang.dualsubreplay.ui

import android.media.MediaExtractor
import android.media.MediaFormat
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.LinePcm
import com.kienhoang.dualsubreplay.data.audioLine
import com.kienhoang.dualsubreplay.data.encodeM4a
import com.kienhoang.dualsubreplay.data.parseLinePcm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** The Anki sentence clips (spec docs/specs/2026-11-09-anki-word-and-sentence-audio.md) on a real WebView and encoder. */
class LineAudioWebViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun aClipEncodesToAnM4aThatAndroidReadsBack() {
        val rate = 24_000
        val samples = ShortArray(rate) { (sin(2 * PI * 440 * it / rate) * 12_000).toInt().toShort() }
        val file = File(instrumentation.targetContext.cacheDir, "line-audio-test.m4a").apply { delete() }
        try {
            assertTrue(encodeM4a(LinePcm(rate, samples), file))
            // About 40 kbit/s: one second is a few kilobytes, far below the 48 KB of the samples.
            assertTrue(file.length() in 1_000..20_000)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.path)
                assertEquals(1, extractor.trackCount)
                val format = extractor.getTrackFormat(0)
                assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
                assertEquals(rate, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                assertTrue(abs(format.getLong(MediaFormat.KEY_DURATION) - 1_000_000) < 150_000)
            } finally {
                extractor.release()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun thePageRecorderKeepsThePlayedSoundOfThePageVideo() {
        val loaded = CountDownLatch(1)
        var web: WebView? = null
        try {
            instrumentation.runOnMainSync {
                web =
                    WebView(instrumentation.targetContext).apply {
                        settings.javaScriptEnabled = true
                        // The fixture plays without a tap; in the app the learner's tap starts the video.
                        // The rule goes back on before the recorder starts, as the app keeps it.
                        settings.mediaPlaybackRequiresUserGesture = false
                        webViewClient =
                            object : WebViewClient() {
                                override fun onPageFinished(
                                    view: WebView,
                                    url: String,
                                ) = loaded.countDown()
                            }
                        loadDataWithBaseURL("https://www.youtube.com/watch?v=abcdefghijk", FIXTURE, "text/html", "UTF-8", null)
                    }
            }
            assertTrue(loaded.await(20, TimeUnit.SECONDS))
            assumeTrue("the fixture video did not start", waitForVideo(web!!, 0.3))
            instrumentation.runOnMainSync { web!!.settings.mediaPlaybackRequiresUserGesture = true }
            var second = 0.0
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline && second < 3.5) {
                evaluate(web!!, webLineAudioScript(on = true, videoId = "abcdefghijk"))
                second = evaluate(web!!, "document.querySelector('video').currentTime")?.toDoubleOrNull() ?: 0.0
                Thread.sleep(300)
            }
            // An emulator without sound output never plays the fixture; there is nothing to record then.
            assumeTrue("the fixture video did not play (at $second s)", second >= 3.5)
            val pcm = parseLinePcm(evaluate(web!!, webLineAudioClipScript(audioLine("abcdefghijk", 1_000, 2_000)!!)))
            assertNotNull("the page did not hold the line", pcm)
            val clip = pcm!!
            val seconds = clip.samples.size.toDouble() / clip.sampleRate
            assertEquals(1.65, seconds, 0.1)
            assertTrue("the clip is silent", clip.samples.maxOf { abs(it.toInt()) } > 1_000)
            // Another video's line is never cut from this video's sound.
            assertEquals(null, parseLinePcm(evaluate(web!!, webLineAudioClipScript(audioLine("zyxwvutsrqp", 1_000, 2_000)!!))))
            evaluate(web!!, webLineAudioScript(on = false, videoId = null))
            assertEquals("null", evaluate(web!!, webLineAudioClipScript(audioLine("abcdefghijk", 1_000, 2_000)!!)))
        } finally {
            instrumentation.runOnMainSync { web?.destroy() }
        }
    }

    private fun waitForVideo(
        web: WebView,
        second: Double,
    ): Boolean {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val at = evaluate(web, "document.querySelector('video').currentTime")?.toDoubleOrNull() ?: 0.0
            if (at >= second) return true
            Thread.sleep(200)
        }
        return false
    }

    private fun evaluate(
        web: WebView,
        script: String,
    ): String? {
        val done = CountDownLatch(1)
        var result: String? = null
        instrumentation.runOnMainSync {
            web.evaluateJavascript(script) {
                result = it
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        return result
    }

    private companion object {
        /** A watch page whose video plays a 10 s tone made in the page (a `blob:` address, as YouTube's own video is). */
        val FIXTURE =
            """
            <html><body><div id="movie_player"><video class="html5-main-video"></video></div><script>
            const rate = 24000, seconds = 10, count = rate * seconds;
            const buffer = new ArrayBuffer(44 + count * 2), view = new DataView(buffer);
            const text = function(at, value) { for (let i = 0; i < value.length; i++) view.setUint8(at + i, value.charCodeAt(i)); };
            text(0, 'RIFF'); view.setUint32(4, 36 + count * 2, true); text(8, 'WAVEfmt ');
            view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 1, true);
            view.setUint32(24, rate, true); view.setUint32(28, rate * 2, true); view.setUint16(32, 2, true); view.setUint16(34, 16, true);
            text(36, 'data'); view.setUint32(40, count * 2, true);
            for (let i = 0; i < count; i++) view.setInt16(44 + i * 2, Math.round(Math.sin(2 * Math.PI * 440 * i / rate) * 12000), true);
            const video = document.querySelector('video');
            video.src = URL.createObjectURL(new Blob([buffer], { type: 'audio/wav' }));
            video.play();
            </script></body></html>
            """.trimIndent()
    }
}
