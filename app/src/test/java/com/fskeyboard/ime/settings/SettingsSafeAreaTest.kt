package com.fskeyboard.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSafeAreaTest {
    @Test
    fun contentAlreadyClearOfTheTitleNeedsNoPadding() {
        val padding = SettingsSafeArea.padding(
            contentLeft = 0,
            contentTop = 180,
            contentRight = 400,
            contentBottom = 700,
            windowWidth = 400,
            windowHeight = 800,
            titleBottom = 180,
            insetLeft = 0,
            insetTop = 80,
            insetRight = 0,
            insetBottom = 48,
        )
        assertEquals(SettingsPadding(0, 0, 0, 0), padding)
    }

    @Test
    fun contentUnderTheTitlePadsToTheTitleBottom() {
        val padding = SettingsSafeArea.padding(
            contentLeft = 0,
            contentTop = 0,
            contentRight = 400,
            contentBottom = 800,
            windowWidth = 400,
            windowHeight = 800,
            titleBottom = 160,
            insetLeft = 0,
            insetTop = 80,
            insetRight = 0,
            insetBottom = 48,
        )
        assertEquals(SettingsPadding(0, 160, 0, 48), padding)
    }

    @Test
    fun statusBarTallerThanTheTitleStillClearsTheStatusBar() {
        val padding = SettingsSafeArea.padding(
            contentLeft = 0,
            contentTop = 0,
            contentRight = 400,
            contentBottom = 800,
            windowWidth = 400,
            windowHeight = 800,
            titleBottom = 40,
            insetLeft = 12,
            insetTop = 80,
            insetRight = 12,
            insetBottom = 0,
        )
        assertEquals(SettingsPadding(12, 80, 12, 0), padding)
    }

    @Test
    fun unknownWindowSizeDoesNotInventPadding() {
        val padding = SettingsSafeArea.padding(
            contentLeft = 0,
            contentTop = 0,
            contentRight = 0,
            contentBottom = 0,
            windowWidth = 0,
            windowHeight = 0,
            titleBottom = 160,
            insetLeft = 0,
            insetTop = 80,
            insetRight = 0,
            insetBottom = 48,
        )
        assertEquals(SettingsPadding(0, 0, 0, 0), padding)
    }
}
