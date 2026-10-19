package com.fskeyboard.ime.rerank

import com.fskeyboard.ime.candidate.Candidate

/**
 * Example reranker. Replace [boost] with a model score.
 * Ties keep the original Rime order. engineIndex is not rewritten.
 */
internal class ScoreBoostReranker(
    private val boost: (Candidate) -> Int = { 0 },
) : CandidateReranker {
    override fun rerank(request: RerankRequest): List<Candidate> {
        return request.candidates
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<Candidate>> { boost(it.value) }
                    .thenBy { it.index },
            )
            .map { it.value }
    }
}
