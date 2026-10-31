package com.fskeyboard.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceGestureTest {
    @Test
    fun aSecondPressInsideTheWindowArmsVoice() {
        assertEquals(550L, VoiceGesture.TAP_WINDOW_MS)
        assertEquals(400L, VoiceGesture.HOLD_MS)
        assertFalse(VoiceGesture.armed(VoiceGesture.NONE, 1_000L))
        assertFalse(VoiceGesture.armed(1_000L, 999L))
        assertTrue(VoiceGesture.armed(1_000L, 1_550L))
        assertFalse(VoiceGesture.armed(1_000L, 1_551L))
    }
}
