package com.fskeyboard.ime.keyboard

import com.fskeyboard.ime.candidate.Candidate
import com.fskeyboard.ime.candidate.CompositionCandidates
import com.fskeyboard.ime.candidate.candidatesFor
import com.fskeyboard.ime.candidate.mergeTouchCorrections
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
        assertEquals(listOf("ㄍㄨㄥ*"), composer.queries())
        assertTrue(composer.markLevelTone())
        assertEquals("ㄍㄨㄥ", composer.text)
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
        assertEquals(listOf("ㄍㄨㄥ*"), composer.queries())
        composer.backspace()
        assertEquals("ㄍㄨ", composer.text)
    }

    @Test
    fun backspaceClearsAToneMarkBeforeTheGlyph() {
        val composer = type("ㄅㄚˋ")
        composer.backspace()
        assertEquals("ㄅㄚ", composer.text)
        assertEquals(listOf("ㄅㄚ*"), composer.queries())
        composer.backspace()
        assertEquals("ㄅ", composer.text)
    }

    @Test
    fun aClosedToneSurvivesWhileTheNextSyllableIsUnfinished() {
        val composer = type("ㄊㄞˊㄨㄢ")
        assertEquals(listOf("ㄊㄞˊ", "ㄨㄢ*"), composer.queries())
        assertEquals("ㄊㄞˊㄨㄢ", composer.text)
    }

    @Test
    fun secondSyllablePredictsUntilItHasATone() {
        val composer = type("ㄅㄚ")
        composer.markLevelTone()
        assertTrue(composer.append("ㄅ"))
        assertTrue(composer.append("ㄚ"))
        assertEquals(listOf("ㄅㄚ", "ㄅㄚ*"), composer.queries())
        assertTrue(composer.append("ˋ"))
        assertEquals(listOf("ㄅㄚ", "ㄅㄚˋ"), composer.queries())
    }

    @Test
    fun emptyComposerIgnoresLevelTone() {
        assertFalse(ZhuyinComposer().markLevelTone())
    }

    @Test
    fun untonedCompositionShowsPredictionsBeforeATone() {
        val composer = type("ㄍㄨㄥ")
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

    @Test
    fun aGlyphInsertedAtTheCaretReplaysTheSyllables() {
        val composer = type("ㄅㄚ")
        assertEquals(2, composer.caret)
        composer.moveCaret(1)
        assertTrue(composer.append("ㄆ"))
        assertEquals("ㄅㄆㄚ", composer.text)
        assertEquals(listOf("ㄅ", "ㄆㄚ"), composer.syllables)
        assertEquals(2, composer.caret)
    }

    @Test
    fun backspaceDeletesTheGlyphBeforeTheCaret() {
        val composer = type("ㄅㄚㄅㄚ")
        composer.moveCaret(2)
        composer.backspace()
        assertEquals("ㄅㄅㄚ", composer.text)
        assertEquals(1, composer.caret)
        composer.moveCaret(0)
        composer.backspace()
        assertEquals("ㄅㄅㄚ", composer.text)
    }

    @Test
    fun aToneInsertedBeforeTheMarkReplacesIt() {
        val composer = type("ㄅㄚˋ")
        composer.moveCaret(2)
        assertTrue(composer.append("ˊ"))
        assertEquals("ㄅㄚˊ", composer.text)
        assertEquals(3, composer.caret)
    }

    @Test
    fun levelToneMarksTheSyllableAtTheCaret() {
        val composer = type("ㄅㄚㄍㄨ")
        composer.moveCaret(2)
        assertTrue(composer.markLevelTone())
        assertEquals(listOf("ㄅㄚ", "ㄍㄨ*"), composer.queries())
        composer.moveCaret(0)
        assertFalse(composer.markLevelTone())
        assertEquals(listOf("ㄅㄚ", "ㄍㄨ*"), composer.queries())
    }

    @Test
    fun insertingBeforeTheCaretKeepsTheNeighbourOnThatGlyph() {
        val composer = ZhuyinComposer()
        composer.append("ㄍ", listOf(NearbyKey("ㄍ", 0.55f), NearbyKey("ㄎ", 0.30f)))
        composer.append("ㄨ")
        composer.append("ㄥ")
        composer.moveCaret(1)
        assertTrue(composer.append("ㄚ"))
        assertEquals("ㄍㄚㄨㄥ", composer.text)
        assertEquals(2, composer.caret)
        assertEquals(listOf(listOf("ㄎㄚ*", "ㄨㄥ*")), composer.corrections().map { it.queries })
    }

    @Test
    fun takeAndRestoreKeepACaretInTheRemainder() {
        val composer = type("ㄐㄧㄣㄊㄧㄢˋ")
        assertEquals(7, composer.caret)
        val front = composer.take(1)
        assertEquals(4, composer.caret)
        composer.restore(front)
        assertEquals(7, composer.caret)
        assertEquals(listOf("ㄐㄧㄣ*", "ㄊㄧㄢˋ"), composer.queries())
    }

    @Test
    fun takeRemovesTheFrontAndRestorePutsItBack() {
        val composer = type("ㄐㄧㄣㄊㄧㄢˋ")
        val front = composer.take(1)
        assertEquals(listOf(ZhuyinSyllable("ㄐㄧㄣ", ZhuyinTone.NONE)), front)
        assertEquals("ㄊㄧㄢˋ", composer.text)
        composer.restore(front)
        assertEquals(listOf("ㄐㄧㄣ*", "ㄊㄧㄢˋ"), composer.queries())
        assertEquals(2, composer.take(5).size)
        assertTrue(composer.isEmpty)
    }

    @Test
    fun anUncertainNeighbourBecomesAnotherReadingAndAnIllegalSwapDoesNot() {
        val composer = ZhuyinComposer()
        composer.append(
            "ㄍ",
            listOf(
                NearbyKey("ㄍ", 0.55f),
                NearbyKey("ㄎ", 0.30f),
                NearbyKey("ㄐ", 0.15f),
            ),
        )
        composer.append("ㄨ")
        composer.append("ㄥ")
        assertEquals(listOf("ㄍㄨㄥ*"), composer.queries())
        assertEquals(listOf(listOf("ㄎㄨㄥ*")), composer.corrections().map { it.queries })
        assertEquals(0.30f, composer.corrections().single().probability, 0.001f)
    }

    @Test
    fun aSureTouchAndADeletedKeyOfferNoCorrection() {
        val sure = ZhuyinComposer()
        sure.append("ㄍ", listOf(NearbyKey("ㄍ", 0.96f), NearbyKey("ㄎ", 0.20f)))
        sure.append("ㄨ")
        sure.append("ㄥ")
        assertTrue(sure.corrections().isEmpty())

        val composer = ZhuyinComposer()
        composer.append("ㄍ", listOf(NearbyKey("ㄍ", 0.55f), NearbyKey("ㄎ", 0.40f)))
        composer.backspace()
        assertTrue(composer.corrections().isEmpty())
    }

    @Test
    fun aToneNeighbourAndLevelToneStayWithTheSyllableTheyBelongTo() {
        val composer = ZhuyinComposer()
        composer.append("ㄚ")
        composer.append("ˊ", listOf(NearbyKey("ˊ", 0.60f), NearbyKey("ˇ", 0.35f)))
        composer.append(
            "ㄅ",
            listOf(NearbyKey("ㄅ", 0.50f), NearbyKey("ㄆ", 0.40f)),
        )
        assertEquals(
            listOf(listOf("ㄚˊ", "ㄆ*"), listOf("ㄚˇ", "ㄅ*")),
            composer.corrections().map { it.queries },
        )
        composer.backspace()
        composer.markLevelTone()
        assertTrue(composer.corrections().isEmpty())
        assertEquals(listOf("ㄚ"), composer.queries())
    }

    @Test
    fun takingASyllableKeepsItsNeighbourForWhenItReturns() {
        val composer = ZhuyinComposer()
        composer.append("ㄍ", listOf(NearbyKey("ㄍ", 0.55f), NearbyKey("ㄎ", 0.40f)))
        composer.append("ㄨ")
        composer.append("ㄥ")
        val front = composer.take(1)
        assertTrue(composer.corrections().isEmpty())
        composer.restore(front)
        assertEquals(listOf(listOf("ㄎㄨㄥ*")), composer.corrections().map { it.queries })
    }

    @Test
    fun neighbourWordsSitBehindTheFirstExactCandidate() {
        val exact = listOf(word("公", 1), word("工", 1))
        val corrected = listOf(word("空", 1), word("公", 1), word("控", 1))
        assertEquals(listOf("公", "空", "控", "工"), mergeTouchCorrections(exact, corrected).map { it.text })
        assertEquals(listOf("空"), mergeTouchCorrections(emptyList(), corrected).map { it.text }.take(1))
    }

    private fun word(text: String, span: Int) = Candidate(text = text, engineIndex = -1, span = span)

    private fun type(glyphs: String): ZhuyinComposer {
        val composer = ZhuyinComposer()
        for (ch in glyphs) {
            assertTrue(ch.toString(), composer.append(ch.toString()))
        }
        return composer
    }
}
