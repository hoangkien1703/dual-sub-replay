package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleTimingSource
import com.kienhoang.dualsubreplay.data.SubtitleWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcousticAlignmentBridgeTest {
    @Test
    fun `only signed googlevideo playback URLs are accepted for native audio decoding`() {
        assertTrue(
            trustedYouTubeAudioStreamUrl(
                "https://rr1---sn-example.googlevideo.com/videoplayback?expire=123&sig=abc",
            ),
        )
        assertFalse(trustedYouTubeAudioStreamUrl("http://rr1.googlevideo.com/videoplayback"))
        assertFalse(trustedYouTubeAudioStreamUrl("https://googlevideo.com.evil.test/videoplayback"))
        assertFalse(trustedYouTubeAudioStreamUrl("https://www.youtube.com/watch?v=abc"))
        assertFalse(trustedYouTubeAudioStreamUrl("https://rr1.googlevideo.com/not-a-video"))
    }

    @Test
    fun `audio stream bridge message carries direct adaptive format metadata`() {
        val message = assertNotNull(
            parseWebKaraokeSyncMessage(
                """
                {
                  "type":"audioStream",
                  "url":"https://www.youtube.com/watch?v=abcdefghijk",
                  "currentSecond":null,
                  "audioUrl":"https://rr1.googlevideo.com/videoplayback?sig=abc",
                  "audioMimeType":"audio/mp4; codecs=\"mp4a.40.2\"",
                  "audioUserAgent":"Android WebView"
                }
                """.trimIndent(),
            ),
        ) as WebKaraokeSyncMessage

        assertEquals("audioStream", message.type)
        assertEquals("https://rr1.googlevideo.com/videoplayback?sig=abc", message.audioUrl)
        assertTrue(message.audioMimeType!!.startsWith("audio/mp4"))
        assertEquals("Android WebView", message.audioUserAgent)
    }

    @Test
    fun `karaoke script exposes only a direct adaptive audio URL`() {
        assertTrue(WEB_KARAOKE_SYNC_SCRIPT.contains("getPlayerResponse"))
        assertTrue(WEB_KARAOKE_SYNC_SCRIPT.contains("adaptiveFormats"))
        assertTrue(WEB_KARAOKE_SYNC_SCRIPT.contains("typeof format.url === 'string'"))
        assertTrue(WEB_KARAOKE_SYNC_SCRIPT.contains("type: 'audioStream'"))
    }

    @Test
    fun `acoustic alignment is limited to generated English captions needing estimates`() {
        val estimated = subtitle(SubtitleTimingSource.ESTIMATED)
        val exact = subtitle(SubtitleTimingSource.YOUTUBE_EXACT)

        assertTrue(shouldUseAcousticAlignment(true, "en", listOf(estimated)))
        assertFalse(shouldUseAcousticAlignment(false, "en", listOf(estimated)))
        assertFalse(shouldUseAcousticAlignment(true, "fr", listOf(estimated)))
        assertFalse(shouldUseAcousticAlignment(true, "en", listOf(exact)))
    }

    @Test
    fun `translation progress cannot overwrite newer acoustic timings`() {
        val acoustic = subtitle(SubtitleTimingSource.ACOUSTIC_ALIGNED).copy(
            words = listOf(
                SubtitleWord(
                    text = "hello",
                    startMs = 820L,
                    endMs = 1_120L,
                    timingSource = SubtitleTimingSource.ACOUSTIC_ALIGNED,
                ),
            ),
        )
        val translatedSnapshot = subtitle(SubtitleTimingSource.ESTIMATED).copy(
            translatedText = "xin chào",
        )

        val merged = mergeTranslatedTextPreservingTiming(
            current = listOf(acoustic),
            translatedSnapshot = listOf(translatedSnapshot),
        )

        assertEquals("xin chào", merged.single().translatedText)
        assertEquals(820L, merged.single().words.single().startMs)
        assertEquals(SubtitleTimingSource.ACOUSTIC_ALIGNED, merged.single().words.single().timingSource)
    }

    private fun subtitle(source: SubtitleTimingSource): SubtitleSegment =
        SubtitleSegment(
            id = 0L,
            startMs = 1_000L,
            endMs = 1_400L,
            originalText = "hello",
            words = listOf(
                SubtitleWord(
                    text = "hello",
                    startMs = 1_000L,
                    endMs = 1_400L,
                    timingSource = source,
                ),
            ),
        )
}
