package com.fskeyboard.ime.candidate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionPreviewTest {
    @Test
    fun bubbleIsWhiteOnTranslucentDarkGray() {
        assertEquals(0xFFFFFFFF.toInt(), CompositionPreview.TEXT)
        assertEquals(0xB32C2C2E.toInt(), CompositionPreview.FILL)
    }

    @Test
    fun bubbleHangsOnTheTopLeftCornerOfTheCandidateBar() {
        val frame = CompositionPreview.place(
            anchorLeft = 0,
            anchorTop = 500,
            bubbleWidth = 120,
            bubbleHeight = 36,
            boundsLeft = 0,
            boundsRight = 1080,
            margin = 8,
        )
        assertEquals(8, frame.left)
        assertEquals(464, frame.top)
        assertEquals(500, frame.top + frame.height)
        assertEquals(120, frame.width)
        assertEquals(36, frame.height)
        assertTrue(frame.top < 500)
    }

    @Test
    fun longReadingStaysInsideTheKeyboardWindow() {
        val frame = CompositionPreview.place(
            anchorLeft = 0,
            anchorTop = 400,
            bubbleWidth = 2000,
            bubbleHeight = 36,
            boundsLeft = 0,
            boundsRight = 400,
            margin = 8,
        )
        assertEquals(8, frame.left)
        assertEquals(384, frame.width)
        assertEquals(392, frame.left + frame.width)
    }

    @Test
    fun offsetWindowStillHangsOnTheBar() {
        val frame = CompositionPreview.place(
            anchorLeft = 100,
            anchorTop = 800,
            bubbleWidth = 80,
            bubbleHeight = 32,
            boundsLeft = 100,
            boundsRight = 500,
            margin = 8,
        )
        assertEquals(108, frame.left)
        assertEquals(768, frame.top)
        assertEquals(800, frame.top + frame.height)
    }

    @Test
    fun nothingToDrawWhenTheMarginsEatTheWindow() {
        val frame = CompositionPreview.place(
            anchorLeft = 0,
            anchorTop = 100,
            bubbleWidth = 40,
            bubbleHeight = 20,
            boundsLeft = 0,
            boundsRight = 10,
            margin = 8,
        )
        assertEquals(0, frame.width)
    }
}
