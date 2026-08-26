package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleWordHighlightTest {

    @Test fun mapsWordTimingsOntoCharacterRanges() {
        val segment = SubtitleSegment(
            id = 1,
            startMs = 0,
            endMs = 2_000,
            originalText = "Hello brave world",
            words = listOf(
                SubtitleWord("Hello", 0, 600),
                SubtitleWord("brave", 600, 1_200),
                SubtitleWord("world", 1_200, 2_000),
            ),
        )

        val spans = subtitleWordSpans(segment.originalText, segment.words)

        assertEquals(3, spans.size)
        assertEquals(0, spans[0].start)
        assertEquals(5, spans[0].end)
        assertEquals(6, spans[1].start)
        assertEquals(11, spans[1].end)
        assertEquals(12, spans[2].start)
        assertEquals(17, spans[2].end)
    }

    @Test fun returnsEmptyWhenAlignmentFails() {
        val words = listOf(SubtitleWord("mismatched", 0, 500))

        assertTrue(subtitleWordSpans("different text entirely", words).isEmpty())
    }

    @Test fun malformedAutoCaptionChunkDoesNotDisableLaterHighlights() {
        val words = listOf(
            SubtitleWord("stale overlapping text", 0, 300),
            SubtitleWord("changes", 300, 600),
            SubtitleWord("being", 600, 900),
            SubtitleWord("applied", 900, 1_200),
        )

        val spans = subtitleWordSpans("changes being applied on web", words)

        assertEquals(listOf(1, 2, 3), spans.map { it.wordIndex })
        assertEquals("changes", "changes being applied on web".substring(spans[0].start, spans[0].end))
    }

    @Test fun spokenWordUsesColorAndUnderlineWithoutBold() {
        val segment = SubtitleSegment(
            id = 1,
            startMs = 0,
            endMs = 1_000,
            originalText = "really useful",
            words = listOf(
                SubtitleWord("really", 0, 500),
                SubtitleWord("useful", 500, 1_000),
            ),
        )

        val annotated = annotatedSpokenText(
            segment = segment,
            activeWordIndex = 0,
            baseColor = Color.White,
            highlightColor = Color.Yellow,
        )
        val style = annotated.spanStyles.single().item

        assertEquals(Color.Yellow, style.color)
        assertEquals(TextDecoration.Underline, style.textDecoration)
        assertEquals(null, style.fontWeight)
    }

    @Test fun returnsEmptyWithoutWordsOrText() {
        assertTrue(subtitleWordSpans("text", emptyList()).isEmpty())
        assertTrue(
            subtitleWordSpans(
                "text",
                listOf(SubtitleWord("text", 0, 10)),
            ).isNotEmpty(),
        )
    }

    @Test fun customColorTogglesFallBackToDefaults() {
        val state = DualSubUiState(
            originalColorKey = "amber",
            translatedColorKey = "rose",
            highlightColorKey = "lavender",
        )
        assertEquals(subtitleColor("amber"), effectiveOriginalColor(state))
        assertEquals(subtitleColor("rose"), effectiveTranslatedColor(state))

        val disabled = state.copy(customColorsEnabled = false, wordHighlightEnabled = false)
        assertEquals(subtitleColor(DEFAULT_ORIGINAL_COLOR_KEY), effectiveOriginalColor(disabled))
        assertEquals(subtitleColor(DEFAULT_TRANSLATED_COLOR_KEY), effectiveTranslatedColor(disabled))
    }

    @Test fun storedColorKeyRejectsUnknownValues() {
        assertEquals(DEFAULT_ORIGINAL_COLOR_KEY, storedSubtitleColorKey(null, DEFAULT_ORIGINAL_COLOR_KEY))
        assertEquals("mint", storedSubtitleColorKey("mint", DEFAULT_HIGHLIGHT_COLOR_KEY))
        assertEquals(
            DEFAULT_HIGHLIGHT_COLOR_KEY,
            storedSubtitleColorKey("neon_pink", DEFAULT_HIGHLIGHT_COLOR_KEY),
        )
    }

    @Test fun backgroundAndThemeKeysRejectUnknownValues() {
        assertEquals(DEFAULT_SUBTITLE_BOX_BACKGROUND_KEY, storedSubtitleBoxBackgroundKey(null))
        assertEquals("navy", storedSubtitleBoxBackgroundKey("navy"))
        assertEquals(DEFAULT_SUBTITLE_BOX_BACKGROUND_KEY, storedSubtitleBoxBackgroundKey("hot_pink"))

        assertEquals(DEFAULT_APP_THEME_ACCENT_KEY, storedAppThemeAccentKey(null))
        assertEquals("rose", storedAppThemeAccentKey("rose"))
        assertEquals(DEFAULT_APP_THEME_ACCENT_KEY, storedAppThemeAccentKey("unknown"))
    }

    @Test fun resettableSettingKeysCoverEveryUserFacingPreference() {
        val expected = setOf(
            "font_scale",
            "preferred_caption_language",
            "target_language",
            "landscape_split_enabled",
            PLAYER_EXPERIENCE_MODE_PREFERENCE,
            AUTO_OVERLAY_FULLSCREEN_PREFERENCE,
            AUTO_OVERLAY_LANDSCAPE_PREFERENCE,
            AUTO_AVOID_PLAYER_CONTROLS_PREFERENCE,
            REMEMBER_OVERLAY_POSITION_PREFERENCE,
            OVERLAY_VERTICAL_POSITION_PREFERENCE,
            OVERLAY_HORIZONTAL_POSITION_PREFERENCE,
            MOVABLE_OVERLAY_PREFERENCE,
            COLLAPSED_CC_HORIZONTAL_POSITION_PREFERENCE,
            COLLAPSED_CC_VERTICAL_POSITION_PREFERENCE,
            SPLIT_LONG_SENTENCES_PREFERENCE,
            LANDSCAPE_VIDEO_FRACTION_PREFERENCE,
            SUBTITLE_ORIGINAL_COLOR_PREFERENCE,
            SUBTITLE_TRANSLATED_COLOR_PREFERENCE,
            SUBTITLE_HIGHLIGHT_COLOR_PREFERENCE,
            SUBTITLE_BOX_BACKGROUND_PREFERENCE,
            APP_THEME_ACCENT_PREFERENCE,
            WORD_HIGHLIGHT_ENABLED_PREFERENCE,
            CUSTOM_SUBTITLE_COLORS_ENABLED_PREFERENCE,
        )
        assertEquals(expected, RESETTABLE_SETTING_KEYS.toSet())
        assertEquals(RESETTABLE_SETTING_KEYS.size, RESETTABLE_SETTING_KEYS.distinct().size)
    }
}
