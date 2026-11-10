package com.fskeyboard.ime.latency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyStageStatsTest {
    @Test
    fun theStageLineIsCountsAndMillisecondsOnly() {
        val stats = KeyStageStats()
        repeat(50) { stats.add(ms(2), ms(2), ms(2), ms(2), ms(2), ms(2), ms(2), ms(2), indexDone = true) }
        val summary = stats.summary()!!
        assertEquals(
            "key stages n=50 indexDone=50 touch p95=2.0ms compose p95=2.0ms " +
                "lookup p95=2.0ms rank p95=2.0ms render p95=2.0ms composing p95=2.0ms " +
                "gap p95=2.0ms draw p95=2.0ms",
            summary.describe(),
        )
    }

    @Test
    fun p95UsesNearestRankAndIndexDoneCountsTheWindow() {
        val stats = KeyStageStats()
        for (value in 1L..100L) {
            stats.add(ms(value), ms(1), ms(1), ms(1), ms(1), ms(1), ms(1), ms(1), value <= 40L)
        }
        val summary = stats.summary()!!
        assertEquals(100, summary.count)
        assertEquals(40, summary.indexDone)
        assertEquals(95.0, summary.touchP95, 0.0)
    }

    @Test
    fun oldIndexDoneFlagsDropOutWhenFull() {
        val stats = KeyStageStats(capacity = 2)
        stats.add(ms(1), ms(1), ms(1), ms(1), ms(1), ms(1), ms(1), ms(1), indexDone = true)
        stats.add(ms(1), ms(1), ms(1), ms(1), ms(1), ms(1), ms(1), ms(1), indexDone = true)
        stats.add(ms(1), ms(1), ms(1), ms(1), ms(1), ms(1), ms(1), ms(1), indexDone = false)
        val summary = stats.summary()!!
        assertEquals(2, summary.count)
        assertEquals(1, summary.indexDone)
    }

    @Test
    fun aNegativeDrawIsIgnored() {
        val stats = KeyStageStats()
        stats.add(ms(1), ms(1), ms(1), ms(1), ms(1), ms(1), ms(1), -1L, indexDone = true)
        assertNull(stats.summary())
    }

    private fun ms(value: Long): Long = value * 1_000_000L
}
