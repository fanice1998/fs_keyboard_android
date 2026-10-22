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
    fun aComputerKeyCanBelongToOnlyOneKey() {
        val map = ComputerKeyMap.DEFAULT
        assertEquals(ComputerSlot('q', SwipeDirection.DOWN_RIGHT), map.owner(ComputerKey.ESC))
        assertNull(map.assign('z', SwipeDirection.LEFT, ComputerKey.ESC))
        assertNull(map.assign('1', SwipeDirection.UP, ComputerKey.TAB))
        assertNull(map.assign('q', SwipeDirection.UP, ComputerKey.TAB))
        val moved = map.clear('a', SwipeDirection.UP_LEFT)
            .assign('z', SwipeDirection.DOWN_LEFT, ComputerKey.TAB)
        assertEquals(ComputerSlot('z', SwipeDirection.DOWN_LEFT), moved!!.owner(ComputerKey.TAB))
        val sameLetter = map.assign('q', SwipeDirection.UP, ComputerKey.ESC)!!
        assertEquals(ComputerKey.ESC, sameLetter.swipes('q')[SwipeDirection.UP])
        assertEquals(ComputerKey.ESC, sameLetter.swipes('q')[SwipeDirection.DOWN_RIGHT])
        assertEquals(map, map.assign('q', SwipeDirection.DOWN_RIGHT, ComputerKey.ESC))
        assertEquals(map, map.clear('z', SwipeDirection.UP))
        val comma = ComputerKeyMap.EMPTY
            .assign(',', SwipeDirection.UP_RIGHT, ComputerKey.META)!!
            .assign(',', SwipeDirection.DOWN_RIGHT, ComputerKey.META)!!
        assertEquals(ComputerKey.META, comma.swipes(',')[SwipeDirection.UP_RIGHT])
        assertEquals(ComputerKey.META, comma.swipes(',')[SwipeDirection.DOWN_RIGHT])
        assertNull(comma.assign('f', SwipeDirection.UP, ComputerKey.META))
        val delete = ComputerKeyMap.EMPTY.assign(
            ComputerKeyMap.DELETE,
            SwipeDirection.UP_RIGHT,
            ComputerKey.FORWARD_DELETE,
        )!!
        assertEquals("del/up_right=forward_delete", delete.encode())
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
        val messy = ComputerKeyMap.decode("nope,Q/UP=ESC, q/left=esc,z/down=nope,q/right=tab,z/down=esc")
        assertEquals(ComputerKey.ESC, messy.swipes('q')[SwipeDirection.UP])
        assertEquals(ComputerKey.ESC, messy.swipes('q')[SwipeDirection.LEFT])
        assertEquals(ComputerKey.TAB, messy.swipes('q')[SwipeDirection.RIGHT])
        assertTrue(messy.swipes('z').isEmpty())
        assertEquals(ComputerKeyMap.DEFAULT, ComputerKeyMap.decode(ComputerKeyMap.DEFAULT.encode()))
        val punctuation = ComputerKeyMap.decode(
            "./left=left,del/up_right=forward_delete,comma/up_right=meta,comma/down_right=meta",
        )
        assertEquals(ComputerKey.LEFT, punctuation.swipes('.')[SwipeDirection.LEFT])
        assertEquals(
            ComputerKey.FORWARD_DELETE,
            punctuation.swipes(ComputerKeyMap.DELETE)[SwipeDirection.UP_RIGHT],
        )
        assertEquals(ComputerKey.META, punctuation.swipes(',')[SwipeDirection.UP_RIGHT])
        assertEquals(ComputerKey.META, punctuation.swipes(',')[SwipeDirection.DOWN_RIGHT])
    }

    @Test
    fun aHeldModifierSurvivesTheNextKey() {
        val modifiers = ComputerModifiers()
        modifiers.hold(ComputerKey.CTRL, true)
        modifiers.toggle(ComputerKey.FN)
        modifiers.hold(ComputerKey.META, true)
        val meta = modifiers.metaState()
        assertTrue(meta and KeyEvent.META_CTRL_ON != 0)
        assertTrue(meta and KeyEvent.META_FUNCTION_ON != 0)
        assertTrue(meta and KeyEvent.META_META_ON != 0)
        modifiers.clearLatched()
        assertEquals(setOf(ComputerKey.CTRL, ComputerKey.META), modifiers.active())
        assertEquals(0, modifiers.metaState() and KeyEvent.META_FUNCTION_ON)
        modifiers.hold(ComputerKey.CTRL, false)
        modifiers.hold(ComputerKey.META, false)
        assertFalse(modifiers.isActive())
        modifiers.toggle(ComputerKey.ALT)
        modifiers.hold(ComputerKey.ALT, true)
        modifiers.hold(ComputerKey.ALT, false)
        assertFalse(modifiers.isActive())
        modifiers.hold(ComputerKey.ESC, true)
        assertFalse(modifiers.isActive())
    }

    @Test
    fun theDefaultMapMatchesTheEnglishComputerKeys() {
        val on = KeyboardLayouts.rows(KeyboardMode.LATIN, computerKeys = true).flatMap { it.keys }
        fun swipes(label: String) = on.first { it.label == label }.swipes
        assertEquals(mapOf(SwipeDirection.DOWN_RIGHT to ComputerKey.ESC), swipes("q"))
        assertEquals(mapOf(SwipeDirection.UP_LEFT to ComputerKey.TAB), swipes("a"))
        assertEquals(mapOf(SwipeDirection.DOWN_RIGHT to ComputerKey.BANG), swipes("1"))
        assertEquals(mapOf(SwipeDirection.DOWN_RIGHT to ComputerKey.AT), swipes("2"))
        assertEquals(mapOf(SwipeDirection.DOWN_RIGHT to ComputerKey.HASH), swipes("3"))
        assertEquals(mapOf(SwipeDirection.DOWN_RIGHT to ComputerKey.DOLLAR), swipes("4"))
        assertEquals(mapOf(SwipeDirection.DOWN_RIGHT to ComputerKey.PERCENT), swipes("5"))
        assertEquals(mapOf(SwipeDirection.DOWN_LEFT to ComputerKey.CARET), swipes("6"))
        assertEquals(mapOf(SwipeDirection.DOWN_LEFT to ComputerKey.AMPERSAND), swipes("7"))
        assertEquals(mapOf(SwipeDirection.DOWN_LEFT to ComputerKey.STAR), swipes("8"))
        assertEquals(mapOf(SwipeDirection.DOWN_LEFT to ComputerKey.PAREN_LEFT), swipes("9"))
        assertEquals(mapOf(SwipeDirection.DOWN_LEFT to ComputerKey.PAREN_RIGHT), swipes("0"))
        assertEquals(mapOf(SwipeDirection.DOWN_LEFT to ComputerKey.DOUBLE_QUOTE), swipes("w"))
        assertEquals(
            mapOf(
                SwipeDirection.DOWN_LEFT to ComputerKey.UNDERSCORE,
                SwipeDirection.UP_RIGHT to ComputerKey.MINUS,
            ),
            swipes("g"),
        )
        assertEquals(
            mapOf(
                SwipeDirection.DOWN_LEFT to ComputerKey.PLUS,
                SwipeDirection.UP_RIGHT to ComputerKey.EQUALS,
            ),
            swipes("h"),
        )
        assertEquals(
            mapOf(
                SwipeDirection.DOWN_LEFT to ComputerKey.BRACE_LEFT,
                SwipeDirection.DOWN_RIGHT to ComputerKey.BRACE_RIGHT,
            ),
            swipes("j"),
        )
        assertEquals(
            mapOf(
                SwipeDirection.DOWN_LEFT to ComputerKey.BRACKET_LEFT,
                SwipeDirection.DOWN_RIGHT to ComputerKey.BRACKET_RIGHT,
            ),
            swipes("k"),
        )
        assertEquals(
            mapOf(
                SwipeDirection.DOWN_LEFT to ComputerKey.QUOTE,
                SwipeDirection.UP_RIGHT to ComputerKey.TILDE,
            ),
            swipes("l"),
        )
        assertEquals(
            mapOf(
                SwipeDirection.DOWN_LEFT to ComputerKey.BACKSLASH,
                SwipeDirection.DOWN_RIGHT to ComputerKey.PIPE,
            ),
            swipes("z"),
        )
        assertEquals(mapOf(SwipeDirection.UP_RIGHT to ComputerKey.LESS), swipes("c"))
        assertEquals(mapOf(SwipeDirection.UP_RIGHT to ComputerKey.GREATER), swipes("v"))
        assertEquals(
            mapOf(
                SwipeDirection.DOWN_LEFT to ComputerKey.SLASH,
                SwipeDirection.UP_RIGHT to ComputerKey.QUESTION,
            ),
            swipes("b"),
        )
        assertEquals(
            mapOf(
                SwipeDirection.UP_RIGHT to ComputerKey.COLON,
                SwipeDirection.DOWN_RIGHT to ComputerKey.SEMICOLON,
            ),
            swipes("n"),
        )
        assertEquals(
            mapOf(
                SwipeDirection.LEFT to ComputerKey.LEFT,
                SwipeDirection.UP to ComputerKey.UP,
                SwipeDirection.RIGHT to ComputerKey.RIGHT,
                SwipeDirection.DOWN to ComputerKey.DOWN,
            ),
            swipes("."),
        )
        assertEquals(mapOf(SwipeDirection.DOWN_RIGHT to ComputerKey.META), swipes(","))
        assertEquals(mapOf(SwipeDirection.UP_LEFT to ComputerKey.HOME), swipes("u"))
        assertEquals(mapOf(SwipeDirection.DOWN_RIGHT to ComputerKey.END), swipes("d"))
        assertEquals(
            mapOf(SwipeDirection.UP_RIGHT to ComputerKey.FORWARD_DELETE),
            on.first { it.action == KeyAction.Backspace }.swipes,
        )
        val ctrl = on.first { it.label == "Ctrl" }
        assertEquals(KeyAction.Computer(ComputerKey.CTRL), ctrl.action)
        assertEquals(ComputerKey.CTRL, ctrl.holdKey)
        val fn = on.first { it.label == "Fn" }
        assertEquals(KeyAction.Computer(ComputerKey.FN), fn.action)
        assertEquals(ComputerKey.FN, fn.holdKey)
        assertEquals(ComputerKey.ALT, fn.swipes[SwipeDirection.UP_LEFT])
        assertEquals(KeyAction.SetMode(KeyboardMode.ZHUYIN), fn.extraSwipes[SwipeDirection.DOWN_RIGHT])
        assertEquals("注", fn.swipeLabels()[SwipeDirection.DOWN_RIGHT])
        assertEquals("⌘", ComputerKey.META.label)
        assertFalse(on.any { it.action == KeyAction.SetMode(KeyboardMode.SYMBOLS) })
        val enter = on.first { it.action == KeyAction.Enter }
        assertEquals("", enter.label)
        assertEquals(KeyIcon.RETURN, enter.icon)

        val off = KeyboardLayouts.rows(KeyboardMode.LATIN).flatMap { it.keys }
        assertTrue(off.none { it.swipes.isNotEmpty() || it.extraSwipes.isNotEmpty() || it.holdKey != null })
        assertTrue(off.any { it.icon == KeyIcon.SYMBOLS && it.action == KeyAction.SetMode(KeyboardMode.SYMBOLS) })
        val zhuyin = KeyboardLayouts.rows(KeyboardMode.ZHUYIN).flatMap { it.keys }
        assertTrue(zhuyin.none { it.swipes.isNotEmpty() })
        val shifted = KeyboardLayouts.rows(
            KeyboardMode.LATIN,
            shifted = true,
            computerKeys = true,
        ).flatMap { it.keys }
        assertEquals(
            mapOf(SwipeDirection.DOWN_RIGHT to ComputerKey.ESC),
            shifted.first { it.label == "Q" }.swipes,
        )
    }

    @Test
    fun aCustomMapReplacesTheDefaultAndStaysOffUntilComputerKeysAreOn() {
        val map = ComputerKeyMap.DEFAULT
            .clear('q', SwipeDirection.DOWN_RIGHT)
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
