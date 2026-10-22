package com.fskeyboard.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPreviewTest {
    @Test
    fun lettersZhuyinAndSymbolsFloatAndFunctionKeysDoNot() {
        val zhuyin = KeyboardLayouts.rows(KeyboardMode.ZHUYIN).flatMap { it.keys }
        assertTrue(zhuyin.any { it.label == "ㄅ" && KeyPreview.visible(it) })
        assertTrue(zhuyin.any { it.role == KeyRole.TONE && KeyPreview.visible(it) })
        assertTrue(zhuyin.any { it.action == KeyAction.Space && !KeyPreview.visible(it) })
        assertTrue(zhuyin.any { it.action == KeyAction.Backspace && !KeyPreview.visible(it) })
        assertTrue(zhuyin.none { it.icon != null && KeyPreview.visible(it) })

        val latin = KeyboardLayouts.rows(KeyboardMode.LATIN).flatMap { it.keys }
        assertTrue(latin.any { it.label == "a" && KeyPreview.visible(it) })
        assertTrue(latin.any { it.label == "," && KeyPreview.visible(it) })
        assertTrue(latin.any { it.action == KeyAction.Shift && !KeyPreview.visible(it) })
        assertTrue(latin.any { it.action == KeyAction.Enter && !KeyPreview.visible(it) })
    }

    @Test
    fun bubbleFloatsAboveTheKeyAndStaysOnTheBoard() {
        val mid = KeyPreview.place(
            keyLeft = 100f,
            keyTop = 80f,
            keyRight = 160f,
            boardWidth = 400f,
            popupWidth = 70f,
            popupHeight = 60f,
            margin = 4f,
            gap = 6f,
        )
        assertEquals(95f, mid.left, 0.01f)
        assertEquals(14f, mid.top, 0.01f)
        assertEquals(70f, mid.width, 0.01f)
        assertEquals(60f, mid.height, 0.01f)

        val topLeft = KeyPreview.place(
            keyLeft = 0f,
            keyTop = 0f,
            keyRight = 40f,
            boardWidth = 400f,
            popupWidth = 70f,
            popupHeight = 60f,
            margin = 4f,
            gap = 6f,
        )
        assertEquals(4f, topLeft.left, 0.01f)
        assertEquals(-66f, topLeft.top, 0.01f)

        val right = KeyPreview.place(
            keyLeft = 360f,
            keyTop = 40f,
            keyRight = 400f,
            boardWidth = 400f,
            popupWidth = 70f,
            popupHeight = 60f,
            margin = 4f,
            gap = 6f,
        )
        assertEquals(326f, right.left, 0.01f)

        val squeezed = KeyPreview.place(
            keyLeft = 0f,
            keyTop = 10f,
            keyRight = 20f,
            boardWidth = 50f,
            popupWidth = 80f,
            popupHeight = 40f,
            margin = 4f,
            gap = 4f,
        )
        assertEquals(42f, squeezed.width, 0.01f)
        assertEquals(4f, squeezed.left, 0.01f)
    }

    @Test
    fun aTapLingersOnlyBriefly() {
        assertTrue(KeyPreview.LINGER_MS in 1L..200L)
    }
}
