package com.fskeyboard.ime.latency

import android.view.FrameMetrics
import android.view.Window

/**
 * Time from the touch event that fired a key to the end of the first frame that drew its result.
 * Touch times and frame metrics both use the monotonic clock.
 * That frame is the first one whose UI-thread work ended after the key was handled.
 * Runs on the main thread: [keyHandled] from the keyboard, frames through a main-looper handler.
 */
internal class FrameLatencyProbe(
    private val stats: KeyLatencyStats,
    private val logger: (String) -> Unit = {},
) : Window.OnFrameMetricsAvailableListener {
    private var touchNanos = 0L
    private var handledNanos = 0L
    private var recorded = 0

    /** Called after the key's lookup, candidate bar and editor update have returned. */
    fun keyHandled(eventUptimeMillis: Long) {
        touchNanos = eventUptimeMillis * 1_000_000L
        handledNanos = System.nanoTime()
    }

    override fun onFrameMetricsAvailable(window: Window, metrics: FrameMetrics, dropCount: Int) {
        if (handledNanos == 0L) return
        if (dropCount > 0) {
            // The frame that drew the key may be among the dropped reports.
            handledNanos = 0L
            return
        }
        val vsync = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        val uiEnd = vsync + UI_PHASES.sumOf { metrics.getMetric(it) }
        if (uiEnd < handledNanos) return
        stats.add(vsync + metrics.getMetric(FrameMetrics.TOTAL_DURATION) - touchNanos)
        handledNanos = 0L
        recorded += 1
        if (recorded % LOG_EVERY == 0) {
            stats.summary()?.let { logger(it.describe()) }
        }
    }

    private companion object {
        const val LOG_EVERY = 50
        val UI_PHASES = intArrayOf(
            FrameMetrics.UNKNOWN_DELAY_DURATION,
            FrameMetrics.INPUT_HANDLING_DURATION,
            FrameMetrics.ANIMATION_DURATION,
            FrameMetrics.LAYOUT_MEASURE_DURATION,
            FrameMetrics.DRAW_DURATION,
        )
    }
}
