package com.fskeyboard.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun aGlideSnapsToTheNearestGrapheme() {
        val text = "abcd"
        assertEquals(1, nearest(text, 0, 14f, 0f))
        assertEquals(2, nearest(text, 0, 16f, 0f))
        assertEquals(0, nearest(text, 0, 4f, 0f))
        assertEquals(3, nearest(text, text.length, -14f, 0f))
    }

    @Test
    fun aTieFollowsTheDirectionOfTheFinger() {
        assertEquals(2, nearest("abcd", 0, 15f, 0f))
        assertEquals(2, nearest("abcd", 4, -15f, 0f))
    }

    @Test
    fun aGlidePastTheEndOfALineStaysOnThatLine() {
        assertEquals(2, nearest("ab\ncd", 0, 100f, 0f))
    }

    @Test
    fun verticalTravelPicksTheNearestLineThenTheNearestGrapheme() {
        val text = "ab\ncd\nef"
        assertEquals(4, nearest(text, 1, 0f, 25f))
        assertEquals(1, nearest(text, 1, 4f, 4f))
        assertEquals(4, nearest("ab\ncd", 1, 0f, 20f))
        assertEquals(3, nearest("ab\ncd", 0, 0f, 10f))
        assertEquals(0, nearest("ab\ncd", 3, 0f, -10f))
    }

    @Test
    fun theCaretDoesNotLandInsideAnEmoji() {
        val thumbs = "a👍b"
        assertEquals(1 + "👍".length, nearest(thumbs, 0, 20f, 0f))
    }

    @Test
    fun anEmptyFieldStaysAtTheStart() {
        assertEquals(0, nearest("", 0, 30f, 10f))
    }

    @Test
    fun composingSelectionSitsInsideTheUnderlinedText() {
        assertEquals(7, composingSelection(selectionEnd = 10, textLength = 4, caret = 1))
        assertNull(composingSelection(selectionEnd = 10, textLength = 4, caret = 4))
        assertNull(composingSelection(selectionEnd = 2, textLength = 4, caret = 1))
    }

    private fun nearest(text: String, origin: Int, dx: Float, dy: Float): Int {
        return CursorMotion.nearest(text, origin, dx, dy, { it.length * 10f }, 20f)
    }
}
