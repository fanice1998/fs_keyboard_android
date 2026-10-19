package com.fskeyboard.ime.engine

import com.fskeyboard.ime.bridge.RimeResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RimeResponseMappingTest {
    @Test
    fun keepsEngineIndexWhenABlankCandidateIsSkipped() {
        val snapshot = RimeResponse(
            consumed = true,
            commit = "",
            preedit = "ni",
            preview = "你",
            candidates = arrayOf("你", "", "呢"),
            comments = arrayOf("", "", "口語"),
            indices = intArrayOf(0, 1, 2),
            highlighted = 0,
        ).toSnapshot()

        assertEquals(true, snapshot.consumed)
        assertNull(snapshot.commit)
        assertEquals(listOf(0, 2), snapshot.candidates.map { it.engineIndex })
        assertEquals("呢", snapshot.candidates[1].text)
        assertEquals("口語", snapshot.candidates[1].comment)
    }
}
