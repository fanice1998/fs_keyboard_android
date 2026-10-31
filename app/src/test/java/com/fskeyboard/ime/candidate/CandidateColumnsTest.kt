package com.fskeyboard.ime.candidate

import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateColumnsTest {
    @Test
    fun columnsFollowTheWidthAndStayBetweenFourAndSix() {
        assertEquals(4, candidateColumns(0f))
        assertEquals(4, candidateColumns(20f))
        assertEquals(4, candidateColumns(360f))
        assertEquals(5, candidateColumns(400f))
        assertEquals(6, candidateColumns(528f))
        assertEquals(6, candidateColumns(900f))
    }
}
