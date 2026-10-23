package com.fskeyboard.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitLayoutTest {
    @Test
    fun templateMarginsAndAThirdSmallerKeys() {
        assertEquals(0.117f, SplitLayout.SIDE, 0.0001f)
        assertEquals(0.883f, SplitLayout.rightEdge(), 0.0001f)
        assertEquals(2f / 3f, SplitLayout.SCALE, 0f)
        val templateW = SplitLayout.KEY
        assertEquals(templateW * 2f / 3f, SplitLayout.placedW(templateW), 0.0001f)
        val center = SplitLayout.SIDE + templateW / 2f
        val placedLeft = SplitLayout.placedX(SplitLayout.SIDE, templateW)
        assertEquals(center, placedLeft + SplitLayout.placedW(templateW) / 2f, 0.0001f)
        assertEquals(0.643f, SplitLayout.keyX(SplitLayout.RIGHT, 0), 0.0001f)
        val width = 1000f
        assertEquals(width * SplitLayout.ROW * SplitLayout.SCALE, SplitLayout.rowSpan(width), 0.01f)
        assertTrue(SplitLayout.KEY_FACE < SplitLayout.ROW)
        val face = SplitLayout.placedH() * width
        val pitch = SplitLayout.rowSpan(width)
        assertTrue(face < pitch)
        assertEquals(
            (SplitLayout.ROW - SplitLayout.KEY_FACE) * SplitLayout.SCALE * width / 2f,
            (pitch - face) / 2f,
            0.05f,
        )
    }

    @Test
    fun shorterRightClusterStaysOnTheOuterEdge() {
        val origin = SplitLayout.rightOriginFor(4, indent = 0)
        val last = SplitLayout.keyX(origin, 3) + SplitLayout.KEY
        assertEquals(SplitLayout.rightEdge(), last, 0.0001f)
    }
}
