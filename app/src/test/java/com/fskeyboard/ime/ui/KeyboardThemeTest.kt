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
    fun predictionChipSitsAtADifferentDepth() {
        for (theme in listOf(KeyboardTheme.paper(), KeyboardTheme.ink())) {
            for (palette in listOf(theme.light, theme.dark)) {
                val fill = palette.predictionFill()
                assertNotEquals(palette.background, fill)
                assertNotEquals(palette.key, fill)
                assertNotEquals(palette.accent, fill)
            }
        }
    }

    @Test
    fun thePreviewReadsTheThemePalette() {
        val view = readProjectFile("src/main/java/com/fskeyboard/ime/keyboard/KeyboardView.kt")
        val preview = view.substringAfter("fun showKeyPreview").substringBefore("private fun dispatch")
        assertTrue(preview.contains("theme.palette(resolvedNight)"))
        assertTrue(preview.contains("themePalette.copy(text = paintResolved.labelColor)"))
        assertFalse(preview.contains("keyPreview.palette = theme.palette"))
    }

    @Test
    fun theComposerDoesNotReadTheTheme() {
        val controller = readProjectFile("src/main/java/com/fskeyboard/ime/candidate/CandidateController.kt")
        val session = readProjectFile("src/main/java/com/fskeyboard/ime/compose/ZhuyinCompositionSession.kt")
        assertFalse(controller.contains("KeyboardTheme"))
        assertFalse(session.contains("KeyboardTheme"))
        for (name in listOf(
            "AppearanceOverrides",
            "ResolvedKeyboard",
            "CandidateChrome",
            "KeyAnimation",
            "AppearanceImageCache",
        )) {
            assertFalse(controller.contains(name))
            assertFalse(session.contains(name))
        }
    }
}
