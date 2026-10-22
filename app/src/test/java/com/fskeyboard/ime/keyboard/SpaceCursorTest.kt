package com.fskeyboard.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceCursorTest {
    private fun cursor() = SpaceCursor(slop = 10f)

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
        assertEquals(0f to 0f, cursor.move(12f, 3f))
        assertTrue(cursor.tracking)
        assertEquals(8f to 0f, cursor.move(20f, 3f))
    }

    @Test
    fun travelIsMeasuredFromTheAnchorEachTime() {
        val cursor = cursor()
        cursor.down(0f, 0f)
        assertEquals(0f to 0f, cursor.move(12f, 0f))
        assertEquals(8f to 0f, cursor.move(20f, 0f))
        assertEquals(18f to 4f, cursor.move(30f, 4f))
    }

    @Test
    fun reversingReportsNegativeTravel() {
        val cursor = cursor()
        cursor.down(0f, 0f)
        cursor.move(12f, 0f)
        assertEquals(-4f to 0f, cursor.move(8f, 0f))
    }

    @Test
    fun aFastSlideReportsTheWholeDistance() {
        val cursor = cursor()
        cursor.down(0f, 0f)
        cursor.move(10f, 0f)
        assertEquals(26f to 0f, cursor.move(36f, 0f))
    }

    @Test
    fun verticalTravelDoesNotChangeTheHorizontalAnchor() {
        val cursor = cursor()
        cursor.down(0f, 0f)
        cursor.move(0f, 10f)
        assertEquals(0f to 22f, cursor.move(0f, 32f))
        assertEquals(7f to 22f, cursor.move(7f, 32f))
    }

    @Test
    fun aStillHoldTracksFromTheFinger() {
        val cursor = cursor()
        cursor.down(40f, 50f)
        cursor.hold()
        assertTrue(cursor.tracking)
        assertEquals(8f to 0f, cursor.move(48f, 50f))
    }
}
