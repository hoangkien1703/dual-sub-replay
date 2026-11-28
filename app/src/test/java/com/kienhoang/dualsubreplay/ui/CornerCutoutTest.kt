package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CornerCutoutTest {
    private val width = 1_080
    private val button = 189
    private val height = 126

    private fun room(
        left: Boolean,
        vararg holes: CutoutBox,
    ) = cornerRoom(holes.toList(), left, width, button, height)

    @Test
    fun aCameraInTheMiddleMovesNeitherButton() {
        val middle = CutoutBox(500, 0, 580, 90)
        assertEquals(CornerRoom(), room(left = true, middle))
        assertEquals(CornerRoom(), room(left = false, middle))
        assertEquals(CornerRoom(), room(left = true))
    }

    @Test
    fun aCameraInATopCornerMovesThatButtonBelowIt() {
        val topLeft = CutoutBox(40, 0, 120, 95)
        assertEquals(CornerRoom(top = 95), room(left = true, topLeft))
        assertEquals(CornerRoom(), room(left = false, topLeft))
        val topRight = CutoutBox(960, 10, 1_040, 100)
        assertEquals(CornerRoom(top = 100), room(left = false, topRight))
        assertEquals(CornerRoom(), room(left = true, topRight))
    }

    @Test
    fun aCameraOnTheSideEdgeMovesTheButtonBesideIt() {
        // Held sideways: the hole sits on the left edge, taller than it is wide.
        assertEquals(CornerRoom(side = 90), room(left = true, CutoutBox(0, 0, 90, 140)))
        assertEquals(CornerRoom(side = 90), room(left = false, CutoutBox(width - 90, 0, width, 140)))
        // Lower down the edge, it does not reach the button.
        assertEquals(CornerRoom(), room(left = true, CutoutBox(0, 400, 90, 600)))
    }
}
