package com.fskeyboard.ime.rerank

import com.fskeyboard.ime.candidate.Candidate

internal data class RerankRequest(
    val preedit: String,
    val preview: String,
    val candidates: List<Candidate>,
    /** Committed text before the cursor. Empty when the field has none. */
    val contextBefore: String = "",
)

/**
 * Reorder, drop, or append candidates.
 *
 * Keep [Candidate.engineIndex] and the text of every [com.fskeyboard.ime.candidate.CandidateSource.RIME]
 * item. Changing that text while keeping the index would teach the user dictionary
 * a different phrase than the one shown.
 * New phrases use a negative [Candidate.engineIndex].
 *
 * The list is the first window returned by librime (see [com.fskeyboard.ime.engine.RimeEngine]),
 * not a single Rime page. A neural reranker may reorder only a prefix of that list.
 */
internal fun interface CandidateReranker {
    fun rerank(request: RerankRequest): List<Candidate>

    fun close() {}
}

/**
 * Scores off the input thread. [onResult] may run on the rerank thread.
 * The keyboard applies it only while the same keystroke is still current
 * and [RerankTrace.fallback] is null.
 */
internal interface AsyncCandidateReranker : CandidateReranker {
    fun rerankAsync(request: RerankRequest, onResult: (RerankTrace) -> Unit)

    /** True when this keystroke will run the model and needs cursor context. */
    fun willScore(candidateCount: Int): Boolean
}
