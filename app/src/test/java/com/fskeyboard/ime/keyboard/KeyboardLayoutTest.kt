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
        assertEquals("注音", specs.first { it.action == KeyAction.Space }.label)
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
    fun symbolsReturnsToZhuyin() {
        val actions = KeyboardLayouts.rows(KeyboardMode.SYMBOLS)
            .flatMap { it.keys }
            .map { it.action }
        assertTrue(actions.contains(KeyAction.SetMode(KeyboardMode.ZHUYIN)))
        assertTrue(actions.contains(KeyAction.Character('，')))
        assertTrue(actions.contains(KeyAction.Character('。')))
        assertEquals(KeyIcon.RETURN, KeyboardLayouts.rows(KeyboardMode.SYMBOLS).flatMap { it.keys }
            .first { it.action == KeyAction.Enter }.icon)
    }
}
