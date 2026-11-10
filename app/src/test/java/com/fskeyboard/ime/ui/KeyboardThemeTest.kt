package com.fskeyboard.ime.ui

import com.fskeyboard.ime.readProjectFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardThemeTest {
    @Test
    fun paperIsTheDefaultAndInkKeepsABorder() {
        val paper = KeyboardTheme.of(null)
        val ink = KeyboardTheme.of(KeyboardTheme.INK)

        assertEquals(KeyboardTheme.PAPER, paper.id)
        assertEquals(0, paper.borderDp)
        assertEquals(0, paper.pressMs)
        assertEquals("", paper.imagePath)
        assertTrue(ink.borderDp > 0)
        assertTrue(ink.pressMs > 0)
        assertNotEquals(paper.light.key, ink.light.key)
        assertEquals(paper.id, KeyboardTheme.of("missing").id)
    }

    @Test
    fun thePreviewReadsTheThemePalette() {
        val view = readProjectFile("src/main/java/com/fskeyboard/ime/keyboard/KeyboardView.kt")
        assertTrue(view.contains("keyPreview.palette = theme.palette"))
    }

    @Test
    fun theComposerDoesNotReadTheTheme() {
        val controller = readProjectFile("src/main/java/com/fskeyboard/ime/candidate/CandidateController.kt")
        val session = readProjectFile("src/main/java/com/fskeyboard/ime/compose/ZhuyinCompositionSession.kt")
        assertFalse(controller.contains("KeyboardTheme"))
        assertFalse(session.contains("KeyboardTheme"))
    }
}
