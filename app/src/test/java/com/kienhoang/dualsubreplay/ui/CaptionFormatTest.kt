package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.RawCaptionCue
import com.kienhoang.dualsubreplay.data.SubtitleMerger
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import com.kienhoang.dualsubreplay.data.SubtitleWord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CaptionFormatTest {
    @Test fun defaultAndLegacyPreferencesAreStable() {
        assertEquals(CaptionFormat.SHORT_PHRASES, storedCaptionFormat(null))
        assertEquals(CaptionFormat.WHOLE_SENTENCE, storedCaptionFormat(null, false))
        CaptionFormat.entries.forEach { assertEquals(it, storedCaptionFormat(it.storageValue)) }
        assertEquals(CaptionFormat.SHORT_PHRASES, storedCaptionFormat("unknown"))
    }

    @Test fun bothFormatsRetainExactUnevenWordTimesIncludingRepeatedWords() {
        val text = "I think I can help you, because I have worked here for five years."
        val words = text.split(" ").mapIndexed { i, word -> SubtitleWord(word, i * i * 30L, (i + 1) * (i + 1) * 30L) }
        val source = listOf(SubtitleSegment(0, 0, words.last().endMs, text, words = words))
        val short = captionDisplaySegments(source, CaptionFormat.SHORT_PHRASES, true)
        val whole = captionDisplaySegments(source, CaptionFormat.WHOLE_SENTENCE, true)
        assertTrue(short.size > 1)
        assertEquals(text, short.joinToString(" ") { it.originalText })
        assertEquals(words, short.flatMap { it.words })
        assertEquals(words, whole.flatMap { it.words })
        // v0.9.5's 75 ms lead skips this fixture's 30 ms opening token once
        // the cue starts. All later token starts are at least 90 ms apart.
        words.forEachIndexed { wordIndex, word ->
            val expected = words[if (wordIndex == 0) 1 else wordIndex]
            listOf(short, whole).forEach { rows ->
                val index = activeSubtitleIndex(rows, word.startMs)
                assertEquals(expected, rows[index].words[activeWordIndex(rows, index, word.startMs)])
            }
        }
    }

    @Test fun missingPunctuationDoesNotJoinUnlimitedSentences() {
        val source = (0..20).map { SubtitleSegment(it.toLong(), it * 1000L, (it + 1) * 1000L, "another fragment") }
        val whole = captionDisplaySegments(source, CaptionFormat.WHOLE_SENTENCE, true)
        assertTrue(whole.size > 1)
        assertTrue(whole.all { it.endMs - it.startMs <= MAX_UNIT_DURATION_MS && it.originalText.length <= MAX_UNIT_CHARACTERS })
        assertEquals(source.joinToString(" ") { it.originalText }, whole.joinToString(" ") { it.originalText })
    }

    @Test fun batchedPreparationMatchesContinuousFormatting() =
        runBlocking {
            val rows =
                List(80) { index ->
                    SubtitleSegment(
                        index.toLong(),
                        index * 1_000L,
                        (index + 1) * 1_000L,
                        if (index % 9 == 8) "fragment $index." else "fragment $index",
                    )
                }
            val expected = captionDisplaySegments(rows, CaptionFormat.SHORT_PHRASES, true)
            val directory = Files.createTempDirectory("caption-format-batches").toFile()
            try {
                SubtitleStore.create(directory, rows).use { source ->
                    prepareCaptionDisplayStore(
                        source = source,
                        directory = directory,
                        format = CaptionFormat.SHORT_PHRASES,
                        natural = true,
                        batchSize = 7,
                    ).use { display ->
                        assertEquals(expected, display.read(0 until display.size))
                    }
                }
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test fun overlappingAutoCaptionsCanBeSplitWithoutFailedRequirement() =
        runBlocking {
            val longText = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen"
            val rows =
                listOf(
                    SubtitleSegment(0, 0, 6_000, longText),
                    SubtitleSegment(1, 1_000, 2_000, "overlapping next caption"),
                )
            val directory = Files.createTempDirectory("caption-overlap-regression").toFile()
            try {
                SubtitleStore.create(directory, rows).use { source ->
                    prepareCaptionDisplayStore(
                        source = source,
                        directory = directory,
                        format = CaptionFormat.SHORT_PHRASES,
                        natural = false,
                        batchSize = 1,
                    ).use { display ->
                        val stored = display.read(0 until display.size)
                        assertTrue(stored.size > rows.size)
                        assertTrue(stored.any { it.originalText == "overlapping next caption" })
                    }
                }
            } finally {
                directory.deleteRecursively()
            }
        }

    /** Auto captions at 0:37 of a real video, where "a way." once became its own row and was translated alone. */
    @Test fun sentenceEndAfterALongLineJoinsTheSentenceBeforeIt() {
        val cues =
            listOf(
                RawCaptionCue(26_000, 30_000, "bit bad about how I'm starting this"),
                RawCaptionCue(30_000, 34_500, "video because it's almost misleading in"),
                RawCaptionCue(34_500, 36_500, "a way. The reality is that two different"),
                RawCaptionCue(36_500, 38_500, "things are happening at once right now."),
            )
        val merged = SubtitleMerger.merge(cues, enhancedNaturalFlow = true)
        assertEquals("a way. The reality is that two different things are happening at once right now.", merged.last().originalText)
        val rows = captionDisplaySegments(merged, CaptionFormat.SHORT_PHRASES, true)
        assertTrue(rows.none { it.originalText.equals("a way.", ignoreCase = true) })
        val tail = rows.first { it.originalText.endsWith("a way.") }
        assertEquals(
            "Bit bad about how I'm starting this video because it's almost misleading in a way.",
            tail.sentence?.text,
        )
        val next = rows.first { it.originalText.startsWith("The reality") }
        assertEquals("The reality is that two different things are happening at once right now.", next.sentence?.text ?: next.originalText)
    }
}
