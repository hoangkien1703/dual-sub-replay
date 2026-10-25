package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Subtitle lines take their writing direction from their own first letter (issue #152), so a
 * Persian, Arabic, Urdu or Hebrew line reads right to left with its punctuation, brackets and
 * numbers in place, whatever language the app itself is in.
 */
internal fun TextStyle.inLineDirection(): TextStyle = copy(textDirection = TextDirection.Content)

/** Whether [text]'s first letter belongs to a right-to-left script; false when it has no letter. */
internal fun startsRightToLeft(text: CharSequence): Boolean {
    var index = 0
    while (index < text.length) {
        val codePoint = Character.codePointAt(text, index)
        when (Character.getDirectionality(codePoint)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
        }
        index += Character.charCount(codePoint)
    }
    return false
}

/** A line written the other way from the screen fills the width, so it starts on its own side. */
internal fun Modifier.lineSide(
    text: CharSequence,
    layoutDirection: LayoutDirection,
): Modifier = if (startsRightToLeft(text) != (layoutDirection == LayoutDirection.Rtl)) fillMaxWidth() else this
