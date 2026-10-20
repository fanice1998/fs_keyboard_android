package com.fskeyboard.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceCursorTest {
    private fun cursor() = SpaceCursor(slop = 10f, xPerStep = 8f, yPerStep = 22f)

    @Test
    fun aWobbleInsideTheSlopStaysATap() {
        val cursor = cursor()
        cursor.down(0f, 0f)
        assertNull(cursor.move(9f, 0f))
        assertFalse(cursor.tracking)
    }

    @Test
    fun crossingTheSlopStartsTrackingWithoutSpendingThatDistance() {
        val cursor = cursor()
        cursor.down(0f, 0f)
        assertEquals(0 to 0, cursor.move(12f, 3f))
        assertTrue(cursor.tracking)
        assertEquals(1 to 0, cursor.move(20f, 3f))
    }

    @Test
    fun leftoverDistanceIsKeptUntilItMakesAStep() {
        val cursor = cursor()
        cursor.down(0f, 0f)
        cursor.move(10f, 0f)
        assertEquals(0 to 0, cursor.move(17f, 0f))
        assertEquals(1 to 0, cursor.move(18f, 0f))
    }

    @Test
    fun reversingUndoesThePartialStepBeforeMovingBack() {
        val cursor = cursor()
        cursor.down(0f, 0f)
        cursor.move(10f, 0f)
        cursor.move(17f, 0f)
        assertEquals(0 to 0, cursor.move(10f, 0f))
        assertEquals(-1 to 0, cursor.move(-1f, 0f))
    }

    @Test
    fun aFastSlideTakesSeveralStepsAndKeepsTheFraction() {
        val cursor = cursor()
        cursor.down(0f, 0f)
        cursor.move(10f, 0f)
        assertEquals(3 to 0, cursor.move(10f + 24f + 1.6f, 0f))
        assertEquals(0 to 0, cursor.move(10f + 24f + 1.6f + 6f, 0f))
        assertEquals(1 to 0, cursor.move(10f + 24f + 1.6f + 8f, 0f))
    }

    @Test
    fun verticalTravelDoesNotSpendHorizontalDistance() {
        val cursor = cursor()
        cursor.down(0f, 0f)
        cursor.move(0f, 10f)
        assertEquals(0 to 1, cursor.move(0f, 32f))
        assertEquals(0 to 0, cursor.move(7f, 32f))
    }

    @Test
    fun aStillHoldTracksFromTheFingerWithoutAStep() {
        val cursor = cursor()
        cursor.down(40f, 50f)
        cursor.hold()
        assertTrue(cursor.tracking)
        assertEquals(1 to 0, cursor.move(48f, 50f))
    }
}
