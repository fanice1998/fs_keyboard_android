package com.fskeyboard.ime.latency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KeyStagesDrawLatchTest {
    @Test
    fun theArmingKeyKeepsItsDraw() {
        val before = KeyStages.recordedCount()
        KeyStages.begin(0L)
        KeyStages.armDraw()
        val probe = FrameLatencyProbe(KeyLatencyStats())
        probe.keyHandled(1L)
        KeyStages.noteDraw(2_000_000L)
        assertEquals(before + 1, KeyStages.recordedCount())
    }

    @Test
    fun aLaterKeyDropsTheArmedDraw() {
        val before = KeyStages.recordedCount()
        KeyStages.begin(0L)
        KeyStages.armDraw()
        val probe = FrameLatencyProbe(KeyLatencyStats())
        probe.keyHandled(1L)
        probe.keyHandled(2L)
        KeyStages.noteDraw(3_000_000L)
        assertEquals(before, KeyStages.recordedCount())
        assertFalse(KeyStages.claimDraw())
    }

    @Test
    fun aDroppedFrameDoesNotReviveOnTheNextKey() {
        val before = KeyStages.recordedCount()
        KeyStages.begin(0L)
        KeyStages.armDraw()
        val probe = FrameLatencyProbe(KeyLatencyStats())
        probe.keyHandled(1L)
        probe.acceptFrame(dropCount = 1) { error("a dropped report has no metric") }
        KeyStages.noteDraw(3_000_000L)
        assertEquals(before, KeyStages.recordedCount())
        probe.keyHandled(2L)
        KeyStages.noteDraw(4_000_000L)
        assertEquals(before, KeyStages.recordedCount())
    }
}
