package com.fskeyboard.ime.keyboard

import com.fskeyboard.ime.candidate.Candidate
import com.fskeyboard.ime.candidate.CompositionCandidates
import com.fskeyboard.ime.candidate.candidatesFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhuyinComposerTest {
    @Test
    fun aSyllableStaysZhuyinAndDoesNotBecomeTheDigitEight() {
        val composer = ZhuyinComposer()
        assertTrue(composer.append("ㄚ"))
        assertEquals("ㄚ", composer.text)
        assertEquals("8", composer.spelling())
        assertEquals("ㄚ", ZhuyinSpelling.toGlyphs("8"))
        assertTrue(ZhuyinSpelling.isKeySpelling("8"))
        assertFalse(ZhuyinSpelling.isKeySpelling("啊"))
    }

    @Test
    fun toneReplacesTheOpenSyllableAndALeadingToneIsIgnored() {
        val composer = ZhuyinComposer()
        assertFalse(composer.append("ˋ"))
        assertTrue(composer.isEmpty)
        composer.append("ㄚ")
        composer.append("ˋ")
        composer.append("ˊ")
        assertEquals("ㄚˊ", composer.text)
        assertEquals(listOf("ㄚˊ"), composer.syllables)
    }

    @Test
    fun syllablesChainWhenTheNextInitialCannotExtendTheCurrentOne() {
        val composer = type("ㄅㄚㄅㄚ")
        assertEquals(listOf("ㄅㄚ", "ㄅㄚ"), composer.syllables)
        assertEquals("ㄅㄚㄅㄚ", composer.text)

        val guo = type("ㄓㄨㄥㄍㄨㄛ")
        assertEquals(listOf("ㄓㄨㄥ", "ㄍㄨㄛ"), guo.syllables)

        val toned = type("ㄅㄚˋㄅㄚ")
        assertEquals(listOf("ㄅㄚˋ", "ㄅㄚ"), toned.syllables)
    }

    @Test
    fun anIllegalInitialMedialPairStartsTheNextSyllable() {
        assertEquals(listOf("ㄍ", "ㄧ"), type("ㄍㄧ").syllables)
        assertEquals(listOf("ㄐ", "ㄨ"), type("ㄐㄨ").syllables)
        assertEquals(listOf("ㄓ", "ㄧ"), type("ㄓㄧ").syllables)
        assertEquals(listOf("ㄅ", "ㄩ"), type("ㄅㄩ").syllables)
        assertEquals(listOf("ㄈ", "ㄧ"), type("ㄈㄧ").syllables)
        assertEquals(listOf("ㄐㄧ"), type("ㄐㄧ").syllables)
        assertEquals(listOf("ㄍㄨ"), type("ㄍㄨ").syllables)
        assertEquals(listOf("ㄓㄨ"), type("ㄓㄨ").syllables)
    }

    @Test
    fun medialThenFinalStaysTogetherAndFinalThenMedialSplits() {
        assertEquals(listOf("ㄧㄚ"), type("ㄧㄚ").syllables)
        assertEquals(listOf("ㄚ", "ㄧ"), type("ㄚㄧ").syllables)
        assertEquals(listOf("ㄐㄧㄤ"), type("ㄐㄧㄤ").syllables)
        assertEquals(listOf("ㄩㄝ"), type("ㄩㄝ").syllables)
        assertEquals(listOf("ㄨㄛˇ"), type("ㄨㄛˇ").syllables)
    }

    @Test
    fun backspaceRemovesOneGlyphAndDropsAnEmptySyllable() {
        val composer = type("ㄅㄚㄅ")
        composer.backspace()
        assertEquals("ㄅㄚ", composer.text)
        composer.backspace()
        composer.backspace()
        assertTrue(composer.isEmpty)
    }

    @Test
    fun adoptReadsEitherGlyphsOrKeyCodes() {
        val fromKeys = ZhuyinComposer()
        fromKeys.adopt("18")
        assertEquals(listOf("ㄅㄚ"), fromKeys.syllables)

        val fromGlyphs = ZhuyinComposer()
        fromGlyphs.adopt("ㄓㄨㄥ")
        assertEquals("ㄓㄨㄥ", fromGlyphs.text)
        assertEquals("5j/", fromGlyphs.spelling())
    }

    @Test
    fun levelToneKeepsTheGlyphsAndBecomesTheQuery() {
        val composer = type("ㄍㄨㄥ")
        assertEquals(listOf("ㄍㄨㄥ"), composer.queries())
        assertFalse(composer.exact)
        assertTrue(composer.markLevelTone())
        assertEquals("ㄍㄨㄥ", composer.text)
        assertTrue(composer.exact)
        assertEquals(listOf("ㄍㄨㄥ"), composer.queries())
    }

    @Test
    fun levelToneReplacesAToneMark() {
        val composer = type("ㄍㄨㄥˊ")
        assertTrue(composer.markLevelTone())
        assertEquals("ㄍㄨㄥ", composer.text)
        assertEquals(listOf("ㄍㄨㄥ"), composer.queries())
    }

    @Test
    fun toneKeyReplacesLevelTone() {
        val composer = type("ㄍㄨㄥ")
        composer.markLevelTone()
        assertTrue(composer.append("ˊ"))
        assertEquals("ㄍㄨㄥˊ", composer.text)
        assertEquals(listOf("ㄍㄨㄥˊ"), composer.queries())
    }

    @Test
    fun backspaceClearsLevelToneBeforeAGlyph() {
        val composer = type("ㄍㄨㄥ")
        composer.markLevelTone()
        composer.backspace()
        assertEquals("ㄍㄨㄥ", composer.text)
        assertFalse(composer.exact)
        assertEquals(listOf("ㄍㄨㄥ"), composer.queries())
        composer.backspace()
        assertEquals("ㄍㄨ", composer.text)
    }

    @Test
    fun backspaceClearsAToneMarkBeforeTheGlyph() {
        val composer = type("ㄅㄚˋ")
        composer.backspace()
        assertEquals("ㄅㄚ", composer.text)
        assertFalse(composer.exact)
        assertEquals(listOf("ㄅㄚ"), composer.queries())
        composer.backspace()
        assertEquals("ㄅ", composer.text)
    }

    @Test
    fun aClosedToneSurvivesWhileTheNextSyllableIsUnfinished() {
        val composer = type("ㄊㄞˊㄨㄢ")
        assertFalse(composer.exact)
        assertEquals(listOf("ㄊㄞˊ", "ㄨㄢ"), composer.partialExactQueries())
        assertEquals(listOf("ㄊㄞˊ", "ㄨㄢ"), composer.queries())
        assertEquals("ㄊㄞˊㄨㄢ", composer.text)
    }

    @Test
    fun secondSyllablePredictsUntilItHasATone() {
        val composer = type("ㄅㄚ")
        composer.markLevelTone()
        assertTrue(composer.append("ㄅ"))
        assertTrue(composer.append("ㄚ"))
        assertFalse(composer.exact)
        assertEquals(listOf("ㄅㄚ", "ㄅㄚ"), composer.queries())
        assertTrue(composer.append("ˋ"))
        assertTrue(composer.exact)
        assertEquals(listOf("ㄅㄚ", "ㄅㄚˋ"), composer.queries())
    }

    @Test
    fun emptyComposerIgnoresLevelTone() {
        assertFalse(ZhuyinComposer().markLevelTone())
    }

    @Test
    fun untonedCompositionShowsPredictionsBeforeATone() {
        val composer = type("ㄍㄨㄥ")
        assertFalse(composer.exact)
        val hits = listOf(
            Candidate("公", engineIndex = -1),
            Candidate("貢", engineIndex = -2),
        )
        assertEquals(listOf("公", "貢", "ㄍㄨㄥ"), candidatesFor(composer, hits).map { it.text })
    }

    @Test
    fun levelToneUsesTheExactHitsAndNotAnotherReading() {
        val composer = type("ㄍㄨㄥ")
        composer.markLevelTone()
        val hits = listOf(
            Candidate("公", engineIndex = -1),
            Candidate("工", engineIndex = -2),
        )
        assertEquals(listOf("公", "工", "ㄍㄨㄥ"), candidatesFor(composer, hits).map { it.text })
    }

    @Test
    fun bareAOffersAhBeforeTheRawZhuyin() {
        val result = CompositionCandidates.supplement("ㄚ", listOf("ㄚ"), emptyList())
        assertEquals("啊", result.first().text)
        assertEquals("ㄚ", result.last().text)
        assertTrue(result.all { it.engineIndex < 0 })
    }

    @Test
    fun engineCandidateStaysAheadOfTheRawZhuyin() {
        val engine = Candidate("啊", engineIndex = 0)
        val result = CompositionCandidates.supplement("ㄚ", listOf("ㄚ"), listOf(engine))
        assertEquals("啊", result.first().text)
        assertEquals(0, result.first().engineIndex)
        assertEquals(listOf("啊", "阿", "ㄚ"), result.map { it.text })
    }

    private fun type(glyphs: String): ZhuyinComposer {
        val composer = ZhuyinComposer()
        for (ch in glyphs) {
            assertTrue(ch.toString(), composer.append(ch.toString()))
        }
        return composer
    }
}
