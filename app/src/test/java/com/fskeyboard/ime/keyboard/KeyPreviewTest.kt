package com.fskeyboard.ime.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPreviewTest {
    @Test
    fun directKeyActionsFollowPreviewRules() {
        // Included character keys
        assertTrue(shouldShowKeyPreview(KeySpec("a", KeyAction.Character('a'))))
        assertTrue(shouldShowKeyPreview(KeySpec("ㄅ", KeyAction.Bopomofo("ㄅ", '1'))))
        assertTrue(shouldShowKeyPreview(KeySpec("，", KeyAction.Punctuation("，"))))
        assertTrue(shouldShowKeyPreview(KeySpec("1", KeyAction.Character('1'))))

        // Excluded functional keys
        assertFalse(shouldShowKeyPreview(KeySpec("空格", KeyAction.Space)))
        assertFalse(shouldShowKeyPreview(KeySpec("Enter", KeyAction.Enter)))
        assertFalse(shouldShowKeyPreview(KeySpec("", KeyAction.Backspace)))
        assertFalse(shouldShowKeyPreview(KeySpec("", KeyAction.Shift)))
        assertFalse(shouldShowKeyPreview(KeySpec("123", KeyAction.SetMode(KeyboardMode.SYMBOLS))))
        assertFalse(shouldShowKeyPreview(KeySpec("#+=", KeyAction.SetMode(KeyboardMode.SYMBOLS_MORE))))
        assertFalse(shouldShowKeyPreview(KeySpec("ABC", KeyAction.SetMode(KeyboardMode.LATIN))))
        assertFalse(shouldShowKeyPreview(KeySpec("注音", KeyAction.SetMode(KeyboardMode.ZHUYIN))))
        assertFalse(shouldShowKeyPreview(KeySpec("", KeyAction.NextInputMethod)))
        assertFalse(shouldShowKeyPreview(null))
    }

    @Test
    fun zhuyinLayoutFollowsPreviewRules() {
        val rows = KeyboardLayouts.rows(KeyboardMode.ZHUYIN)
        val allKeys = rows.flatMap { it.keys }

        // All bopomofo keys (letters + tones) must show preview
        val bopomofoKeys = allKeys.filter { it.action is KeyAction.Bopomofo }
        assertTrue(bopomofoKeys.isNotEmpty())
        for (key in bopomofoKeys) {
            assertTrue("Expected preview for bopomofo key ${key.label}", shouldShowKeyPreview(key))
        }

        // Excluded keys
        val space = allKeys.first { it.action == KeyAction.Space }
        assertFalse("Space must not show preview", shouldShowKeyPreview(space))

        val enter = allKeys.first { it.action == KeyAction.Enter }
        assertFalse("Enter must not show preview", shouldShowKeyPreview(enter))

        val delete = allKeys.first { it.action == KeyAction.Backspace }
        assertFalse("Delete must not show preview", shouldShowKeyPreview(delete))

        val symbols = allKeys.first { it.action == KeyAction.SetMode(KeyboardMode.SYMBOLS) }
        assertFalse("Symbol switch must not show preview", shouldShowKeyPreview(symbols))

        val globe = allKeys.first { it.icon == KeyIcon.GLOBE }
        assertFalse("Globe language switch must not show preview", shouldShowKeyPreview(globe))
    }

    @Test
    fun latinLayoutFollowsPreviewRules() {
        val rows = KeyboardLayouts.rows(KeyboardMode.LATIN, latinTopNumberRow = true)
        val allKeys = rows.flatMap { it.keys }

        // Letters and numbers must show preview
        val characters = allKeys.filter { it.action is KeyAction.Character }
        assertTrue(characters.isNotEmpty())
        for (key in characters) {
            assertTrue("Expected preview for key ${key.label}", shouldShowKeyPreview(key))
        }

        // Functional keys must not show preview
        val space = allKeys.first { it.action == KeyAction.Space }
        assertFalse(shouldShowKeyPreview(space))

        val enter = allKeys.first { it.action == KeyAction.Enter }
        assertFalse(shouldShowKeyPreview(enter))

        val delete = allKeys.first { it.action == KeyAction.Backspace }
        assertFalse(shouldShowKeyPreview(delete))

        val shift = allKeys.first { it.action == KeyAction.Shift }
        assertFalse(shouldShowKeyPreview(shift))

        val globe = allKeys.first { it.icon == KeyIcon.GLOBE }
        assertFalse(shouldShowKeyPreview(globe))

        val symbols = allKeys.first { it.icon == KeyIcon.SYMBOLS }
        assertFalse(shouldShowKeyPreview(symbols))
    }

    @Test
    fun symbolsLayoutFollowsPreviewRules() {
        for (more in listOf(false, true)) {
            val mode = if (more) KeyboardMode.SYMBOLS_MORE else KeyboardMode.SYMBOLS
            val rows = KeyboardLayouts.rows(mode)
            val allKeys = rows.flatMap { it.keys }

            val contentKeys = allKeys.filter {
                it.action is KeyAction.Character || it.action is KeyAction.Punctuation
            }
            assertTrue(contentKeys.isNotEmpty())
            for (key in contentKeys) {
                assertTrue("Expected preview for symbol key ${key.label}", shouldShowKeyPreview(key))
            }

            // Excluded keys
            val space = allKeys.first { it.action == KeyAction.Space }
            assertFalse(shouldShowKeyPreview(space))

            val enter = allKeys.first { it.action == KeyAction.Enter }
            assertFalse(shouldShowKeyPreview(enter))

            val delete = allKeys.first { it.action == KeyAction.Backspace }
            assertFalse(shouldShowKeyPreview(delete))

            val modeSwitches = allKeys.filter { it.action is KeyAction.SetMode }
            assertTrue(modeSwitches.isNotEmpty())
            for (key in modeSwitches) {
                assertFalse("Expected no preview for mode switch ${key.label}", shouldShowKeyPreview(key))
            }
        }
    }
}
