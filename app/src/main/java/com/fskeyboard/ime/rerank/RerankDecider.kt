package com.fskeyboard.ime.rerank

import com.fskeyboard.ime.candidate.Candidate

internal enum class RerankFallback {
    DISABLED,
    UNAVAILABLE,
    BUSY,
    TOO_SLOW,
    FAILED,
    BAD_SCORES,
}

internal data class RerankTrace(
    val fallback: RerankFallback?,
    val elapsedMillis: Long,
    val candidates: List<Candidate>,
    /** Time inside [RerankModel.score]. Zero when the call never reached the model. */
    val inferMillis: Long = 0,
)

/** A late result, or one whose keystroke was replaced, must not change the bar. */
internal fun acceptRerankUpdate(ticket: Long, current: Long, fallback: RerankFallback?): Boolean {
    return ticket == current && fallback == null
}

/**
 * Applies scores to the head of [candidates] and leaves the tail in Rime order.
 * Any failure, or a measured latency above [budgetMillis], keeps the original list.
 * Ties keep the original relative order. Candidate objects are not copied.
 */
internal object RerankDecider {
    fun decide(
        candidates: List<Candidate>,
        windowSize: Int,
        scores: FloatArray?,
        elapsedMillis: Long,
        budgetMillis: Long,
        failure: RerankFallback?,
        inferMillis: Long = 0,
    ): RerankTrace {
        if (failure != null || candidates.isEmpty()) {
            return RerankTrace(failure, elapsedMillis, candidates, inferMillis)
        }
        if (elapsedMillis > budgetMillis) {
            return RerankTrace(RerankFallback.TOO_SLOW, elapsedMillis, candidates, inferMillis)
        }
        if (scores == null) {
            return RerankTrace(RerankFallback.FAILED, elapsedMillis, candidates, inferMillis)
        }
        val window = windowSize
            .coerceIn(RerankLimits.MIN_WINDOW, RerankLimits.MAX_WINDOW)
            .coerceAtMost(candidates.size)
        if (scores.size < window || (0 until window).any { !scores[it].isFinite() }) {
            return RerankTrace(RerankFallback.BAD_SCORES, elapsedMillis, candidates, inferMillis)
        }
        val orderedHead = candidates.take(window)
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<Candidate>> { scores[it.index] }
                    .thenBy { it.index },
            )
            .map { it.value }
        return RerankTrace(
            fallback = null,
            elapsedMillis = elapsedMillis,
            candidates = orderedHead + candidates.drop(window),
            inferMillis = inferMillis,
        )
    }
}
