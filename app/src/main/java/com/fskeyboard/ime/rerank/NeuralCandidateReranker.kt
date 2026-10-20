package com.fskeyboard.ime.rerank

import com.fskeyboard.ime.candidate.Candidate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface NanoClock {
    fun now(): Long
}

internal fun interface RerankLogger {
    fun info(message: String)
}

/**
 * Reorders the first 5–9 librime candidates with [model].
 * [rerankAsync] does not block the caller. A run that is still going after
 * [NeuralRerankConfig.budgetMillis], a missing model, or the settings switch
 * keeps the original order. [rerank] is the same decision, but it waits at
 * most the budget so tests can read the result directly.
 * One inference at a time; a second request while the session is busy falls back.
 */
internal class NeuralCandidateReranker(
    private val model: RerankModel,
    private val enabled: () -> Boolean = { true },
    private val config: NeuralRerankConfig = NeuralRerankConfig(),
    private val clock: NanoClock = NanoClock { System.nanoTime() },
    private val executor: ExecutorService = newRerankExecutor(),
    private val logger: RerankLogger = RerankLogger { },
) : AsyncCandidateReranker {
    @Volatile
    var lastTrace: RerankTrace? = null
        private set

    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val loadStarted = AtomicBoolean(false)
    private val loggedUnavailable = AtomicBoolean(false)

    /**
     * Opens [model] once on the rerank thread. A second call does nothing.
     * A missing file or a thrown load is logged and does not escape.
     */
    fun load() {
        if (closed.get() || !loadStarted.compareAndSet(false, true)) return
        try {
            executor.execute {
                try {
                    model.load()
                } catch (error: Throwable) {
                    logger.info("rerank load failed ${error.javaClass.simpleName}")
                }
            }
        } catch (error: Exception) {
            logger.info("rerank load failed ${error.javaClass.simpleName}")
        }
    }

    override fun rerank(request: RerankRequest): List<Candidate> {
        ready(request)?.let { return it.candidates }
        if (!busy.compareAndSet(false, true)) {
            return publish(request.candidates, scores = null, elapsedMillis = 0, failure = RerankFallback.BUSY).candidates
        }
        val start = clock.now()
        var failure: RerankFallback? = null
        var scores: FloatArray? = null
        var inferMillis = 0L
        var submitted = false
        try {
            val future = executor.submit<Inference> { infer(request) }
            submitted = true
            val result = future.get(config.budgetMillis, TimeUnit.MILLISECONDS)
            scores = result.scores
            failure = result.failure
            inferMillis = result.inferMillis
        } catch (timeout: TimeoutException) {
            failure = RerankFallback.TOO_SLOW
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            failure = RerankFallback.FAILED
        } catch (error: Exception) {
            failure = RerankFallback.FAILED
            val reason = error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName
            logger.info("rerank failed $reason")
        } finally {
            if (!submitted) busy.set(false)
        }
        return publish(
            candidates = request.candidates,
            scores = scores,
            elapsedMillis = millisSince(start),
            failure = failure,
            inferMillis = inferMillis,
        ).candidates
    }

    override fun willScore(candidateCount: Int): Boolean {
        if (closed.get() || !enabled() || !model.available) return false
        return candidateCount >= 2
    }

    override fun rerankAsync(request: RerankRequest, onResult: (RerankTrace) -> Unit) {
        ready(request)?.let {
            onResult(it)
            return
        }
        if (!busy.compareAndSet(false, true)) {
            onResult(publish(request.candidates, scores = null, elapsedMillis = 0, failure = RerankFallback.BUSY))
            return
        }
        val start = clock.now()
        try {
            executor.execute {
                val result = infer(request)
                val trace = publish(
                    candidates = request.candidates,
                    scores = result.scores,
                    elapsedMillis = millisSince(start),
                    failure = result.failure,
                    inferMillis = result.inferMillis,
                )
                try {
                    onResult(trace)
                } catch (error: Throwable) {
                    logger.info("rerank callback failed ${error.javaClass.simpleName}")
                }
            }
        } catch (error: Exception) {
            busy.set(false)
            logger.info("rerank failed ${error.javaClass.simpleName}")
            onResult(publish(request.candidates, scores = null, elapsedMillis = 0, failure = RerankFallback.FAILED))
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdown()
        val finished = executor.awaitTermination(config.budgetMillis + 50, TimeUnit.MILLISECONDS)
        if (finished) model.close()
    }

    private fun ready(request: RerankRequest): RerankTrace? {
        if (closed.get() || !enabled()) {
            return publish(request.candidates, scores = null, elapsedMillis = 0, failure = RerankFallback.DISABLED)
        }
        if (!model.available) {
            if (loggedUnavailable.compareAndSet(false, true)) {
                logger.info("rerank model unavailable")
            }
            return publish(request.candidates, scores = null, elapsedMillis = 0, failure = RerankFallback.UNAVAILABLE)
        }
        if (request.candidates.size < 2) {
            return publish(request.candidates, scores = null, elapsedMillis = 0, failure = null)
        }
        return null
    }

    private fun infer(request: RerankRequest): Inference {
        val start = clock.now()
        return try {
            Inference(scores = model.score(windowed(request)), failure = null, inferMillis = millisSince(start))
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.info("rerank failed InterruptedException")
            Inference(scores = null, failure = RerankFallback.FAILED, inferMillis = millisSince(start))
        } catch (error: Throwable) {
            val reason = error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName
            logger.info("rerank failed $reason")
            Inference(scores = null, failure = RerankFallback.FAILED, inferMillis = millisSince(start))
        } finally {
            busy.set(false)
        }
    }

    private fun millisSince(start: Long): Long {
        return ((clock.now() - start) / 1_000_000L).coerceAtLeast(0)
    }

    private fun windowed(request: RerankRequest): NeuralRerankInput {
        return NeuralRerankInput(
            contextBefore = request.contextBefore.takeLast(config.contextChars),
            preedit = request.preedit,
            candidates = request.candidates.take(config.windowSize),
        )
    }

    private fun publish(
        candidates: List<Candidate>,
        scores: FloatArray?,
        elapsedMillis: Long,
        failure: RerankFallback?,
        inferMillis: Long = 0,
    ): RerankTrace {
        val trace = RerankDecider.decide(
            candidates = candidates,
            windowSize = config.windowSize,
            scores = scores,
            elapsedMillis = elapsedMillis,
            budgetMillis = config.budgetMillis,
            failure = failure,
            inferMillis = inferMillis,
        )
        lastTrace = trace
        if (trace.fallback == RerankFallback.TOO_SLOW || trace.fallback == RerankFallback.BAD_SCORES) {
            logger.info("rerank fallback ${trace.fallback} infer=${trace.inferMillis}ms elapsed=${trace.elapsedMillis}ms")
        }
        return trace
    }

    private class Inference(
        val scores: FloatArray?,
        val failure: RerankFallback?,
        val inferMillis: Long,
    )
}

internal fun newRerankExecutor(): ExecutorService {
    return Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fskeyboard-rerank").apply { isDaemon = true }
    }
}
