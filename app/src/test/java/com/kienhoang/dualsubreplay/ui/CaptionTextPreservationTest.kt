package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.CaptionDocumentParser
import com.kienhoang.dualsubreplay.data.SubtitleMerger
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleWord
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** Grouping and splitting may move caption text between rows, but never drop spoken words. */
class CaptionTextPreservationTest {
    @Test fun overlappingSentencePieceInsideItsNeighbourKeepsItsWords() {
        // Phone report: "every day. All of this sets a" lost "All of this sets a" because the
        // piece fit inside the previous overlapping auto-caption line's time range.
        val first = segment(0, 0, 9_000, "Rely on when you worked every day.")
        val second = segment(1, 6_000, 12_000, "All of this sets a pretty brutal stage.")
        val merged = listOf(first, second.copy(startMs = 6_000, endMs = 8_500))
        CaptionFormat.entries.forEach { format ->
            val display = captionDisplaySegments(merged, format, natural = true)
            assertEquals(text(merged), text(display))
            assertEquals(merged.flatMap { it.words }, display.flatMap { it.words })
        }
    }

    @Test fun generatedOverlappingAutoCaptionsNeverLoseTextOrWords() {
        repeat(400) { seed ->
            val merged = SubtitleMerger.merge(CaptionDocumentParser.parse(autoCaptions(Random(seed))), enhancedNaturalFlow = true)
            CaptionFormat.entries.forEach { format ->
                val display = captionDisplaySegments(merged, format, natural = true)
                assertEquals("seed $seed $format", text(merged), text(display))
                assertEquals(
                    "seed $seed $format",
                    merged.flatMap { it.words }.map { it.text },
                    display.flatMap { it.words }.map { it.text },
                )
            }
        }
    }

    private fun text(rows: List<SubtitleSegment>) = rows.joinToString(" ") { it.originalText }.lowercase().replace(Regex("\\s+"), " ")

    private fun segment(
        id: Long,
        startMs: Long,
        endMs: Long,
        text: String,
    ): SubtitleSegment {
        val tokens = text.split(" ")
        val step = (endMs - startMs) / (tokens.size + 1)
        val words = tokens.mapIndexed { index, token -> SubtitleWord(token, startMs + index * step, startMs + (index + 1) * step) }
        return SubtitleSegment(id, startMs, endMs, text, words = words)
    }

    /** json3-like ASR events whose display durations overlap the next line, as YouTube's do. */
    private fun autoCaptions(random: Random): String {
        var time = 0L
        val events =
            List(random.nextInt(3, 14)) {
                val words = List(random.nextInt(1, 9)) { VOCABULARY[random.nextInt(VOCABULARY.size)] }.toMutableList()
                if (random.nextInt(4) == 0) random.nextInt(words.size).let { words[it] = words[it] + "." }
                val spoken = random.nextLong(800, 5_000)
                val segs =
                    words.mapIndexed { index, word ->
                        val utf8 = if (index == 0) word else " $word"
                        "{\"utf8\":\"$utf8\",\"tOffsetMs\":${index * spoken / (words.size + 1)}}"
                    }
                val event = "{\"tStartMs\":$time,\"dDurationMs\":${spoken +
                    random.nextLong(
                        0,
                        3_000,
                    )
                },\"segs\":[${segs.joinToString(",")}]}"
                time += spoken + random.nextLong(0, 1_500)
                event
            }
        return "{\"events\":[${events.joinToString(",")}]}"
    }

    private companion object {
        val VOCABULARY =
            (
                "all of this sets a pretty brutal stage for anthropic who previously made incredible models in the opus " +
                    "line and has since kind let it die actual thing you could rely on when worked every day"
            ).split(" ")
    }
}
