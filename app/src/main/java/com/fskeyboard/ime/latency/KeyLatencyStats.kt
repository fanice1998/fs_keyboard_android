package com.fskeyboard.ime.latency

import java.util.Locale

/**
 * The last [capacity] key latencies, in nanoseconds.
 * Older samples drop out, so the numbers follow the current build and device state.
 */
internal class KeyLatencyStats(private val capacity: Int = DEFAULT_CAPACITY) {
    private val samples = LongArray(capacity)
    private var next = 0
    private var size = 0

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun add(nanos: Long) {
        if (nanos < 0) return
        samples[next] = nanos
        next = (next + 1) % capacity
        if (size < capacity) size += 1
    }

    @Synchronized
    fun clear() {
        next = 0
        size = 0
    }

    /** Nearest-rank percentiles. Null before the first sample. */
    @Synchronized
    fun summary(): KeyLatencySummary? {
        if (size == 0) return null
        val sorted = samples.copyOf(size).also { it.sort() }
        return KeyLatencySummary(
            count = size,
            p50Millis = percentile(sorted, 50),
            p95Millis = percentile(sorted, 95),
            p99Millis = percentile(sorted, 99),
            maxMillis = millis(sorted.last()),
        )
    }

    private fun percentile(sorted: LongArray, percent: Int): Double {
        val rank = (percent * sorted.size + 99) / 100
        return millis(sorted[(rank - 1).coerceIn(0, sorted.lastIndex)])
    }

    private fun millis(nanos: Long): Double = nanos / 1_000_000.0

    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}

internal data class KeyLatencySummary(
    val count: Int,
    val p50Millis: Double,
    val p95Millis: Double,
    val p99Millis: Double,
    val maxMillis: Double,
) {
    fun describe(): String = String.format(
        Locale.ROOT,
        "key latency n=%d p50=%.1fms p95=%.1fms p99=%.1fms max=%.1fms",
        count,
        p50Millis,
        p95Millis,
        p99Millis,
        maxMillis,
    )
}

/** One buffer per process, so the settings page reads what the keyboard measured. */
internal object KeyLatency {
    val stats = KeyLatencyStats()
}
