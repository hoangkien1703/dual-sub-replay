package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.*
import org.junit.Test

class CaptionVisibilityTest {
    @Test fun allNineCombinationsShareOverlayPolicyWhilePlayingAndPaused() {
        for (original in CaptionVisibility.entries) {
            for (translation in CaptionVisibility.entries) {
                for (paused in listOf(false, true)) {
                    val state =
                        DualSubUiState(
                            activeVideoId = "dQw4w9WgXcQ",
                            currentIndex = 0,
                            segments = listOf(SubtitleSegment(0, 0, 1000, "original", "translation")),
                            originalVisibility = original,
                            translatedVisibility = translation,
                            playbackPaused = paused,
                        )
                    val overlay = learningOverlayContent(state, TestUiStrings)!!
                    assertEquals(original.visible(paused), overlay.originalText != null)
                    assertEquals(translation.visible(paused), overlay.translatedText != null)
                    assertEquals(state.showOriginal(), overlay.originalText != null)
                    assertEquals(state.showTranslation(), overlay.translatedText != null)
                }
            }
        }
    }

    @Test fun missingAndUnknownPreferencesPreserveBothTracks() {
        assertEquals(CaptionVisibility.ALWAYS, storedCaptionVisibility(null))
        assertEquals(CaptionVisibility.ALWAYS, storedCaptionVisibility("future"))
    }

    @Test fun bufferingIsNotPausedAndOldSnapshotsDefaultToPlaying() {
        val snapshot =
            parseWebPlaybackSnapshot(
                org.json.JSONObject.quote("{\"url\":\"https://m.youtube.com\",\"currentSecond\":1,\"controlsVisible\":true}"),
            )!!
        assertFalse(snapshot.paused)
        assertFalse(CaptionVisibility.PAUSED.visible(snapshot.paused))
    }

    @Test fun snapshotCarriesTheWatchPlayerFrame() {
        val snapshot =
            parseWebPlaybackSnapshot(
                org.json.JSONObject.quote(
                    "{\"url\":\"https://m.youtube.com/watch?v=abcdefghijk\",\"currentSecond\":1," +
                        "\"playerFrame\":{\"bottom\":0.32,\"aspect\":0.45}}",
                ),
            )!!
        assertEquals(PagePlayerFrame(bottomFraction = 0.32f, viewportAspect = 0.45f), snapshot.playerFrame)

        val broken =
            parseWebPlaybackSnapshot(
                org.json.JSONObject.quote(
                    "{\"url\":\"https://m.youtube.com\",\"currentSecond\":1,\"playerFrame\":{\"bottom\":1.5,\"aspect\":0.45}}",
                ),
            )!!
        assertNull(broken.playerFrame)
    }

    @Test fun showWhilePlayingToggleSwitchesAlwaysAndOnlyWhenPaused() {
        // Ticked means the translation shows while playing, so the default (Always) is ticked.
        assertTrue(CaptionVisibility.ALWAYS.shownWhilePlaying)
        assertFalse(CaptionVisibility.PAUSED.shownWhilePlaying)
        assertFalse(CaptionVisibility.NEVER.shownWhilePlaying)
        assertEquals(CaptionVisibility.ALWAYS, storedCaptionVisibility(null))
        assertEquals(CaptionVisibility.PAUSED, CaptionVisibility.ALWAYS.toggledShowWhilePlaying())
        assertEquals(CaptionVisibility.ALWAYS, CaptionVisibility.PAUSED.toggledShowWhilePlaying())
        // Never is only in the full settings; a tap here shows the line again.
        assertEquals(CaptionVisibility.ALWAYS, CaptionVisibility.NEVER.toggledShowWhilePlaying())
    }

    @Test fun listeningPracticeStartsWhenNeitherLineShowsWhilePlaying() {
        val always = CaptionVisibility.ALWAYS
        val paused = CaptionVisibility.PAUSED
        val never = CaptionVisibility.NEVER
        // Both chips off in the gear popup.
        assertTrue(listeningPractice(paused, paused))
        // Set on the settings page: one line hidden for good, the other only while playing.
        assertTrue(listeningPractice(never, paused))
        assertTrue(listeningPractice(paused, never))
        // Either line showing while playing: the usual panel.
        assertFalse(listeningPractice(always, paused))
        assertFalse(listeningPractice(paused, always))
        assertFalse(listeningPractice(always, always))
        // Both Never: nothing to show, so the panel still goes away.
        assertFalse(listeningPractice(never, never))
    }

    @Test fun listeningPracticeKeepsThePanelWhileThePlayingVideoHidesBothLines() {
        val playing =
            DualSubUiState(
                activeVideoId = "dQw4w9WgXcQ",
                originalVisibility = CaptionVisibility.PAUSED,
                translatedVisibility = CaptionVisibility.PAUSED,
                playbackPaused = false,
            )
        assertFalse(playing.showOriginal())
        assertFalse(playing.showTranslation())
        assertTrue(playing.listeningInPanel())
        assertTrue("The panel no longer disappears, so the video never looks blank", playing.tracksVisible())
        assertFalse("Live subtitles have no fixed lines to hide", playing.copy(liveFallback = true).listeningInPanel())
        // Listen first and Dictation keep their own rules.
        assertFalse(playing.copy(studyPlayMode = StudyPlayMode.LISTEN_FIRST).listeningInPanel())
        assertFalse(playing.copy(studyPlayMode = StudyPlayMode.DICTATION).listeningInPanel())
        assertTrue(playing.copy(studyPlayMode = StudyPlayMode.REPEAT_LINE).listeningInPanel())
        // The overlay and full screen keep Only when paused: the line shows once the video pauses.
        assertTrue(playing.copy(playbackPaused = true).showOriginal())
        // Turning a line back on ends it.
        assertFalse(playing.copy(translatedVisibility = CaptionVisibility.ALWAYS).listeningInPanel())
    }

    @Test fun listeningPracticeRemembersRevealedLinesPerVideo() {
        val listening = ListeningPracticeState()
        val line = DictationKey("dQw4w9WgXcQ", 3)
        assertFalse(listening.isRevealed(line))
        listening.reveal(line)
        assertTrue(listening.isRevealed(line))
        assertFalse(listening.isRevealed(DictationKey("dQw4w9WgXcQ", 4)))
        assertFalse(listening.isRevealed(DictationKey("abcdefghijk", 3)))
        listening.hide(line)
        assertFalse("The hide icon turns it back into dots", listening.isRevealed(line))
    }
}
