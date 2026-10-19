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
        assertTrue(specs.any { it.label == "注" && it.action == KeyAction.SetMode(KeyboardMode.ZHUYIN) })
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
        assertTrue(actions.contains(KeyAction.Punctuation("，")))
        assertTrue(actions.contains(KeyAction.Punctuation("。")))
        assertFalse(actions.contains(KeyAction.Character(',')))
        assertFalse(actions.contains(KeyAction.Character('.')))
        assertTrue(actions.contains(KeyAction.SetMode(KeyboardMode.LATIN)))
        assertTrue(actions.contains(KeyAction.SetMode(KeyboardMode.SYMBOLS)))
        assertTrue(actions.contains(KeyAction.Space))
        assertTrue(actions.contains(KeyAction.Backspace))
        assertTrue(actions.contains(KeyAction.Enter))
    }

    @Test
    fun symbolsReturnsToZhuyin() {
        val actions = KeyboardLayouts.rows(KeyboardMode.SYMBOLS)
            .flatMap { it.keys }
            .map { it.action }
        assertTrue(actions.contains(KeyAction.SetMode(KeyboardMode.ZHUYIN)))
    }
}
