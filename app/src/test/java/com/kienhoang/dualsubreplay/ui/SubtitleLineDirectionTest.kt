package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleLineDirectionTest {
    @Test
    fun aLineTakesTheDirectionOfItsFirstLetter() {
        assertTrue(startsRightToLeft("سلام، حال شما چطور است؟"))
        assertTrue(startsRightToLeft("«2024» سال خوبی بود"))
        assertTrue(startsRightToLeft("שלום עולם"))
        assertFalse(startsRightToLeft("Hello سلام"))
        assertFalse(startsRightToLeft("(123) English first"))
        assertFalse(startsRightToLeft("123 …"))
        assertFalse(startsRightToLeft(""))
    }

    @Test
    fun subtitleStylesFollowTheirContent() {
        assertEquals(TextDirection.Content, TextStyle(color = androidx.compose.ui.graphics.Color.White).inLineDirection().textDirection)
    }
}
