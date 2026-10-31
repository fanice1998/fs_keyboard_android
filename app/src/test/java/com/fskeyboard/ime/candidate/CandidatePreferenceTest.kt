package com.fskeyboard.ime.candidate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidatePreferenceTest {
    @Test
    fun onePromoteMovesThatWordAheadOfItsNeighbour() {
        val preference = CandidatePreference()
        val band = word("band")
        preference.promote(band)
        val shown = preference.apply(listOf(word("banana"), band, word("bank")))
        assertEquals(listOf("band", "banana", "bank"), shown.map { it.text })
    }

    @Test
    fun oneDemoteMovesTheFirstWordBehindTheNext() {
        val preference = CandidatePreference()
        val banana = word("banana")
        preference.demote(banana)
        val shown = preference.apply(listOf(banana, word("band"), word("bank")))
        assertEquals(listOf("band", "banana", "bank"), shown.map { it.text })
    }

    @Test
    fun hideDropsTheWordAndTheFileBringsThatBack() {
        val preference = CandidatePreference()
        preference.hide(word("你", reading = "ㄋㄧˇ"))
        val first = preference.apply(listOf(word("你", reading = "ㄋㄧˇ"), word("呢", reading = "ㄋㄧˇ")))
        assertEquals(listOf("呢"), first.map { it.text })
        val loaded = CandidatePreference()
        loaded.load(preference.serialize())
        val again = loaded.apply(listOf(word("你", reading = "ㄋㄧˇ"), word("呢", reading = "ㄋㄧˇ")))
        assertEquals(listOf("呢"), again.map { it.text })
    }

    @Test
    fun aCancelledStepLeavesTheFile() {
        val preference = CandidatePreference()
        val band = word("band")
        preference.promote(band)
        preference.demote(band)
        assertTrue(preference.serialize().isEmpty())
        assertEquals(listOf("band", "bank"), preference.apply(listOf(band, word("bank"))).map { it.text })
    }

    @Test
    fun stepsStayInsideTheCap() {
        val preference = CandidatePreference()
        val band = word("band")
        repeat(80) { preference.promote(band) }
        val line = preference.serialize().single()
        assertTrue(line.endsWith("\t64\t0"))
    }

    private fun word(text: String, reading: String = "") = Candidate(text = text, engineIndex = 0, reading = reading)
}
