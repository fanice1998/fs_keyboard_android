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

    @Test
    fun samsungButtonRowIsTallerThanTheGestureInset() {
        assertEquals(
            144,
            KeyboardInsets.bottomClearance(
                navigationBars = 24,
                captionBar = 0,
                mandatoryGestures = 0,
                tappableElements = 0,
                embeddedBar = 0,
                systemFrame = 144,
                preferSystemFrame = true,
            ),
        )
    }

    @Test
    fun captionBarClearsTheSwitcherRowWithoutForcingTheFrame() {
        assertEquals(
            144,
            KeyboardInsets.bottomClearance(
                navigationBars = 24,
                captionBar = 144,
                mandatoryGestures = 0,
                tappableElements = 0,
                embeddedBar = 0,
                systemFrame = 0,
                preferSystemFrame = false,
            ),
        )
    }

    @Test
    fun measuredEmbeddedBarWinsOverTheFrame() {
        assertEquals(
            160,
            KeyboardInsets.bottomClearance(
                navigationBars = 48,
                captionBar = 48,
                mandatoryGestures = 48,
                tappableElements = 0,
                embeddedBar = 160,
                systemFrame = 144,
                preferSystemFrame = true,
            ),
        )
    }

    @Test
    fun landscapeSitsOnTheMeasuredBar() {
        assertEquals(
            24,
            KeyboardInsets.bottomClearance(
                navigationBars = 24,
                captionBar = 0,
                mandatoryGestures = 0,
                tappableElements = 0,
                embeddedBar = 0,
                systemFrame = 144,
                preferSystemFrame = true,
                landscape = true,
            ),
        )
        assertEquals(
            48,
            KeyboardInsets.bottomClearance(
                navigationBars = 24,
                captionBar = 48,
                mandatoryGestures = 0,
                tappableElements = 0,
                embeddedBar = 0,
                systemFrame = 144,
                preferSystemFrame = true,
                landscape = true,
            ),
        )
    }

    @Test
    fun otherDevicesDoNotForceTheSystemFrame() {
        assertEquals(
            48,
            KeyboardInsets.bottomClearance(
                navigationBars = 48,
                captionBar = 0,
                mandatoryGestures = 16,
                tappableElements = 0,
                embeddedBar = 0,
                systemFrame = 144,
                preferSystemFrame = false,
            ),
        )
    }
}
