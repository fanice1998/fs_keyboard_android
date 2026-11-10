package com.fskeyboard.ime.engine

import com.fskeyboard.ime.candidate.PartialLexicon
import com.fskeyboard.ime.candidate.UnigramRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ChiaKeyRuntimeTest {
    @Before
    fun clearProcessState() {
        ChiaKeyRuntime.resetForTest()
    }

    @After
    fun dropProcessState() {
        ChiaKeyRuntime.resetForTest()
    }

    @Test
    fun oneSessionSurvivesASecondRequest() {
        val root = tempRoot()
        val first = ChiaKeyRuntime.session(root)
        val second = ChiaKeyRuntime.session(File(root, "other"))
        assertSame(first, second)
    }

    @Test
    fun aNewListenerReceivesTheIndexAlreadyBuilt() {
        val characters = lexicon("不" to listOf("ㄅㄨˋ"))
        val sentences = lexicon(
            "不" to listOf("ㄅㄨˋ"),
            "不是" to listOf("ㄅㄨˋ", "ㄕˋ"),
        )
        val first = mutableListOf<Int>()
        val second = mutableListOf<Int>()
        val firstListener = { lexicon: PartialLexicon -> first += lexicon.wordCount }
        val secondListener = { lexicon: PartialLexicon -> second += lexicon.wordCount }
        ChiaKeyRuntime.attach(firstListener)
        ChiaKeyRuntime.offer(characters, done = false)
        ChiaKeyRuntime.detach(firstListener)
        ChiaKeyRuntime.attach(secondListener)
        ChiaKeyRuntime.offer(sentences, done = true)
        assertEquals(listOf(1), first)
        assertEquals(listOf(1, 2), second)
    }

    @Test
    fun aCharacterOfferAfterTheSentenceIndexDoesNotReplaceIt() {
        val characters = lexicon("不" to listOf("ㄅㄨˋ"))
        val sentences = lexicon(
            "不" to listOf("ㄅㄨˋ"),
            "不是" to listOf("ㄅㄨˋ", "ㄕˋ"),
        )
        val seen = mutableListOf<Int>()
        val listener = { lexicon: PartialLexicon -> seen += lexicon.wordCount }
        ChiaKeyRuntime.attach(listener)
        ChiaKeyRuntime.offer(sentences, done = true)
        ChiaKeyRuntime.offer(characters, done = false)
        assertEquals(listOf(2), seen)
        ChiaKeyRuntime.detach(listener)
        val later = mutableListOf<Int>()
        ChiaKeyRuntime.attach { later += it.wordCount }
        assertEquals(listOf(2), later)
        assertTrue(ChiaKeyRuntime.indexDone())
    }

    @Test
    fun indexDoneStaysFalseUntilTheSentenceOffer() {
        assertFalse(ChiaKeyRuntime.indexDone())
        ChiaKeyRuntime.offer(lexicon("不" to listOf("ㄅㄨˋ")), done = false)
        assertFalse(ChiaKeyRuntime.indexDone())
    }

    @Test
    fun theUnigramIndexIsOnePass() {
        assertEquals(listOf(1..4), ChiaKeyIndexPlan.ranges())
    }

    @Test
    fun aFinishedScanDoesNotStartAgain() {
        assertTrue(ChiaKeyRuntime.claimScan())
        assertFalse(ChiaKeyRuntime.claimScan())
        ChiaKeyRuntime.finishScan()
        assertTrue(ChiaKeyRuntime.claimScan())
        ChiaKeyRuntime.offer(lexicon("不" to listOf("ㄅㄨˋ")), done = true)
        ChiaKeyRuntime.finishScan()
        assertFalse(ChiaKeyRuntime.claimScan())
    }

    @Test
    fun openIsSingleFlightUntilItFinishes() {
        assertTrue(ChiaKeyRuntime.claimOpen())
        assertFalse(ChiaKeyRuntime.claimOpen())
        ChiaKeyRuntime.finishOpen()
        assertTrue(ChiaKeyRuntime.claimOpen())
    }

    @Test
    fun syllableLengthIsTwoBytes() {
        assertEquals(null, ChiaKeyUnigramScan.lengthClause(1..Int.MAX_VALUE))
        assertEquals("length(qstring) BETWEEN 2 AND 2", ChiaKeyUnigramScan.lengthClause(1..1))
        assertEquals("length(qstring) BETWEEN 2 AND 8", ChiaKeyUnigramScan.lengthClause(1..4))
    }

    private fun lexicon(vararg rows: Pair<String, List<String>>): PartialLexicon {
        return PartialLexicon.index(rows.map { (text, syllables) -> UnigramRow.of(text, syllables, -1.0) })
    }

    private fun tempRoot() = File.createTempFile("chiakey-runtime", "").apply {
        delete()
        mkdirs()
    }
}
