package com.fskeyboard.ime.candidate

import com.fskeyboard.ime.keyboard.KeyboardMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EnglishLexiconTest {
    @Test
    fun anExactWordLeadsEvenWhenALongerWordIsMoreFrequent() {
        val lexicon = EnglishLexicon()
        lexicon.load(
            listOf(
                "application\t100",
                "apple\t50",
                "app\t10",
            ),
        )
        val hits = lexicon.complete("app", limit = 8)
        val shown = EnglishSuggest.candidates("app", hits, limit = 8)
        assertEquals(listOf("app", "application", "apple"), shown)
    }

    @Test
    fun anUnfinishedPrefixCompletesAndKeepsWhatWasTyped() {
        val lexicon = EnglishLexicon()
        lexicon.load(listOf("deploy\t40", "deployment\t30", "depth\t20"))
        val hits = lexicon.complete("depl", limit = 8)
        assertEquals(
            listOf("deploy", "depl", "deployment"),
            EnglishSuggest.candidates("depl", hits, limit = 8),
        )
    }

    @Test
    fun caseFollowsTheTypedPrefix() {
        val lexicon = EnglishLexicon()
        lexicon.load(listOf("deploy\t40", "deployment\t10"))
        val hits = lexicon.complete("dep", limit = 8)
        assertEquals("Deploy", EnglishSuggest.candidates("Dep", hits, limit = 1).first())
        assertEquals("DEPLOY", EnglishSuggest.candidates("DEP", hits, limit = 1).first())
    }

    @Test
    fun theShippedListHasEverydayWordsAndTheAddedTechnicalOnes() {
        val file = listOf(
            File("src/main/assets/lexicon/english.tsv"),
            File("app/src/main/assets/lexicon/english.tsv"),
        ).first { it.isFile }
        val lexicon = EnglishLexicon()
        lexicon.load(file.readLines())
        assertTrue(lexicon.size > 10_000)
        val the = EnglishSuggest.candidates("th", lexicon.complete("th", 8), 8)
        assertEquals("the", the.first())
        assertTrue(EnglishSuggest.candidates("kube", lexicon.complete("kube", 8), 8).any { it == "kubectl" })
        assertEquals("deploy", EnglishSuggest.candidates("deploy", lexicon.complete("deploy", 8), 8).first())
    }

    @Test
    fun englishPredictionsStayOnTheCandidateBar() {
        val word = Candidate("deploy", engineIndex = -1)
        assertEquals(listOf(word), shownCandidates(KeyboardMode.LATIN, listOf(word)))
        assertEquals(listOf(word), shownCandidates(KeyboardMode.ZHUYIN, listOf(word)))
        assertTrue(shownCandidates(KeyboardMode.SYMBOLS, listOf(word)).isEmpty())
    }
}
