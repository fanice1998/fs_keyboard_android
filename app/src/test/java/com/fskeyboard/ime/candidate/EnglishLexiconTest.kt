package com.fskeyboard.ime.candidate

import com.fskeyboard.ime.keyboard.KeyboardMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun spaceAndEnterTakeThePredictionAndOtherKeysKeepTheLetters() {
        assertEquals("deploy", EnglishSuggest.commit("depl", "deploy", takePrediction = true))
        assertEquals("depl", EnglishSuggest.commit("depl", "deploy", takePrediction = false))
        assertEquals("depl", EnglishSuggest.commit("depl", null, takePrediction = true))
        assertEquals("", EnglishSuggest.commit("", "deploy", takePrediction = false))
    }

    @Test
    fun dynamicLearningAddsNewWordsAndBoostsPredictions() {
        val lexicon = EnglishLexicon()
        lexicon.load(listOf("application\t100", "apple\t50"))
        // Before learning, "antigravity" is unknown
        val beforeHits = lexicon.complete("anti", limit = 5)
        assertTrue(beforeHits.words.isEmpty())

        // Learn "antigravity"
        assertTrue(lexicon.learn("antigravity"))
        val afterHits = lexicon.complete("anti", limit = 5)
        assertEquals(listOf("antigravity"), afterHits.words)

        // Invalid words are rejected
        assertFalse(lexicon.learn("a")) // too short
        assertFalse(lexicon.learn("bad_word!")) // invalid characters

        // Alphanumeric words (e.g. "abc123") are allowed and learned
        assertTrue(lexicon.learn("abc123"))
        assertEquals(listOf("abc123"), lexicon.complete("abc1", limit = 5).words)

        // Pure numbers without letters are rejected
        assertFalse(lexicon.learn("12345"))
    }

    @Test
    fun userLearnedWordsRankAheadOfDefaultDictionaryWords() {
        val lexicon = EnglishLexicon()
        lexicon.load(listOf("banana\t100", "band\t50"))
        lexicon.learn("band")
        lexicon.learn("band")
        val hits = lexicon.complete("ban", limit = 5)
        // "band" learned by user ranks ahead of "banana"
        assertEquals("band", hits.words.first())
    }

    @Test
    fun loadUserRestoresDynamicWords() {
        val lexicon = EnglishLexicon()
        lexicon.loadUser(listOf("gemini\t10", "claude\t5"))
        val hits = lexicon.complete("gem", limit = 5)
        assertEquals(listOf("gemini"), hits.words)
    }

    @Test
    fun englishPredictionsStayOnTheCandidateBar() {
        val word = Candidate("deploy", engineIndex = -1)
        assertEquals(listOf(word), shownCandidates(KeyboardMode.LATIN, listOf(word)))
        assertEquals(listOf(word), shownCandidates(KeyboardMode.ZHUYIN, listOf(word)))
        assertTrue(shownCandidates(KeyboardMode.SYMBOLS, listOf(word)).isEmpty())
    }
}
