package com.fskeyboard.ime.rerank

import com.fskeyboard.ime.candidate.Candidate
import java.util.concurrent.ExecutorService

/**
 * Reorders at most [RerankLimits.MAX_WINDOW] candidate strings using the
 * last [RerankLimits.CONTEXT_CHARS] characters of [context].
 *
 * The returned list is a permutation of the same string instances, so
 * duplicate texts stay distinguishable. Anything past the window keeps
 * its original place. Timeout, a model error, a missing file, or the
 * settings switch returns the original list.
 *
 * [load] is the one startup load. The IME also calls [rerank] with a
 * [RerankRequest]; that path keeps each [Candidate] and its engine index.
 */
internal class OnnxCandidateRanker(
    model: RerankModel,
    enabled: () -> Boolean = { true },
    config: NeuralRerankConfig = NeuralRerankConfig(),
    clock: NanoClock = NanoClock { System.nanoTime() },
    executor: ExecutorService = newRerankExecutor(),
    logger: RerankLogger = RerankLogger { },
) : AsyncCandidateReranker {
    private val neural = NeuralCandidateReranker(
        model = model,
        enabled = enabled,
        config = config,
        clock = clock,
        executor = executor,
        logger = logger,
    )

    val lastTrace: RerankTrace?
        get() = neural.lastTrace

    /** Copies the asset, if needed, and opens the session on the rerank thread. */
    fun load() {
        neural.load()
    }

    fun rerank(context: String, candidates: List<String>, preedit: String = ""): List<String> {
        if (candidates.isEmpty()) return candidates
        val wrapped = candidates.mapIndexed { index, text ->
            Candidate(text = text, engineIndex = index)
        }
        return neural.rerank(
            RerankRequest(
                preedit = preedit,
                preview = "",
                candidates = wrapped,
                contextBefore = context,
            ),
        ).map { it.text }
    }

    override fun rerank(request: RerankRequest): List<Candidate> = neural.rerank(request)

    override fun willScore(candidateCount: Int): Boolean = neural.willScore(candidateCount)

    override fun rerankAsync(request: RerankRequest, onResult: (RerankTrace) -> Unit) {
        neural.rerankAsync(request, onResult)
    }

    override fun close() {
        neural.close()
    }
}
