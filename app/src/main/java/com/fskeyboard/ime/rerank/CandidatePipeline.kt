package com.fskeyboard.ime.rerank

import com.fskeyboard.ime.candidate.Candidate
import com.fskeyboard.ime.candidate.CandidateSource
import com.fskeyboard.ime.engine.EngineSnapshot
import com.fskeyboard.ime.script.OutputScript

internal class CandidatePipeline(
    private val reranker: CandidateReranker = PassthroughReranker(),
    private val script: OutputScript = OutputScript.IDENTITY,
) {
    /** Rime order with script conversion, before the model runs. */
    fun preview(snapshot: EngineSnapshot): List<Candidate> = finish(snapshot.candidates)

    /** False when the model is absent, so the key path can skip reading the editor. */
    fun willScore(candidateCount: Int): Boolean {
        val async = reranker as? AsyncCandidateReranker ?: return false
        return async.willScore(candidateCount)
    }

    fun present(snapshot: EngineSnapshot, contextBefore: String = ""): List<Candidate> {
        return finish(reranker.rerank(request(snapshot, contextBefore)))
    }

    /**
     * Asks the reranker without waiting. [onResult] keeps [RerankTrace.fallback]
     * and the timing fields. The candidate texts are script-converted.
     */
    fun presentAsync(
        snapshot: EngineSnapshot,
        contextBefore: String = "",
        onResult: (RerankTrace) -> Unit,
    ) {
        val pending = request(snapshot, contextBefore)
        val deliver = { trace: RerankTrace ->
            onResult(trace.copy(candidates = finish(trace.candidates)))
        }
        val async = reranker as? AsyncCandidateReranker
        if (async != null) {
            async.rerankAsync(pending, deliver)
        } else {
            deliver(RerankTrace(fallback = null, elapsedMillis = 0, candidates = reranker.rerank(pending)))
        }
    }

    private fun request(snapshot: EngineSnapshot, contextBefore: String): RerankRequest {
        return RerankRequest(
            preedit = snapshot.preedit,
            preview = snapshot.composingPreview,
            candidates = snapshot.candidates,
            contextBefore = contextBefore,
        )
    }

    private fun finish(candidates: List<Candidate>): List<Candidate> {
        return candidates.map { candidate ->
            if (candidate.source == CandidateSource.RIME) {
                candidate
            } else {
                candidate.copy(text = script.convert(candidate.text))
            }
        }
    }

    fun close() {
        reranker.close()
    }
}
