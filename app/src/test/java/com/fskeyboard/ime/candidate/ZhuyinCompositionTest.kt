package com.fskeyboard.ime.candidate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhuyinCompositionTest {
    private fun word(text: String, span: Int) = Candidate(text = text, engineIndex = -1, span = span)

    private fun typed(glyphs: String): ZhuyinComposition {
        val composition = ZhuyinComposition()
        for (ch in glyphs) assertTrue(ch.toString(), composition.append(ch.toString()))
        return composition
    }

    @Test
    fun choosingTheFrontWordKeepsTheRestToChoose() {
        val composition = typed("ㄐㄧㄣㄊㄧㄢㄊㄧㄢㄑㄧˋ")
        assertNull(composition.choose(word("今天", span = 2)))
        assertEquals("今天ㄊㄧㄢㄑㄧˋ", composition.text)
        assertEquals(listOf("ㄊㄧㄢ*", "ㄑㄧˋ"), composition.pending.queries())
        assertEquals("今天天氣", composition.choose(word("天氣", span = 2))?.text)
        assertTrue(composition.isEmpty)
    }

    @Test
    fun backspaceEditsTheZhuyinThenTurnsTheLastWordBackIntoZhuyin() {
        val composition = typed("ㄐㄧㄣㄊㄧㄢㄊ")
        composition.choose(word("今天", span = 2))
        composition.backspace()
        assertEquals("今天", composition.text)
        assertTrue(composition.pending.isEmpty)
        composition.backspace()
        assertEquals("ㄐㄧㄣㄊㄧㄢ", composition.text)
        assertEquals(listOf("ㄐㄧㄣ*", "ㄊㄧㄢ*"), composition.pending.queries())
        composition.backspace()
        assertEquals("ㄐㄧㄣㄊㄧ", composition.text)
    }

    @Test
    fun aChosenToneComesBackWithItsWord() {
        val composition = typed("ㄋㄧˇㄏㄠˇ")
        composition.choose(word("你", span = 1))
        composition.backspace()
        composition.backspace()
        composition.backspace()
        assertEquals("你", composition.text)
        composition.backspace()
        assertEquals(listOf("ㄋㄧˇ"), composition.pending.queries())
    }

    @Test
    fun convertAllUsesTheScreenCandidateFirstThenTheFrontEachTime() {
        val composition = typed("ㄍㄨㄥㄎㄜˋㄅㄣˇ")
        val result = composition.convertAll(first = word("功課", span = 2)) { pending ->
            assertEquals(listOf("ㄅㄣˇ"), pending.queries())
            listOf(word("本", span = 1))
        }
        assertEquals("功課本", result.text)
        assertTrue(result.learned.isEmpty())
        assertTrue(composition.isEmpty)
    }

    @Test
    fun convertAllKeepsChosenWordsAndFallsBackToZhuyin() {
        val composition = typed("ㄍㄨㄥㄅㄚ")
        composition.choose(word("公", span = 1))
        assertEquals("公ㄅㄚ", composition.convertAll(first = null) { emptyList() }.text)
        assertTrue(composition.isEmpty)
    }

    @Test
    fun aZeroSpanStillUsesOneSyllable() {
        val composition = typed("ㄍㄨㄥㄅㄚ")
        assertNull(composition.choose(word("公", span = 0)))
        assertEquals("公ㄅㄚ", composition.text)
    }

    @Test
    fun theRawZhuyinCandidateSpansEverySyllable() {
        val composition = typed("ㄍㄨㄥㄅㄚ")
        val shown = candidatesFor(composition.pending, listOf(word("公", span = 1)))
        assertEquals(listOf("公", "ㄍㄨㄥㄅㄚ"), shown.map { it.text })
        assertEquals(2, shown.last().span)
        assertEquals("ㄍㄨㄥㄅㄚ", composition.choose(shown.last())?.text)
    }

    @Test
    fun twoWordsChosenInARowAreLearnedAsOnePhrase() {
        val composition = typed("ㄓㄣㄉㄜㄐㄧㄚㄉㄜ")
        fun lexical(text: String, reading: String) =
            Candidate(text = text, engineIndex = -1, span = 2, reading = reading)
        composition.choose(lexical("真的", "ㄓㄣ ㄉㄜ˙"))
        val done = composition.choose(lexical("假的", "ㄐㄧㄚˇ ㄉㄜ˙"))!!
        assertEquals("真的假的", done.text)
        assertEquals(
            listOf(
                "ㄓㄣ ㄉㄜ˙" to "真的",
                "ㄐㄧㄚˇ ㄉㄜ˙" to "假的",
                "ㄓㄣ ㄉㄜ˙ ㄐㄧㄚˇ ㄉㄜ˙" to "真的假的",
            ),
            done.learned,
        )
    }

    @Test
    fun enterLearnsThePhraseItConfirms() {
        val composition = typed("ㄎㄚˇㄎㄡˋ")
        val done = composition.convertAll(
            first = Candidate(text = "卡口", engineIndex = -1, span = 2, reading = "ㄎㄚˇ ㄎㄡˋ"),
        ) { emptyList() }
        assertEquals("卡口", done.text)
        assertEquals(listOf("ㄎㄚˇ ㄎㄡˋ" to "卡口"), done.learned)
    }

    @Test
    fun enterOnOneCharacterIsNotStoredAsAPhrase() {
        val composition = typed("ㄍㄨㄥ")
        val done = composition.convertAll(
            first = Candidate(text = "公", engineIndex = -1, span = 1, reading = "ㄍㄨㄥ"),
        ) { emptyList() }
        assertEquals("公", done.text)
        assertTrue(done.learned.isEmpty())
    }

    @Test
    fun oneChosenPhraseIsLearnedOnItsOwn() {
        val composition = typed("ㄎㄚˇㄎㄡˋ")
        val done = composition.choose(
            Candidate(text = "卡口", engineIndex = -1, span = 2, reading = "ㄎㄚˇ ㄎㄡˋ"),
        )!!
        assertEquals(listOf("ㄎㄚˇ ㄎㄡˋ" to "卡口"), done.learned)
    }

    @Test
    fun oneChosenSingleCharacterIsLearnedOnItsOwn() {
        val composition = typed("ㄍㄨㄥ")
        val done = composition.choose(
            Candidate(text = "工", engineIndex = -1, span = 1, reading = "ㄍㄨㄥ"),
        )!!
        assertEquals(listOf("ㄍㄨㄥ" to "工"), done.learned)
    }

    @Test
    fun aWordWithoutAReadingIsNotLearned() {
        val composition = typed("ㄍㄨㄥㄅㄚ")
        composition.choose(Candidate(text = "公", engineIndex = -1, reading = "ㄍㄨㄥ"))
        val done = composition.choose(word("ㄅㄚ", span = 1))!!
        assertEquals(listOf("ㄍㄨㄥ" to "公"), done.learned)
    }
}
