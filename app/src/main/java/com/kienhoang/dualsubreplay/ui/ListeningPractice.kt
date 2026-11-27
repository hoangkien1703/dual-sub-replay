package com.kienhoang.dualsubreplay.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Listening practice (owner pick 2026-11-27, turned on when neither line shows while playing; see
 * [listeningPractice]): the lines the learner tapped to show, per video and line. Kept only while the
 * app runs, above the subtitle panel like [DictationState], so closing the panel or turning the phone
 * keeps them. Pausing shows only the line just heard (see `PanelLineHiding`), never the next lines.
 */
internal class ListeningPracticeState {
    private val revealed = mutableStateMapOf<DictationKey, Boolean>()

    fun isRevealed(key: DictationKey): Boolean = revealed[key] == true

    fun reveal(key: DictationKey) {
        revealed[key] = true
    }

    /** The hide icon on a shown line turns it back into dots (owner question 2026-11-27). */
    fun hide(key: DictationKey) {
        revealed.remove(key)
    }
}

internal val LocalListeningPractice = staticCompositionLocalOf<ListeningPracticeState?> { null }
