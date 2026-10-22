package com.fskeyboard.ime.candidate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticlesTest {
    @Test
    fun aQuestionWordTakesQuestionParticlesButNotBaOrLuo() {
        val found = Particles.suggest("你在哪")
        assertEquals("啊", found.first())
        assertTrue("呀" in found)
        assertFalse("吧" in found)
        assertFalse("囉" in found)
    }

    @Test
    fun aStatementTakesBaFirst() {
        assertEquals("吧", Particles.suggest("我們走").first())
    }

    @Test
    fun aReplyAndThanksHaveTheirOwnParticles() {
        assertEquals("啊", Particles.suggest("好").first())
        assertEquals("啦", Particles.suggest("謝謝").first())
    }

    @Test
    fun nothingAfterPunctuationLatinOrAParticle() {
        assertTrue(Particles.suggest("").isEmpty())
        assertTrue(Particles.suggest("你好。").isEmpty())
        assertTrue(Particles.suggest("hello").isEmpty())
        assertTrue(Particles.suggest("你好 ").isEmpty())
        assertTrue(Particles.suggest("走吧").isEmpty())
    }

    @Test
    fun promoteMovesFittingParticlesToTheFront() {
        val typed = listOf("阿", "啊", "呀", "吧").map { Candidate(it, engineIndex = -1) }
        assertEquals(listOf("啊", "呀", "阿", "吧"), Particles.promote(typed, "你在哪").map { it.text })
        assertEquals(typed, Particles.promote(typed, ""))
    }
}
