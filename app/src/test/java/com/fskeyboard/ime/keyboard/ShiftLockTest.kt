package com.fskeyboard.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftLockTest {
    private val shift = ShiftLock()

    @Test
    fun oneTapCapitalizesTheNextLetterOnly() {
        shift.onShift()
        assertEquals(ShiftMode.ONCE, shift.mode)
        assertTrue(shift.onCharacter())
        assertEquals(ShiftMode.OFF, shift.mode)
        assertFalse(shift.onCharacter())
    }

    @Test
    fun aSecondTapHoldsUppercaseUntilShift() {
        shift.onShift()
        shift.onShift()
        assertEquals(ShiftMode.LOCKED, shift.mode)
        assertFalse(shift.onCharacter())
        assertEquals(ShiftMode.LOCKED, shift.mode)
        shift.onShift()
        assertEquals(ShiftMode.OFF, shift.mode)
        assertFalse(shift.uppercase)
    }

    @Test
    fun aLetterBetweenTapsStartsAFreshShift() {
        shift.onShift()
        shift.onCharacter()
        shift.onShift()
        assertEquals(ShiftMode.ONCE, shift.mode)
    }

    @Test
    fun leavingLatinDropsAOneShotAndKeepsCapsLock() {
        shift.onShift()
        shift.releaseOnce()
        assertEquals(ShiftMode.OFF, shift.mode)
        shift.onShift()
        shift.onShift()
        shift.releaseOnce()
        assertEquals(ShiftMode.LOCKED, shift.mode)
    }
}
