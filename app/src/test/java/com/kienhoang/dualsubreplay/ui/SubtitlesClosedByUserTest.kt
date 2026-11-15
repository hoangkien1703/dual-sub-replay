package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlesClosedByUserTest {
    @Test fun automaticRestoreOpensThePanel() {
        val hidden = DualSubUiState(subtitlePanelVisible = false)
        assertTrue(hidden.withPanelShown().subtitlePanelVisible)
    }

    @Test fun automaticRestoreKeepsThePanelClosedAfterThePersonClosedIt() {
        val closed = DualSubUiState(subtitlePanelVisible = false, subtitlesClosedByUser = true)
        assertFalse(closed.withPanelShown().subtitlePanelVisible)
        assertTrue(closed.withPanelShown().subtitlesClosedByUser)
    }

    @Test fun nothingIsClosedWhenTheAppStarts() {
        assertFalse(DualSubUiState().subtitlesClosedByUser)
    }
}
