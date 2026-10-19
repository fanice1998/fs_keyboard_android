package com.fskeyboard.ime.keyboard

import com.fskeyboard.ime.candidate.Candidate
import com.fskeyboard.ime.candidate.CompositionCandidates
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
