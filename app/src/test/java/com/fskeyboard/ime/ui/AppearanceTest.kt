package com.fskeyboard.ime.ui

import com.fskeyboard.ime.readProjectFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {
    @Test
    fun absentAlphaStaysAbsentAndStoredZeroStaysZero() {
        assertNull(readStoredAlpha(false, 0))
        assertNull(readStoredAlpha(false, 100))
        assertEquals(0, readStoredAlpha(true, 0))
        assertEquals(0, readStoredAlpha(true, -1))
        assertEquals(100, readStoredAlpha(true, 150))
        assertEquals(40, readStoredAlpha(true, 40))
    }

    @Test
    fun sampleSizeHasNoCeilingOf256() {
        assertEquals(1, appearanceSampleSize(1280, 1280))
        assertEquals(2, appearanceSampleSize(2561, 100))
        assertEquals(4, appearanceSampleSize(3000, 2000))
        val wide = 256 * 1280 * 2
        val sample = appearanceSampleSize(wide, 10)
        assertTrue(sample > 256)
        assertTrue(wide / sample <= 1280)
        assertFalse(sampledBufferTooLarge(2561, 1280, 2))
        assertTrue(sampledBufferTooLarge(2000, 2000, 1))
        val capped = appearanceSampleSize(Int.MAX_VALUE, Int.MAX_VALUE)
        assertTrue(Int.MAX_VALUE / capped <= 1280)
        assertFalse(sampledBufferTooLarge(Int.MAX_VALUE, Int.MAX_VALUE, capped))
    }

    @Test
    fun imageNameAcceptsOnlyTheOfficialFile() {
        assertEquals(APPEARANCE_IMAGE_FILE, acceptedImageName("background.webp"))
        assertNull(acceptedImageName(null))
        assertNull(acceptedImageName(""))
        assertNull(acceptedImageName("../background.webp"))
        assertNull(acceptedImageName("a/background.webp"))
        assertNull(acceptedImageName("a\\background.webp"))
        assertNull(acceptedImageName("other.webp"))
    }

    @Test
    fun functionKeyContrastUsesTheFaceAboutToBeDrawn() {
        val paper = KeyboardTheme.paper().light
        val label = 0xFFD0D4DA.toInt()
        assertFalse(colorsMatch(label, paper.key))
        assertTrue(colorsMatch(label, paper.keyAction))
        assertEquals(0xFF1C1C1E.toInt(), haloColor(paper.key))
        assertEquals(0xFFF2F2F7.toInt(), haloColor(0xFF1C1C1E.toInt()))
    }

    @Test
    fun unknownAnimationIsNone() {
        assertEquals(KeyAnimation.NONE, KeyAnimation.of(null))
        assertEquals(KeyAnimation.NONE, KeyAnimation.of(""))
        assertEquals(KeyAnimation.NONE, KeyAnimation.of("spin"))
        assertEquals(KeyAnimation.POP, KeyAnimation.of("pop"))
        assertEquals(0, KeyAnimation.NONE.durationMs)
        assertEquals(120, KeyAnimation.POP.durationMs)
        assertEquals(160, KeyAnimation.RIPPLE.durationMs)
        assertEquals(80, KeyAnimation.FLASH.durationMs)
        assertEquals(100, KeyAnimation.SINK.durationMs)
    }

    @Test
    fun pulsePeaksAtTheMiddle() {
        assertEquals(0f, pulse(0f), 0.001f)
        assertEquals(1f, pulse(0.5f), 0.001f)
        assertEquals(0f, pulse(1f), 0.001f)
    }

    @Test
    fun absentOverridesKeepTheThemeAndThePaperCandidateRow() {
        val paper = resolveKeyboard(KeyboardTheme.paper(), night = false, AppearanceOverrides.NONE, null)
        assertEquals(KeyboardTheme.paper().light, paper.palette)
        assertEquals(100, paper.alphaPercent)
        assertEquals(KeyAnimation.NONE, paper.animation)
        assertFalse(paper.drawBorder)
        assertEquals(0, paper.borderDp)
        assertNull(paper.imageFile)
        assertFalse(paper.labelOverridden)

        val ink = resolveKeyboard(KeyboardTheme.ink(), night = false, AppearanceOverrides.NONE, null)
        assertTrue(ink.drawBorder)
        assertEquals(KeyboardTheme.ink().borderDp, ink.borderDp)

        val borderOnPaper = resolveKeyboard(
            KeyboardTheme.paper(),
            night = false,
            AppearanceOverrides.NONE.copy(keyBorderEnabled = true),
            null,
        )
        assertTrue(borderOnPaper.drawBorder)
        assertEquals(1, borderOnPaper.borderDp)

        val chrome = CandidateChrome.resolve(false, AppearanceOverrides.NONE)
        val base = KeyboardPalette.of(false)
        assertEquals(base.background, chrome.bar)
        assertEquals(base.accent, chrome.selected)
        assertEquals(base.key, chrome.unselected)
        assertEquals(base.text, chrome.text)
        assertEquals(base.keyPressed, chrome.pressed)
        assertEquals(base.predictionFill(), chrome.prediction)
    }

    @Test
    fun pressedFillFollowsTheThreeKeysOnly() {
        val base = KeyboardPalette.of(false)
        val fill = 0xFF112233.toInt()
        val selected = 0xFFABCDE0.toInt()
        val unselected = 0xFF010203.toInt()
        assertEquals(base.keyPressed, pressed(AppearanceOverrides.NONE))
        assertEquals(pressedFill(base.key), pressed(AppearanceOverrides.NONE.copy(keyFill = fill)))
        assertEquals(pressedFill(base.key), pressed(AppearanceOverrides.NONE.copy(candidateSelected = selected)))
        assertEquals(pressedFill(unselected or 0xFF000000.toInt()), pressed(AppearanceOverrides.NONE.copy(candidateUnselected = unselected)))
        assertEquals(base.keyPressed, pressed(AppearanceOverrides.NONE.copy(candidateBar = fill)))
        assertEquals(base.keyPressed, pressed(AppearanceOverrides.NONE.copy(candidateText = fill)))
    }

    @Test
    fun predictionFollowsTheBarAndTheUnselectedFill() {
        val base = KeyboardPalette.of(false)
        assertEquals(base.predictionFill(), prediction(AppearanceOverrides.NONE))
        val keyOnly = AppearanceOverrides.NONE.copy(keyFill = 0xFF010203.toInt())
        assertEquals(base.predictionFill(), prediction(keyOnly))
        val bar = 0xFF112233.toInt()
        assertEquals(predictionOf(bar, base.key, base), prediction(AppearanceOverrides.NONE.copy(candidateBar = bar)))
        val unselected = 0xFF445566.toInt()
        assertEquals(
            predictionOf(base.background, unselected, base),
            prediction(AppearanceOverrides.NONE.copy(candidateUnselected = unselected)),
        )
        val same = 0xFF778899.toInt()
        assertEquals(same, prediction(AppearanceOverrides.NONE.copy(candidateBar = same, candidateUnselected = same)))
    }

    @Test
    fun editorStaysOnPaperUntilAKeyFaceChanges() {
        assertNull(editorResolved(AppearanceOverrides.NONE, night = false))
        assertNull(
            editorResolved(
                AppearanceOverrides.NONE.copy(
                    backgroundColor = 0xFF010203.toInt(),
                    alphaPercent = 40,
                    imageName = APPEARANCE_IMAGE_FILE,
                    candidateBar = 0xFF445566.toInt(),
                ),
                night = true,
            ),
        )
        val edited = editorResolved(AppearanceOverrides.NONE.copy(keyFill = 0xFF010203.toInt()), night = false)
        assertNotNull(edited)
        assertEquals(0xFF010203.toInt(), edited!!.palette.key)
        assertEquals(KeyboardTheme.paper().light.background, edited.palette.background)
        assertEquals(100, edited.alphaPercent)
        assertNull(edited.imageFile)
        val labeled = editorResolved(AppearanceOverrides.NONE.copy(keyLabelColor = 0xFFABCDEF.toInt()), night = true)
        assertEquals(KeyboardTheme.paper().dark.background, labeled!!.palette.background)
        assertEquals(0xFFABCDEF.toInt(), labeled.labelColor)
        assertEquals(
            KeyAnimation.POP,
            editorResolved(AppearanceOverrides.NONE.copy(animation = KeyAnimation.POP), night = false)!!.animation,
        )
    }

    @Test
    fun swatchesAreTheThemeChannelsPlusEightBasics() {
        val swatches = appearanceSwatches()
        assertEquals(36, swatches.size)
        assertEquals(swatches.size, swatches.toSet().size)
        for (color in intArrayOf(
            0xFFB42318.toInt(),
            0xFFB45309.toInt(),
            0xFF0F766E.toInt(),
            0xFF1D4ED8.toInt(),
            0xFF6D28D9.toInt(),
            0xFF111827.toInt(),
            0xFFF9FAFB.toInt(),
            0xFFF59E0B.toInt(),
        )) {
            assertTrue(swatches.contains(color))
        }
    }

    @Test
    fun recoveryLeavesAnActiveGenerationAlone() {
        assertEquals(RecoveryAction.IGNORE, recoveryAction(inActive = true, abandoned = true, ok = true))
        assertEquals(RecoveryAction.IGNORE, recoveryAction(inActive = true, abandoned = false, ok = false))
        assertEquals(RecoveryAction.DELETE, recoveryAction(inActive = false, abandoned = true, ok = true))
        assertEquals(RecoveryAction.DELETE, recoveryAction(inActive = false, abandoned = false, ok = false))
        assertEquals(RecoveryAction.REENCODE, recoveryAction(inActive = false, abandoned = false, ok = true))
    }

    @Test
    fun hexRequiresSixDigits() {
        assertEquals(0xFFAABBCC.toInt(), parseHexColor("#AABBCC"))
        assertEquals(0xFFAABBCC.toInt(), parseHexColor("aabbcc"))
        assertNull(parseHexColor("abc"))
        assertNull(parseHexColor("gg0000"))
        assertEquals(0xFF000000.toInt(), opaque(0))
    }

    @Test
    fun drawingAndTypingDoNotDecodeOrReadPreferences() {
        val view = readProjectFile("src/main/java/com/fskeyboard/ime/keyboard/KeyboardView.kt")
        val session = readProjectFile("src/main/java/com/fskeyboard/ime/compose/ZhuyinCompositionSession.kt")
        val chips = readProjectFile("src/main/java/com/fskeyboard/ime/candidate/CandidateChips.kt")
        val strip = readProjectFile("src/main/java/com/fskeyboard/ime/candidate/CandidateStrip.kt")
        val panel = readProjectFile("src/main/java/com/fskeyboard/ime/candidate/CandidatePanelView.kt")
        val service = readProjectFile("src/main/java/com/fskeyboard/ime/FsKeyboardService.kt")
        assertFalse(view.contains("BitmapFactory"))
        assertFalse(view.contains("getSharedPreferences"))
        assertFalse(session.contains("BitmapFactory"))
        assertFalse(session.contains("getSharedPreferences"))
        val onDraw = view.substringAfter("override fun onDraw").substringBefore("override fun onTouchEvent")
        assertFalse(onDraw.contains("isNightMode"))
        val fire = view.substringAfter("fun fire(").substringBefore("private fun themeLinger")
        assertTrue(fire.contains("startAnim"))
        assertFalse(fire.contains("BitmapFactory"))
        val dispatch = view.substringAfter("fun dispatch(").substringBefore("private fun rebuild")
        assertFalse(dispatch.contains("startAnim"))
        val bind = chips.substringAfter("fun bind(").substringBefore("fun labelOf")
        assertTrue(bind.contains("candidateFaceFill"))
        assertFalse(bind.contains("when ("))
        assertFalse(chips.contains("setShadowLayer"))
        assertFalse(strip.contains("predictionFill("))
        assertTrue(strip.contains("candidateFaceFill"))
        assertTrue(panel.contains("if (listChanged) scrollTo(0, 0)"))
        assertTrue(panel.contains("shownChrome == chrome"))
        val config = service.substringAfter("override fun onConfigurationChanged")
            .substringBefore("override fun onEvaluateFullscreenMode")
        assertTrue(config.contains("decodeImage = false"))
        assertFalse(config.contains("loadAppearance"))
        assertFalse(config.contains("BitmapFactory"))
        assertFalse(config.contains("getSharedPreferences"))
        assertFalse(config.contains("ensureLoaded"))
        assertFalse(config.contains("lastModified"))
    }

    private fun pressed(overrides: AppearanceOverrides): Int {
        return CandidateChrome.resolve(false, overrides).pressed
    }

    private fun prediction(overrides: AppearanceOverrides): Int {
        return CandidateChrome.resolve(false, overrides).prediction
    }
}
