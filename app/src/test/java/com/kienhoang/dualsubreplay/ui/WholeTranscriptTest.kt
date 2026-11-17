package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SentenceSlice
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.StringWriter
import java.io.Writer

class WholeTranscriptTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val asked = mutableListOf<String>()
    private val prefetched = mutableListOf<List<String>>()

    /** "Hello there." as two rows of one sentence, then "Good night." as one row. */
    private val rows =
        listOf(
            SubtitleSegment(1, 1_000, 2_000, "Hello", sentence = SentenceSlice("Hello there.", listOf(6), 0)),
            SubtitleSegment(2, 2_000, 3_500, "there.", sentence = SentenceSlice("Hello there.", listOf(6), 1)),
            SubtitleSegment(3, 3_600_000, 3_602_000, "Good night."),
        )

    private fun transcript(segments: List<SubtitleSegment> = rows): WholeTranscript =
        runBlocking {
            WholeTranscript(
                SubtitleStore.create(folder.newFolder(), segments),
                translate = { text ->
                    asked += text
                    "vi: $text"
                },
                prefetch = { texts -> prefetched += texts },
            )
        }

    @Test
    fun rowsAreReadFromDiskAndATranslationShowsUnderTheSentencesFirstRow() =
        runBlocking {
            val whole = transcript()
            assertEquals(3, whole.size)
            assertEquals("there.", whole.row(1)?.originalText)
            assertNull(whole.row(3))
            val shown = whole.translateStarts(0..2)
            assertEquals(mapOf(0 to "vi: Hello there.", 2 to "vi: Good night."), shown)
            assertEquals(listOf(listOf("Hello there.", "Good night.")), prefetched)
            // Scrolling back asks nothing again.
            whole.translateStarts(0..2)
            assertEquals(2, asked.size)
        }

    @Test
    fun searchFindsRowsIgnoringCase() =
        runBlocking {
            val whole = transcript()
            assertEquals(listOf(0), whole.search("hello"))
            assertEquals(listOf(2), whole.search(" NIGHT "))
            assertEquals(emptyList<Int>(), whole.search("morning"))
            assertEquals(emptyList<Int>(), whole.search("  "))
        }

    @Test
    fun textExportWritesOneLinePerSentenceWithItsTranslation() =
        runBlocking {
            val out = StringWriter()
            assertTrue(transcript().export(out, TranscriptExportFormat.TEXT, withTranslation = true, title = "My video"))
            assertEquals(
                "My video\n\n[0:01] Hello there.\n    vi: Hello there.\n[1:00:00] Good night.\n    vi: Good night.\n",
                out.toString(),
            )
        }

    @Test
    fun srtExportJoinsASentencesRowsAndCanLeaveTheTranslationOut() =
        runBlocking {
            val out = StringWriter()
            assertTrue(transcript().export(out, TranscriptExportFormat.SRT, withTranslation = false, title = "ignored"))
            assertEquals(
                "1\n00:00:01,000 --> 00:00:03,500\nHello there.\n\n2\n01:00:00,000 --> 01:00:02,000\nGood night.\n\n",
                out.toString(),
            )
            assertTrue("Nothing is translated without the translation", asked.isEmpty())
        }

    @Test
    fun aClosedStoreEndsReadsAndExportsQuietly() =
        runBlocking {
            val store = SubtitleStore.create(folder.newFolder(), rows)
            val whole = WholeTranscript(store, { it }, null)
            store.close()
            assertNull(whole.row(0))
            assertEquals(emptyList<Int>(), whole.search("hello"))
            assertFalse(whole.export(StringWriter(), TranscriptExportFormat.TEXT, withTranslation = false, title = null))
        }

    /**
     * The panel's window exists because a 6-hour video once used up the app's memory. A 6-hour
     * transcript (a line every 1.5 s) is searched and exported through a writer that keeps nothing,
     * while only a few pages of rows are ever held.
     */
    @Test
    fun aSixHourTranscriptStreamsWithoutHoldingItsRows() =
        runBlocking {
            val count = 6 * 60 * 60 * 2 / 3
            val long = List(count) { SubtitleSegment(it.toLong(), it * 1_500L, it * 1_500L + 1_400, "Line number $it of the talk.") }
            val whole = transcript(long)
            assertEquals(count, whole.size)
            assertEquals(WHOLE_TRANSCRIPT_MAX_MATCHES, whole.search("line").size)
            assertEquals(listOf(count - 1), whole.search("number ${count - 1} "))
            var written = 0L
            val counting =
                object : Writer() {
                    override fun write(
                        cbuf: CharArray,
                        off: Int,
                        len: Int,
                    ) {
                        written += len
                    }

                    override fun flush() = Unit

                    override fun close() = Unit
                }
            var progress = 0
            assertTrue(
                whole.export(counting, TranscriptExportFormat.SRT, withTranslation = false, title = null) { done, _ ->
                    progress =
                        done
                },
            )
            assertEquals(count, progress)
            assertTrue("Only $written characters written", written > count * 30L)
            // Reading rows all over the video keeps at most a few pages.
            listOf(0, count / 2, count - 1, 100, 5_000).forEach { assertTrue(whole.row(it) != null) }
        }

    @Test
    fun timesAndFileNamesAreFormatted() {
        assertEquals("0:05", clockTime(5_400))
        assertEquals("12:00", clockTime(720_000))
        assertEquals("2:03:04", clockTime(7_384_000))
        assertEquals("01:02:03,045", srtTime(3_723_045))
        assertEquals("A B video.srt", exportFileName("A/B  video", TranscriptExportFormat.SRT))
        assertEquals("transcript.txt", exportFileName(null, TranscriptExportFormat.TEXT))
        assertEquals("transcript.txt", exportFileName(" ?* ", TranscriptExportFormat.TEXT))
    }
}
