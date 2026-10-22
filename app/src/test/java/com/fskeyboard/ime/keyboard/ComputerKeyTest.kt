package com.fskeyboard.ime.keyboard

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerKeyTest {
    @Test
    fun upwardSwipeSelectsTheComputerKey() {
        val swipe = KeySwipe(slop = 10f)
        assertFalse(swipe.move(0f, -9f))
        assertTrue(swipe.move(0f, -10f))
        assertFalse(swipe.move(0f, 0f))
        assertFalse(swipe.move(16f, -8f))
        assertTrue(swipe.move(8f, -20f))
    }

    @Test
    fun modifiersToggleAndCombineUntilCleared() {
        val modifiers = ComputerModifiers()
        modifiers.toggle(ComputerKey.CTRL)
        assertEquals(setOf(ComputerKey.CTRL), modifiers.active())
        modifiers.toggle(ComputerKey.ALT)
        assertEquals(setOf(ComputerKey.CTRL, ComputerKey.ALT), modifiers.active())
        val meta = modifiers.metaState(shift = true)
        assertTrue(meta and KeyEvent.META_CTRL_ON != 0)
        assertTrue(meta and KeyEvent.META_ALT_ON != 0)
        assertTrue(meta and KeyEvent.META_SHIFT_ON != 0)
        modifiers.toggle(ComputerKey.CTRL)
        assertEquals(setOf(ComputerKey.ALT), modifiers.active())
        modifiers.toggle(ComputerKey.ESC)
        assertEquals(setOf(ComputerKey.ALT), modifiers.active())
        modifiers.clear()
        assertFalse(modifiers.isActive())
        assertEquals(0, modifiers.metaState())
    }

    @Test
    fun lettersAndDigitsHaveKeyCodes() {
        assertEquals(KeyEvent.KEYCODE_C, computerKeyCode('c'))
        assertEquals(KeyEvent.KEYCODE_C, computerKeyCode('C'))
        assertEquals(KeyEvent.KEYCODE_1, computerKeyCode('1'))
        assertNull(computerKeyCode(','))
    }

    @Test
    fun englishLettersKeepTheirComputerKeyOnAnUpSwipe() {
        val on = KeyboardLayouts.rows(KeyboardMode.LATIN, computerKeys = true).flatMap { it.keys }
        fun swipe(label: String) = on.first { it.label == label }.swipeUp
        assertEquals(KeyAction.Computer(ComputerKey.ESC), swipe("q"))
        assertEquals(KeyAction.Computer(ComputerKey.TAB), swipe("w"))
        assertEquals(KeyAction.Computer(ComputerKey.CTRL), swipe("e"))
        assertEquals(KeyAction.Computer(ComputerKey.ALT), swipe("r"))
        assertEquals(KeyAction.Computer(ComputerKey.UP), swipe("t"))
        assertEquals(KeyAction.Computer(ComputerKey.LEFT), swipe("u"))
        assertEquals(KeyAction.Computer(ComputerKey.HOME), swipe("o"))
        assertEquals(KeyAction.Computer(ComputerKey.END), swipe("p"))
        assertEquals(KeyAction.Computer(ComputerKey.PAGE_UP), swipe("a"))
        assertEquals(KeyAction.Computer(ComputerKey.PAGE_DOWN), swipe("s"))
        assertEquals(KeyAction.Computer(ComputerKey.FORWARD_DELETE), swipe("d"))
        assertEquals(KeyAction.Computer(ComputerKey.META), swipe("f"))
        assertNull(on.first { it.label == "z" }.swipeUp)
        assertNull(on.first { it.label == "g" }.swipeUp)

        val off = KeyboardLayouts.rows(KeyboardMode.LATIN).flatMap { it.keys }
        assertTrue(off.none { it.swipeUp != null })
        val zhuyin = KeyboardLayouts.rows(KeyboardMode.ZHUYIN).flatMap { it.keys }
        assertTrue(zhuyin.none { it.swipeUp != null })
        val shifted = KeyboardLayouts.rows(
            KeyboardMode.LATIN,
            shifted = true,
            computerKeys = true,
        ).flatMap { it.keys }
        assertEquals("Q", shifted.first { it.swipeUp == KeyAction.Computer(ComputerKey.ESC) }.label)
    }
}
