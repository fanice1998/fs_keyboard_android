package com.fskeyboard.ime.latency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyLatencyStatsTest {
    private fun ms(value: Long): Long = value * 1_000_000L

    @Test
    fun emptyStatsHaveNoSummary() {
        assertNull(KeyLatencyStats().summary())
    }

    @Test
    fun percentilesUseNearestRank() {
        val stats = KeyLatencyStats()
        for (value in 1L..100L) stats.add(ms(value))
        val summary = stats.summary()!!
        assertEquals(100, summary.count)
        assertEquals(50.0, summary.p50Millis, 0.0)
        assertEquals(95.0, summary.p95Millis, 0.0)
        assertEquals(99.0, summary.p99Millis, 0.0)
        assertEquals(100.0, summary.maxMillis, 0.0)
    }

    @Test
    fun aSingleSampleIsEveryPercentile() {
        val stats = KeyLatencyStats()
        stats.add(ms(7))
        val summary = stats.summary()!!
        assertEquals(7.0, summary.p50Millis, 0.0)
        assertEquals(7.0, summary.p99Millis, 0.0)
    }

    @Test
    fun oldSamplesDropOutWhenFull() {
        val stats = KeyLatencyStats(capacity = 3)
        listOf(100L, 1L, 2L, 3L).forEach { stats.add(ms(it)) }
        val summary = stats.summary()!!
        assertEquals(3, summary.count)
        assertEquals(3.0, summary.maxMillis, 0.0)
    }

    @Test
    fun negativeSamplesAreIgnoredAndClearEmpties() {
        val stats = KeyLatencyStats()
        stats.add(-1)
        assertNull(stats.summary())
        stats.add(ms(5))
        stats.clear()
        assertNull(stats.summary())
    }
}
