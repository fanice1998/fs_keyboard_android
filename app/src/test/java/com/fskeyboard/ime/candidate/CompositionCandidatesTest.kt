package com.fskeyboard.ime.candidate

import com.fskeyboard.ime.engine.EngineSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompositionCandidatesTest {
    @Test
    fun togglingScriptKeepsTheZhuyinInsteadOfTheKeySpelling() {
        val raw = EngineSnapshot(
            preedit = "su3",
            composingPreview = "su3",
            commit = "su3",
            candidates = listOf(Candidate(text = "你", engineIndex = 0)),
        )
        val shown = raw.preservingComposition("ㄋㄧˇ", listOf("ㄋㄧˇ"))
        assertEquals("ㄋㄧˇ", shown.preedit)
        assertEquals("ㄋㄧˇ", shown.composingPreview)
        assertNull(shown.commit)
        assertEquals("你", shown.candidates.first().text)
        assertEquals("ㄋㄧˇ", shown.candidates.last().text)
    }
}