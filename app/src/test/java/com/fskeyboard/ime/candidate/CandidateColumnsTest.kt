package com.fskeyboard.ime.candidate

import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateColumnsTest {
    @Test
    fun expandedRowIsSixColumns() {
        assertEquals(6, candidateColumns())
    }
}
