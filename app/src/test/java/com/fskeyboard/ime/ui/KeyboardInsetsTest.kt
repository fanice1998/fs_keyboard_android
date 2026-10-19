package com.fskeyboard.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardInsetsTest {
    @Test
    fun usesTheReportedNavigationInset() {
        val padding = KeyboardInsets.resolve(0, 12, reportedBottom = 48, fallbackBottom = 96)
        assertEquals(0, padding.left)
        assertEquals(12, padding.right)
        assertEquals(48, padding.bottom)
    }

    @Test
    fun missingInsetUsesTheFallbackAndAnExplicitZeroDoesNot() {
        assertEquals(96, KeyboardInsets.resolve(0, 0, reportedBottom = null, fallbackBottom = 96).bottom)
        assertEquals(0, KeyboardInsets.resolve(0, 0, reportedBottom = 0, fallbackBottom = 96).bottom)
    }
}
