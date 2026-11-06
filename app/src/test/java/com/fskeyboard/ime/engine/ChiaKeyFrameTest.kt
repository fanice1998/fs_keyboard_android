package com.fskeyboard.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChiaKeyFrameTest {
    @Test
    fun parsesReadingComposingCommitAndCandidates() {
        val raw = "ㄏㄠ\u001f你\u001f\u001f1\u001f你好\u001e妳好"
        val frame = ChiaKeyFrame.parse(raw)
        assertEquals("ㄏㄠ", frame.reading)
        assertEquals("你", frame.composing)
        assertEquals("", frame.committed)
        assertTrue(frame.candidatesVisible)
        assertEquals(listOf("你好", "妳好"), frame.candidates)
        assertEquals("你ㄏㄠ", frame.display)
        assertTrue(frame.active)
    }

    @Test
    fun anEmptyFrameIsIdle() {
        val frame = ChiaKeyFrame.parse("")
        assertFalse(frame.active)
        assertEquals("", frame.display)
        assertTrue(frame.candidates.isEmpty())
    }
}
