package com.fskeyboard.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutTest {
    @Test
    fun latinHasTheAlphabetAndZhuyinDoesNotSendLetters() {
        val alphabet = ('a'..'z').toSet()
        assertEquals(alphabet, KeyboardLayouts.letterKeys(KeyboardMode.LATIN))
        assertTrue(KeyboardLayouts.letterKeys(KeyboardMode.ZHUYIN).isEmpty())
    }

    @Test
    fun latinShiftUppercasesLettersAndReturnsToZhuyin() {
        val specs = KeyboardLayouts.rows(KeyboardMode.LATIN, shifted = true)
            .flatMap { it.keys }
        assertTrue(specs.map { it.label }.contains("Q"))
        assertTrue(specs.map { it.label }.contains("⇧"))
        assertTrue(specs.any { it.icon == KeyIcon.GLOBE && it.action == KeyAction.SetMode(KeyboardMode.ZHUYIN) })
    }

    @Test
    fun zhuyinUsesTheStandardPositions() {
        val rows = KeyboardLayouts.rows(KeyboardMode.ZHUYIN)
        val letterRows = rows.take(4).map { row ->
            row.keys.filter { it.action is KeyAction.Bopomofo }
        }
        assertEquals(
            ZhuyinLayout.rows.map { row -> row.map { it.label to it.key } },
            letterRows.map { row ->
                row.map { spec ->
                    val action = spec.action as KeyAction.Bopomofo
                    action.label to action.key
                }
            },
        )
        val toneLabels = rows.flatMap { it.keys }
            .filter { it.role == KeyRole.TONE }
            .map { it.label }
            .toSet()
        assertEquals(setOf("ˇ", "ˋ", "ˊ", "˙"), toneLabels)
        val actions = rows.flatMap { it.keys }.map { it.action }
        assertTrue(actions.contains(KeyAction.Bopomofo("ㄝ", ',')))
        assertTrue(actions.contains(KeyAction.Bopomofo("ㄡ", '.')))
        assertTrue(actions.contains(KeyAction.Bopomofo("ㄥ", '/')))
        assertFalse(actions.contains(KeyAction.Punctuation("，")))
        assertFalse(actions.contains(KeyAction.Punctuation("。")))
        assertFalse(actions.contains(KeyAction.Character(',')))
        assertFalse(actions.contains(KeyAction.Character('.')))
        assertTrue(actions.contains(KeyAction.SetMode(KeyboardMode.LATIN)))
        assertTrue(actions.contains(KeyAction.SetMode(KeyboardMode.SYMBOLS)))
        assertTrue(actions.contains(KeyAction.Space))
        assertTrue(actions.contains(KeyAction.Backspace))
        assertTrue(actions.contains(KeyAction.Enter))
        val specs = rows.flatMap { it.keys }
        assertEquals("平聲", specs.first { it.action == KeyAction.Space }.label)
        assertEquals(KeyIcon.DELETE, specs.first { it.action == KeyAction.Backspace }.icon)
        assertEquals(KeyIcon.RETURN, specs.first { it.action == KeyAction.Enter }.icon)
        assertEquals(KeyIcon.SYMBOLS, specs.first { it.action == KeyAction.SetMode(KeyboardMode.SYMBOLS) }.icon)
        val globe = specs.first { it.icon == KeyIcon.GLOBE }
        assertEquals(KeyAction.SetMode(KeyboardMode.LATIN), globe.action)
        assertEquals(KeyAction.NextInputMethod, globe.secondary)
        assertEquals(1f / 3f, rows[1].leading, 0.0001f)
        assertEquals(2f / 3f, rows[1].trailing, 0.0001f)
        assertEquals(2f / 3f, rows[2].leading, 0.0001f)
        assertEquals(1f / 3f, rows[2].trailing, 0.0001f)
        assertEquals(0f, rows[0].leading, 0f)
        assertEquals(0f, rows[3].leading, 0f)
        assertEquals(0f, rows[3].trailing, 0f)
        assertTrue(rows[3].keys.last().action == KeyAction.Backspace)
    }

    @Test
    fun symbolsFromZhuyinUseChinesePunctuationAndReturnToZhuyin() {
        val specs = KeyboardLayouts.rows(KeyboardMode.SYMBOLS, base = KeyboardMode.ZHUYIN)
            .flatMap { it.keys }
        val actions = specs.map { it.action }
        assertTrue(actions.contains(KeyAction.Character('，')))
        assertTrue(actions.contains(KeyAction.Character('。')))
        assertTrue(actions.contains(KeyAction.Character('「')))
        assertTrue(actions.contains(KeyAction.SetMode(KeyboardMode.SYMBOLS_MORE)))
        assertEquals("ㄅㄆㄇ", specs.first { it.action == KeyAction.SetMode(KeyboardMode.ZHUYIN) }.label)
        assertEquals(KeyIcon.RETURN, specs.first { it.action == KeyAction.Enter }.icon)
    }

    @Test
    fun symbolsFromLatinUseAsciiAndReturnToLatin() {
        for (mode in listOf(KeyboardMode.SYMBOLS, KeyboardMode.SYMBOLS_MORE)) {
            val specs = KeyboardLayouts.rows(mode, base = KeyboardMode.LATIN).flatMap { it.keys }
            val back = specs.first { it.role == KeyRole.MODIFIER && it.label == "ABC" }
            assertEquals(KeyAction.SetMode(KeyboardMode.LATIN), back.action)
            assertFalse(specs.any { it.action == KeyAction.Character('，') })
        }
        val numbers = KeyboardLayouts.rows(KeyboardMode.SYMBOLS, base = KeyboardMode.LATIN)
        assertEquals("1234567890", numbers[0].keys.joinToString("") { it.label })
        assertEquals("-/:;()$&@\"", numbers[1].keys.joinToString("") { it.label })
    }

    @Test
    fun theTwoSymbolPagesSwitchToEachOther() {
        for (base in listOf(KeyboardMode.ZHUYIN, KeyboardMode.LATIN)) {
            val numbers = KeyboardLayouts.rows(KeyboardMode.SYMBOLS, base = base).flatMap { it.keys }
            val more = KeyboardLayouts.rows(KeyboardMode.SYMBOLS_MORE, base = base).flatMap { it.keys }
            assertEquals("#+=", numbers.first { it.action == KeyAction.SetMode(KeyboardMode.SYMBOLS_MORE) }.label)
            assertEquals("123", more.first { it.action == KeyAction.SetMode(KeyboardMode.SYMBOLS) }.label)
        }
    }

    @Test
    fun everySymbolRowIsAsWideAsTenKeys() {
        for (base in listOf(KeyboardMode.ZHUYIN, KeyboardMode.LATIN)) {
            for (mode in listOf(KeyboardMode.SYMBOLS, KeyboardMode.SYMBOLS_MORE)) {
                val rows = KeyboardLayouts.rows(mode, base = base)
                for (row in rows.take(3)) {
                    assertEquals("$base $mode", 10f, row.keys.sumOf { it.weight.toDouble() }.toFloat(), 0.001f)
                }
            }
        }
    }
}
