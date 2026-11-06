package com.fskeyboard.ime.candidate

import com.fskeyboard.ime.keyboard.ZhuyinTone
import com.fskeyboard.ime.ui.CompositionPreviewStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialLexiconTest {
    @Test
    fun absoluteOrderRoundTripsASyllable() {
        val encoded = BopomofoOrder.qstring(listOf("ㄋㄧˇ", "ㄏㄠˇ", "ㄓㄨㄥ", "ㄉㄜ˙", "ㄚ"))
        val decoded = BopomofoOrder.decode(encoded!!)
        assertEquals(
            listOf(
                SyllableReading("ㄋㄧ", ZhuyinTone.THIRD),
                SyllableReading("ㄏㄠ", ZhuyinTone.THIRD),
                SyllableReading("ㄓㄨㄥ", ZhuyinTone.LEVEL),
                SyllableReading("ㄉㄜ", ZhuyinTone.NEUTRAL),
                SyllableReading("ㄚ", ZhuyinTone.LEVEL),
            ),
            decoded,
        )
        assertNull(BopomofoOrder.decode("!"))
    }

    @Test
    fun anUnfinishedSyllableListsTheShorterReadingFirst() {
        val lexicon = PartialLexicon.index(
            listOf(
                row("主", listOf("ㄓㄨˇ"), -1.0),
                row("住", listOf("ㄓㄨˋ"), -1.4),
                row("注", listOf("ㄓㄨˋ"), -1.2),
                row("中", listOf("ㄓㄨㄥ"), -0.4),
            ),
        )
        val texts = lexicon.candidates(listOf(SyllableReading("ㄓㄨ", ZhuyinTone.NONE))).map { it.text }
        assertEquals("主", texts.first())
        assertTrue(texts.indexOf("主") < texts.indexOf("中"))
        assertTrue(texts.contains("住"))
    }

    @Test
    fun initialsOnlyRankAnExactReadingAheadOfALongerWord() {
        val lexicon = PartialLexicon.index(
            listOf(
                row("你好", listOf("ㄋㄧˇ", "ㄏㄠˇ"), -0.2),
                row("呢呵", listOf("ㄋ", "ㄏ"), -3.0),
            ),
        )
        val typed = listOf(
            SyllableReading("ㄋ", ZhuyinTone.NONE),
            SyllableReading("ㄏ", ZhuyinTone.NONE),
        )
        val texts = lexicon.candidates(typed).map { it.text }
        assertEquals("呢呵", texts.first())
        assertTrue(texts.indexOf("呢呵") < texts.indexOf("你好"))
        val one = lexicon.candidates(listOf(SyllableReading("ㄋ", ZhuyinTone.NONE))).map { it.text }
        assertTrue("你好" !in one)
    }

    @Test
    fun anUntonedSentenceUsesChiaKeyPhrases() {
        val lexicon = PartialLexicon.index(
            listOf(
                row("今天", listOf("ㄐㄧㄣ", "ㄊㄧㄢ"), -1.0),
                row("好開心", listOf("ㄏㄠˇ", "ㄎㄞ", "ㄒㄧㄣ"), -1.2),
                row("開心", listOf("ㄎㄞ", "ㄒㄧㄣ"), -1.1),
                row("好", listOf("ㄏㄠˇ"), -1.8),
                row("號", listOf("ㄏㄠˋ"), -1.4),
                row("開", listOf("ㄎㄞ"), -3.0),
                row("心", listOf("ㄒㄧㄣ"), -3.0),
                row("今", listOf("ㄐㄧㄣ"), -3.0),
                row("天", listOf("ㄊㄧㄢ"), -3.0),
            ),
        )
        val readings = listOf("ㄐㄧㄣ", "ㄊㄧㄢ", "ㄏㄠ", "ㄎㄞ", "ㄒㄧㄣ")
            .map { SyllableReading(it, ZhuyinTone.NONE) }
        val first = lexicon.candidates(readings).first()
        assertEquals("今天好開心", first.text)
        assertEquals(5, first.span)
    }

    @Test
    fun aMarkedToneKeepsOnlyThatTone() {
        val lexicon = PartialLexicon.index(
            listOf(
                row("好", listOf("ㄏㄠˇ"), -2.0),
                row("號", listOf("ㄏㄠˋ"), -1.0),
            ),
        )
        val texts = lexicon.candidates(listOf(SyllableReading("ㄏㄠ", ZhuyinTone.THIRD))).map { it.text }
        assertEquals(listOf("好"), texts)
    }

    @Test
    fun aFullReadingListsTheWordAheadOfAShorterOne() {
        val lexicon = PartialLexicon.index(
            listOf(
                row("你好", listOf("ㄋㄧˇ", "ㄏㄠˇ"), -1.2),
                row("你", listOf("ㄋㄧˇ"), -0.4),
                row("好", listOf("ㄏㄠˇ"), -0.5),
            ),
        )
        val typed = listOf(
            SyllableReading("ㄋㄧ", ZhuyinTone.NONE),
            SyllableReading("ㄏㄠ", ZhuyinTone.NONE),
        )
        val texts = lexicon.candidates(typed).map { it.text }
        assertEquals("你好", texts.first())
        assertTrue(texts.indexOf("你好") < texts.indexOf("你"))
    }

    @Test
    fun previewBlocksAreOneSymbolEachAndTheStyleIsADarkTranslucentChip() {
        assertEquals(listOf("你", "ㄏ", "ㄠ"), previewBlocks("你", "ㄏㄠ"))
        assertEquals(listOf("ㄋ", "ㄧ", "ˇ", "ㄏ", "ㄠ", "ˇ"), previewBlocks("", "ㄋㄧˇㄏㄠˇ"))
        val style = CompositionPreviewStyle.standard()
        assertEquals(0xFFFFFFFF.toInt(), style.text)
        assertEquals(0xB32C2C2E.toInt(), style.block)
    }

    private fun row(text: String, syllables: List<String>, probability: Double): UnigramRow {
        return UnigramRow.of(text, syllables, probability)
    }
}
