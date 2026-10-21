package com.fskeyboard.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardHeightTest {
    @Test
    fun defaultSitsInsideTheAdjustableRange() {
        assertTrue(KeyboardHeight.DEFAULT_ROW_DP in KeyboardHeight.MIN_ROW_DP..KeyboardHeight.MAX_ROW_DP)
        assertEquals(91, KeyboardHeight.percentOfDefault(KeyboardHeight.DEFAULT_ROW_DP))
        assertEquals(100, KeyboardHeight.percentOfDefault(KeyboardHeight.STANDARD_ROW_DP))
    }

    @Test
    fun clampKeepsASavedHeightInsideTheRange() {
        assertEquals(KeyboardHeight.MIN_ROW_DP, KeyboardHeight.clamp(1))
        assertEquals(KeyboardHeight.MAX_ROW_DP, KeyboardHeight.clamp(200))
        assertEquals(52, KeyboardHeight.clamp(52))
    }
}
