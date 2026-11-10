package com.fskeyboard.ime.latency

import com.fskeyboard.ime.engine.ChiaKeyRuntime
import java.util.Locale

/**
 * The last [capacity] zhuyin key-stage samples, in nanoseconds.
 * Older samples drop out. Each stage is its own clock, not a remainder.
 */
internal class KeyStageStats(private val capacity: Int = DEFAULT_CAPACITY) {
    private val touch = LongArray(capacity)
    private val compose = LongArray(capacity)
    private val lookup = LongArray(capacity)
    private val rank = LongArray(capacity)
    private val render = LongArray(capacity)
    private val composing = LongArray(capacity)
    private val gap = LongArray(capacity)
    private val draw = LongArray(capacity)
    private val indexDone = BooleanArray(capacity)
    private var next = 0
    private var size = 0

    init {
        require(capacity > 0)
    }

    fun add(
        touchNanos: Long,
        composeNanos: Long,
        lookupNanos: Long,
        rankNanos: Long,
        renderNanos: Long,
        composingNanos: Long,
        gapNanos: Long,
        drawNanos: Long,
        indexDone: Boolean,
    ) {
        if (drawNanos < 0 || touchNanos < 0 || composeNanos < 0 || lookupNanos < 0 ||
            rankNanos < 0 || renderNanos < 0 || composingNanos < 0 || gapNanos < 0
        ) {
            return
        }
        touch[next] = touchNanos
        compose[next] = composeNanos
        lookup[next] = lookupNanos
        rank[next] = rankNanos
        render[next] = renderNanos
        composing[next] = composingNanos
        gap[next] = gapNanos
        draw[next] = drawNanos
        this.indexDone[next] = indexDone
        next = (next + 1) % capacity
        if (size < capacity) size += 1
    }

    /** p95 of each stage. Null before the first sample. */
    fun summary(): KeyStageSummary? {
        if (size == 0) return null
        var done = 0
        for (index in 0 until size) if (indexDone[index]) done += 1
        return KeyStageSummary(
            count = size,
            indexDone = done,
            touchP95 = percentile(touch),
            composeP95 = percentile(compose),
            lookupP95 = percentile(lookup),
            rankP95 = percentile(rank),
            renderP95 = percentile(render),
            composingP95 = percentile(composing),
            gapP95 = percentile(gap),
            drawP95 = percentile(draw),
        )
    }

    private fun percentile(samples: LongArray): Double {
        val sorted = samples.copyOf(size).also { it.sort() }
        val rank = (95 * sorted.size + 99) / 100
        return sorted[(rank - 1).coerceIn(0, sorted.lastIndex)] / 1_000_000.0
    }

    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}

internal data class KeyStageSummary(
    val count: Int,
    val indexDone: Int,
    val touchP95: Double,
    val composeP95: Double,
    val lookupP95: Double,
    val rankP95: Double,
    val renderP95: Double,
    val composingP95: Double,
    val gapP95: Double,
    val drawP95: Double,
) {
    fun describe(): String = String.format(
        Locale.ROOT,
        "key stages n=%d indexDone=%d touch p95=%.1fms compose p95=%.1fms " +
            "lookup p95=%.1fms rank p95=%.1fms render p95=%.1fms composing p95=%.1fms " +
            "gap p95=%.1fms draw p95=%.1fms",
        count,
        indexDone,
        touchP95,
        composeP95,
        lookupP95,
        rankP95,
        renderP95,
        composingP95,
        gapP95,
        drawP95,
    )
}

/**
 * One zhuyin letter, from [begin] until its frame reports [noteDraw].
 * Main thread only, same as [FrameLatencyProbe].
 * A sample is kept only when that frame arrives, so draw is not a leftover.
 * The draw belongs to the key that called [armDraw]. A later key drops it.
 */
internal object KeyStages {
    private const val IDLE = 0
    private const val MEASURING = 1
    private const val AWAIT_DRAW = 2
    private const val LOG_EVERY = 50

    private val stats = KeyStageStats()
    private var phase = IDLE
    private var recorded = 0
    private var drawLatched = false
    var logger: (String) -> Unit = {}

    private var touchStart = 0L
    private var touch = 0L
    private var compose = 0L
    private var lookup = 0L
    private var rank = 0L
    private var render = 0L
    private var composing = 0L
    private var gap = 0L
    private var routerAt = 0L
    private var returnedAt = 0L
    private var indexDone = false

    /** Starts the touch clock. Drops a letter whose frame never arrived. */
    fun begin(now: Long) {
        phase = MEASURING
        touchStart = now
        touch = 0L
        compose = 0L
        lookup = 0L
        rank = 0L
        render = 0L
        composing = 0L
        gap = 0L
        routerAt = 0L
        returnedAt = 0L
        drawLatched = false
        indexDone = ChiaKeyRuntime.indexDone()
    }

    /** Touch ends as onKey is entered. The router gap starts here. */
    fun onKeyEntered() {
        if (phase != MEASURING || routerAt != 0L) return
        val now = System.nanoTime()
        touch = now - touchStart
        routerAt = now
    }

    /** Router gap stops at chain.append. */
    fun finishRouterGap() {
        if (phase != MEASURING || routerAt == 0L) return
        gap += System.nanoTime() - routerAt
        routerAt = 0L
    }

    fun noteCompose(nanos: Long) {
        if (phase == MEASURING && nanos >= 0L) compose = nanos
    }

    fun noteLookup(nanos: Long) {
        if (phase == MEASURING && nanos >= 0L) lookup = nanos
    }

    fun noteRank(nanos: Long) {
        if (phase == MEASURING && nanos >= 0L) rank = nanos
    }

    fun noteRender(nanos: Long) {
        if (phase == MEASURING && nanos >= 0L) render = nanos
    }

    fun noteComposing(nanos: Long) {
        if (phase == MEASURING && nanos >= 0L) composing = nanos
    }

    /** withCreated, or contextBefore plus the rerank submit. Added, not subtracted. */
    fun addGap(nanos: Long) {
        if (phase == MEASURING && nanos > 0L) gap += nanos
    }

    fun onKeyReturned() {
        if (phase != MEASURING) return
        finishRouterGap()
        if (returnedAt == 0L) returnedAt = System.nanoTime()
    }

    /** Tail of dispatch, up to keyHandled. */
    fun closeTail() {
        if (phase != MEASURING || returnedAt == 0L) return
        gap += System.nanoTime() - returnedAt
        returnedAt = 0L
    }

    fun armDraw() {
        if (phase != MEASURING) return
        phase = AWAIT_DRAW
        drawLatched = true
    }

    /** True once, for the key that just called [armDraw]. */
    fun claimDraw(): Boolean {
        if (phase != AWAIT_DRAW || !drawLatched) return false
        drawLatched = false
        return true
    }

    /** Drops the sample so [noteDraw] cannot attach a later frame. */
    fun abandon() {
        phase = IDLE
        drawLatched = false
    }

    fun recordedCount(): Int = recorded

    fun noteDraw(drawNanos: Long) {
        if (phase != AWAIT_DRAW) return
        phase = IDLE
        drawLatched = false
        if (drawNanos < 0L) return
        stats.add(touch, compose, lookup, rank, render, composing, gap, drawNanos, indexDone)
        recorded += 1
        if (recorded % LOG_EVERY == 0) {
            stats.summary()?.let { logger(it.describe()) }
        }
    }
}
