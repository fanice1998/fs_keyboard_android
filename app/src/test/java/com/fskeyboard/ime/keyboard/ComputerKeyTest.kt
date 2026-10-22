package com.fskeyboard.ime.keyboard

import android.view.KeyEvent
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerKeyTest {
    @Test
    fun swipeDirectionIsOneOfEightSlices() {
        val swipe = KeySwipe(slop = 10f)
        assertNull(swipe.move(0f, -9f))
        assertEquals(SwipeDirection.UP, swipe.move(0f, -10f))
        assertNull(swipe.move(0f, 0f))
        assertEquals(SwipeDirection.UP, SwipeDirection.of(8f, -20f, slop = 10f))
        assertEquals(SwipeDirection.UP, SwipeDirection.of(0f, -20f, slop = 10f))
        assertEquals(SwipeDirection.UP_RIGHT, SwipeDirection.of(20f, -20f, slop = 10f))
        assertEquals(SwipeDirection.RIGHT, SwipeDirection.of(20f, 0f, slop = 10f))
        assertEquals(SwipeDirection.DOWN_RIGHT, SwipeDirection.of(20f, 20f, slop = 10f))
        assertEquals(SwipeDirection.DOWN, SwipeDirection.of(0f, 20f, slop = 10f))
        assertEquals(SwipeDirection.DOWN_LEFT, SwipeDirection.of(-20f, 20f, slop = 10f))
        assertEquals(SwipeDirection.LEFT, SwipeDirection.of(-20f, 0f, slop = 10f))
        assertEquals(SwipeDirection.UP_LEFT, SwipeDirection.of(-20f, -20f, slop = 10f))
        val boundary = Math.PI / 8.0
        assertEquals(SwipeDirection.UP_RIGHT, directionAt(boundary))
        assertEquals(SwipeDirection.UP, directionAt(boundary - 0.05))
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
    fun symbolsCanBeAssignedOnce() {
        val symbols = ComputerKey.values().mapNotNull { it.symbol }
        assertEquals(
            "!@#\$%^&*(){}[]`~-_=+\\|;:'\",./<>?…—·°±×÷".toList(),
            symbols,
        )
        assertEquals(symbols.size, symbols.toSet().size)
        val map = ComputerKeyMap.EMPTY.assign('z', SwipeDirection.RIGHT, ComputerKey.BANG)!!
        assertEquals(ComputerKey.BANG, map.swipes('z')[SwipeDirection.RIGHT])
        assertNull(map.assign('a', SwipeDirection.UP, ComputerKey.BANG))
        assertEquals("z/right=bang", map.encode())
        assertEquals(map, ComputerKeyMap.decode(map.encode()))
        val onKey = KeyboardLayouts.rows(
            KeyboardMode.LATIN,
            computerKeys = true,
            computerMap = map,
        ).flatMap { it.keys }
        assertEquals(ComputerKey.BANG, onKey.first { it.label == "z" }.swipes[SwipeDirection.RIGHT])
    }

    @Test
    fun aComputerKeyCanBelongToOnlyOneSlot() {
        val map = ComputerKeyMap.DEFAULT
        assertEquals(ComputerSlot('q', SwipeDirection.UP), map.owner(ComputerKey.ESC))
        assertNull(map.assign('z', SwipeDirection.LEFT, ComputerKey.ESC))
        assertNull(map.assign('1', SwipeDirection.UP, ComputerKey.TAB))
        val replaced = map.assign('q', SwipeDirection.UP, ComputerKey.TAB)
        assertNull(replaced)
        val moved = map.clear('w', SwipeDirection.UP).assign('z', SwipeDirection.DOWN_LEFT, ComputerKey.TAB)
        assertEquals(ComputerSlot('z', SwipeDirection.DOWN_LEFT), moved!!.owner(ComputerKey.TAB))
        val sameSlot = map
            .clear('w', SwipeDirection.UP)
            .assign('q', SwipeDirection.UP, ComputerKey.TAB)!!
        assertEquals(ComputerKey.TAB, sameSlot.swipes('q')[SwipeDirection.UP])
        assertNull(sameSlot.owner(ComputerKey.ESC))
        assertEquals(map, map.assign('q', SwipeDirection.UP, ComputerKey.ESC))
        assertEquals(map, map.clear('z', SwipeDirection.UP))
    }

    @Test
    fun theMapRoundTripsAndKeepsTheFirstDuplicate() {
        val map = ComputerKeyMap.EMPTY
            .assign('a', SwipeDirection.UP, ComputerKey.ESC)!!
            .assign('a', SwipeDirection.DOWN, ComputerKey.TAB)!!
        assertEquals("a/up=esc,a/down=tab", map.encode())
        assertEquals(map, ComputerKeyMap.decode(map.encode()))
        assertEquals(ComputerKeyMap.EMPTY, ComputerKeyMap.decode(""))
        assertEquals(ComputerKeyMap.EMPTY, ComputerKeyMap.decode("   "))
        val messy = ComputerKeyMap.decode("nope,Q/UP=ESC, q/left=esc,z/down=nope,q/right=tab")
        assertEquals(ComputerKey.ESC, messy.swipes('q')[SwipeDirection.UP])
        assertNull(messy.swipes('q')[SwipeDirection.LEFT])
        assertEquals(ComputerKey.TAB, messy.swipes('q')[SwipeDirection.RIGHT])
        assertEquals(ComputerKeyMap.DEFAULT, ComputerKeyMap.decode(ComputerKeyMap.DEFAULT.encode()))
    }

    @Test
    fun englishLettersKeepTheirComputerKeyOnAnUpSwipe() {
        val on = KeyboardLayouts.rows(KeyboardMode.LATIN, computerKeys = true).flatMap { it.keys }
        fun swipe(label: String) = on.first { it.label == label }.swipes[SwipeDirection.UP]
        assertEquals(ComputerKey.ESC, swipe("q"))
        assertEquals(ComputerKey.TAB, swipe("w"))
        assertEquals(ComputerKey.CTRL, swipe("e"))
        assertEquals(ComputerKey.ALT, swipe("r"))
        assertEquals(ComputerKey.UP, swipe("t"))
        assertEquals(ComputerKey.LEFT, swipe("u"))
        assertEquals(ComputerKey.HOME, swipe("o"))
        assertEquals(ComputerKey.END, swipe("p"))
        assertEquals(ComputerKey.PAGE_UP, swipe("a"))
        assertEquals(ComputerKey.PAGE_DOWN, swipe("s"))
        assertEquals(ComputerKey.FORWARD_DELETE, swipe("d"))
        assertEquals(ComputerKey.META, swipe("f"))
        assertTrue(on.first { it.label == "z" }.swipes.isEmpty())
        assertTrue(on.first { it.label == "g" }.swipes.isEmpty())

        val off = KeyboardLayouts.rows(KeyboardMode.LATIN).flatMap { it.keys }
        assertTrue(off.none { it.swipes.isNotEmpty() })
        val zhuyin = KeyboardLayouts.rows(KeyboardMode.ZHUYIN).flatMap { it.keys }
        assertTrue(zhuyin.none { it.swipes.isNotEmpty() })
        val shifted = KeyboardLayouts.rows(
            KeyboardMode.LATIN,
            shifted = true,
            computerKeys = true,
        ).flatMap { it.keys }
        assertEquals(
            mapOf(SwipeDirection.UP to ComputerKey.ESC),
            shifted.first { it.label == "Q" }.swipes,
        )
    }

    @Test
    fun aCustomMapReplacesTheDefaultAndStaysOffUntilComputerKeysAreOn() {
        val map = ComputerKeyMap.DEFAULT
            .clear('q', SwipeDirection.UP)
            .assign('z', SwipeDirection.DOWN_LEFT, ComputerKey.ESC)!!
        val keys = KeyboardLayouts.rows(
            KeyboardMode.LATIN,
            computerKeys = true,
            computerMap = map,
        ).flatMap { it.keys }
        assertTrue(keys.first { it.label == "q" }.swipes.isEmpty())
        assertEquals(ComputerKey.ESC, keys.first { it.label == "z" }.swipes[SwipeDirection.DOWN_LEFT])
        val shifted = KeyboardLayouts.rows(
            KeyboardMode.LATIN,
            shifted = true,
            computerKeys = true,
            computerMap = map,
        ).flatMap { it.keys }
        assertEquals(ComputerKey.ESC, shifted.first { it.label == "Z" }.swipes[SwipeDirection.DOWN_LEFT])
        val disabled = KeyboardLayouts.rows(
            KeyboardMode.LATIN,
            computerKeys = false,
            computerMap = map,
        ).flatMap { it.keys }
        assertTrue(disabled.all { it.swipes.isEmpty() })
    }

    private fun directionAt(angle: Double): SwipeDirection? {
        return SwipeDirection.of(
            dx = (sin(angle) * 20.0).toFloat(),
            dy = (-cos(angle) * 20.0).toFloat(),
            slop = 1f,
        )
    }
}
