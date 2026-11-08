package com.fskeyboard.ime.candidate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun aLaterChoiceRaisesRankAfterADownAdjustment() {
        val preference = CandidatePreference()
        val banana = word("banana")
        preference.demote(banana)
        val lowered = preference.apply(listOf(banana, word("band"), word("bank")))
        assertEquals(listOf("band", "banana", "bank"), lowered.map { it.text })
        preference.promote(banana)
        val raised = preference.apply(listOf(banana, word("band"), word("bank")))
        assertEquals(listOf("banana", "band", "bank"), raised.map { it.text })
    }

    @Test
    fun aCreatedWordCanBeForgottenAndABaseWordCannot() {
        val preference = CandidatePreference()
        val made = word("自創", reading = "ㄗˋ")
        preference.remember(made)
        assertTrue(preference.isCreated(made))
        assertTrue(preference.forget(made))
        assertFalse(preference.isCreated(made))
        assertTrue(preference.serialize().none { it.contains("自創") })

        val base = word("你", reading = "ㄋㄧˇ")
        preference.promote(base)
        assertFalse(preference.isCreated(base))
        assertFalse(preference.forget(base))
        val shown = preference.apply(listOf(base, word("呢", reading = "ㄋㄧˇ")))
        assertTrue(shown.any { it.text == "你" })
    }

    @Test
    fun theTwentyFirstUnchosenShowDropsTheMemoryOnlyWhenEnabled() {
        val kept = CandidatePreference()
        val ni = word("你", reading = "ㄋㄧˇ")
        kept.promote(ni)
        val shown = listOf(ni)
        repeat(20) { kept.noteShown(shown, dropUnused = true) }
        assertTrue(kept.serialize().any { it.startsWith("ㄋㄧˇ\t你\t") })
        kept.noteShown(shown, dropUnused = true)
        assertTrue(kept.serialize().none { it.contains("你") })

        val held = CandidatePreference()
        held.promote(ni)
        repeat(21) { held.noteShown(shown, dropUnused = false) }
        assertTrue(held.serialize().any { it.startsWith("ㄋㄧˇ\t你\t") })
        held.promote(ni)
        repeat(20) { held.noteShown(shown, dropUnused = true) }
        assertTrue(held.serialize().any { it.startsWith("ㄋㄧˇ\t你\t") })
    }

    private fun word(text: String, reading: String = "") = Candidate(text = text, engineIndex = 0, reading = reading)
}
