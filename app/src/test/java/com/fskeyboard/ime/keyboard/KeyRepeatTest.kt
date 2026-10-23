package com.fskeyboard.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyRepeatTest {
    @Test
    fun backspaceRepeatsALittleFasterAndArrowsRepeatWhileHeld() {
        assertEquals(320L, KeyRepeat.DELAY_MS)
        assertTrue(KeyRepeat.DELETE_MS < 45L)
        assertEquals(32L, KeyRepeat.DELETE_MS)
        assertEquals(KeyRepeat.DELETE_MS, KeyRepeat.interval(KeyAction.Backspace))
        assertEquals(KeyRepeat.ARROW_MS, KeyRepeat.interval(KeyAction.Computer(ComputerKey.LEFT)))
        assertEquals(KeyRepeat.ARROW_MS, KeyRepeat.interval(KeyAction.Computer(ComputerKey.UP)))
        assertEquals(KeyRepeat.ARROW_MS, KeyRepeat.interval(KeyAction.Computer(ComputerKey.RIGHT)))
        assertEquals(KeyRepeat.ARROW_MS, KeyRepeat.interval(KeyAction.Computer(ComputerKey.DOWN)))
        assertEquals(KeyRepeat.ARROW_MS, KeyRepeat.interval(KeyAction.Computer(ComputerKey.HOME)))
        assertEquals(KeyRepeat.ARROW_MS, KeyRepeat.interval(KeyAction.Computer(ComputerKey.END)))
        assertEquals(KeyRepeat.ARROW_MS, KeyRepeat.interval(KeyAction.Computer(ComputerKey.CTRL)))
        assertEquals(KeyRepeat.ARROW_MS, KeyRepeat.interval(KeyAction.Computer(ComputerKey.ALT)))
        assertEquals(KeyRepeat.ARROW_MS, KeyRepeat.interval(KeyAction.Computer(ComputerKey.FN)))
        assertEquals(KeyRepeat.ARROW_MS, KeyRepeat.interval(KeyAction.Computer(ComputerKey.META)))
        assertEquals(
            KeyRepeat.ARROW_MS,
            KeyRepeat.interval(KeyAction.Computer(ComputerKey.CTRL, repeating = true)),
        )
    }

    @Test
    fun keysThatStayOnceDoNotRepeat() {
        assertNull(KeyRepeat.interval(KeyAction.Computer(ComputerKey.TAB)))
        assertNull(KeyRepeat.interval(KeyAction.Computer(ComputerKey.ESC)))
        assertNull(KeyRepeat.interval(KeyAction.Computer(ComputerKey.PAGE_UP)))
        assertNull(KeyRepeat.interval(KeyAction.Computer(ComputerKey.PAGE_DOWN)))
        assertNull(KeyRepeat.interval(KeyAction.Computer(ComputerKey.FORWARD_DELETE)))
        assertNull(KeyRepeat.interval(KeyAction.Computer(ComputerKey.BANG)))
        assertNull(KeyRepeat.interval(KeyAction.Character('a')))
        assertFalse(ComputerKey.HOME.isArrow)
        assertFalse(ComputerKey.CTRL.isArrow)
        assertTrue(ComputerKey.CTRL.repeats)
        assertTrue(ComputerKey.HOME.repeats)
        assertTrue(ComputerKey.RIGHT.isArrow)
        assertFalse(ComputerKey.TAB.repeats)
        assertFalse(ComputerKey.ESC.repeats)
    }

    @Test
    fun aHeldArrowIsNotSentAgainWhenTheFingerLifts() {
        val arrow = KeyAction.Computer(ComputerKey.RIGHT)
        assertFalse(KeyRepeat.alreadyDelivered(arrow, repeating = null, backspaceSent = false))
        assertTrue(KeyRepeat.alreadyDelivered(arrow, repeating = arrow, backspaceSent = false))
        val ctrl = KeyAction.Computer(ComputerKey.CTRL)
        val home = KeyAction.Computer(ComputerKey.HOME)
        assertTrue(KeyRepeat.alreadyDelivered(ctrl, repeating = ctrl, backspaceSent = false))
        assertTrue(KeyRepeat.alreadyDelivered(home, repeating = home, backspaceSent = false))
        assertTrue(
            KeyRepeat.alreadyDelivered(
                ctrl,
                repeating = KeyAction.Computer(ComputerKey.CTRL, repeating = true),
                backspaceSent = false,
            ),
        )
        assertFalse(
            KeyRepeat.alreadyDelivered(
                ctrl,
                repeating = KeyAction.Computer(ComputerKey.ALT),
                backspaceSent = false,
            ),
        )
        assertFalse(
            KeyRepeat.alreadyDelivered(
                KeyAction.Computer(ComputerKey.LEFT),
                repeating = arrow,
                backspaceSent = false,
            ),
        )
        assertTrue(KeyRepeat.alreadyDelivered(KeyAction.Backspace, repeating = null, backspaceSent = true))
        assertFalse(KeyRepeat.alreadyDelivered(KeyAction.Backspace, repeating = null, backspaceSent = false))
    }
}
