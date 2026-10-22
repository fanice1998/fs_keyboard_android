package com.fskeyboard.ime.keyboard

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberPadTest {
    @Test
    fun numberPhoneAndDateFieldsAskForTheDigitPad() {
        assertEquals(NumberPad.NONE, NumberPad.from(InputType.TYPE_CLASS_NUMBER))
        assertEquals(NumberPad.NONE, NumberPad.from(InputType.TYPE_CLASS_PHONE))
        assertEquals(NumberPad.NONE, NumberPad.from(InputType.TYPE_CLASS_DATETIME))
        assertEquals(
            NumberPad.NONE,
            NumberPad.from(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
        )
        assertNull(NumberPad.from(InputType.TYPE_CLASS_TEXT))
        assertNull(NumberPad.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertNull(NumberPad.from(InputType.TYPE_NULL))
    }

    @Test
    fun aNumberFieldKeepsOnlyTheMarksItAskedFor() {
        assertEquals(
            NumberPad(decimal = true),
            NumberPad.from(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL),
        )
        assertEquals(
            NumberPad(signed = true),
            NumberPad.from(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED),
        )
        assertEquals(
            NumberPad(signed = true, decimal = true),
            NumberPad.from(
                InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_SIGNED or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL,
            ),
        )
        assertEquals(NumberPad.NONE, NumberPad.from(InputType.TYPE_CLASS_PHONE or InputType.TYPE_NUMBER_FLAG_DECIMAL))
    }

    @Test
    fun theDigitPadHasNoWayToSwitchKeyboards() {
        val rows = KeyboardLayouts.rows(KeyboardMode.NUMBER)
        val keys = rows.flatMap { it.keys }
        assertEquals(listOf("123", "456", "789", "0", ""), rows.map { row -> row.keys.joinToString("") { it.label } })
        assertEquals(
            "1234567890".map { KeyAction.Character(it) },
            keys.map { it.action }.filterIsInstance<KeyAction.Character>(),
        )
        assertEquals(1, keys.count { it.action == KeyAction.Backspace })
        assertEquals(1, keys.count { it.action == KeyAction.Enter })
        assertEquals(KeyIcon.DELETE, keys.first { it.action == KeyAction.Backspace }.icon)
        assertEquals(KeyIcon.RETURN, keys.first { it.action == KeyAction.Enter }.icon)
        assertTrue(keys.none { it.action is KeyAction.SetMode })
        assertTrue(keys.none { it.icon == KeyIcon.GLOBE || it.icon == KeyIcon.SYMBOLS })
        assertTrue(keys.none { it.action == KeyAction.Space || it.action is KeyAction.Bopomofo })
        assertEquals(5, rows.size)
        for (row in rows) {
            val width = row.leading + row.trailing + row.keys.sumOf { it.weight.toDouble() }.toFloat()
            assertEquals(4f, width, 0.001f)
        }
        assertEquals(1f, rows[3].leading, 0f)
        assertEquals(2f, rows[3].trailing, 0f)
    }

    @Test
    fun minusAndTheDecimalPointFillTheRightColumn() {
        val both = KeyboardLayouts.rows(
            KeyboardMode.NUMBER,
            numberPad = NumberPad(signed = true, decimal = true),
        )
        assertEquals("-", both[1].keys.last().label)
        assertEquals(".", both[2].keys.last().label)
        assertEquals(0f, both[1].trailing, 0f)
        assertEquals(0f, both[2].trailing, 0f)
        val plain = KeyboardLayouts.rows(KeyboardMode.NUMBER)
        assertFalse(plain.flatMap { it.keys }.any { it.label == "-" || it.label == "." })
        assertEquals(1f, plain[1].trailing, 0f)
        assertEquals(1f, plain[2].trailing, 0f)
    }
}
