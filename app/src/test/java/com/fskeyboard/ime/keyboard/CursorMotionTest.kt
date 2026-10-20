package com.fskeyboard.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class CursorMotionTest {
    @Test
    fun horizontalStepsMoveByGraphemesAndStopAtTheEnds() {
        val text = "ab👍c"
        assertEquals(1, CursorMotion.move(text, 0, 1, 0))
        assertEquals(text.length, CursorMotion.move(text, 2, 5, 0))
        assertEquals(4, CursorMotion.move(text, text.length, -1, 0))
        assertEquals(2, CursorMotion.move(text, text.length, -2, 0))
        assertEquals(0, CursorMotion.move(text, 0, -3, 0))
    }

    @Test
    fun horizontalStepsStopAtTheEndsOfTheLine() {
        val text = "ab\ncd"
        assertEquals(3, CursorMotion.move(text, 3, -1, 0))
        assertEquals(3, CursorMotion.move(text, 3, -4, 0))
        assertEquals(2, CursorMotion.move(text, 2, 1, 0))
        assertEquals(2, CursorMotion.move(text, 1, 3, 0))
        assertEquals(text.length, CursorMotion.move(text, 4, 5, 0))
        assertEquals(0, CursorMotion.move(text, 1, -5, 0))
    }

    @Test
    fun anEmojiIsOneStep() {
        val thumbs = "👍"
        assertEquals(thumbs.length, CursorMotion.move(thumbs, 0, 1, 0))
        assertEquals(0, CursorMotion.move(thumbs, thumbs.length, -1, 0))
    }

    @Test
    fun verticalStepsKeepTheColumnAndStopOnTheFirstAndLastLine() {
        val text = "ab\ncd\nef"
        assertEquals(4, CursorMotion.move(text, 1, 0, 1))
        assertEquals(1, CursorMotion.move(text, 4, 0, -1))
        assertEquals(0, CursorMotion.move(text, 3, 0, -1))
        assertEquals(text.length, CursorMotion.move(text, text.length, 0, 1))
        assertEquals(7, CursorMotion.move(text, 4, 0, 3))
    }

    @Test
    fun aShorterLineStopsAfterItsLastCharacter() {
        val text = "abc\nz"
        assertEquals(text.length, CursorMotion.move(text, 2, 0, 1))
    }
}
