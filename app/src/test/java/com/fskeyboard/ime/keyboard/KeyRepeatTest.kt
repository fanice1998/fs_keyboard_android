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
    }

    @Test
    fun otherComputerKeysDoNotRepeat() {
        assertNull(KeyRepeat.interval(KeyAction.Computer(ComputerKey.HOME)))
        assertNull(KeyRepeat.interval(KeyAction.Computer(ComputerKey.END)))
        assertNull(KeyRepeat.interval(KeyAction.Computer(ComputerKey.TAB)))
        assertNull(KeyRepeat.interval(KeyAction.Computer(ComputerKey.ESC)))
        assertNull(KeyRepeat.interval(KeyAction.Computer(ComputerKey.CTRL)))
        assertNull(KeyRepeat.interval(KeyAction.Computer(ComputerKey.FORWARD_DELETE)))
        assertNull(KeyRepeat.interval(KeyAction.Character('a')))
        assertFalse(ComputerKey.HOME.isArrow)
        assertTrue(ComputerKey.RIGHT.isArrow)
    }

    @Test
    fun aHeldArrowIsNotSentAgainWhenTheFingerLifts() {
        val arrow = KeyAction.Computer(ComputerKey.RIGHT)
        assertFalse(KeyRepeat.alreadyDelivered(arrow, repeating = null, backspaceSent = false))
        assertTrue(KeyRepeat.alreadyDelivered(arrow, repeating = arrow, backspaceSent = false))
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
