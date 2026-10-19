package com.fskeyboard.ime.rerank

import com.fskeyboard.ime.candidate.Candidate
import java.io.Closeable

internal object RerankLimits {
    const val MIN_WINDOW = 5
    const val MAX_WINDOW = 9
    const val DEFAULT_WINDOW = MAX_WINDOW
    const val BUDGET_MILLIS = 50L
    /** Committed text immediately before the cursor. */
    const val CONTEXT_CHARS = 20
}

internal data class NeuralRerankConfig(
    val windowSize: Int = RerankLimits.DEFAULT_WINDOW,
    val budgetMillis: Long = RerankLimits.BUDGET_MILLIS,
    val contextChars: Int = RerankLimits.CONTEXT_CHARS,
) {
    init {
        require(windowSize in RerankLimits.MIN_WINDOW..RerankLimits.MAX_WINDOW) {
            "windowSize must be ${RerankLimits.MIN_WINDOW}..${RerankLimits.MAX_WINDOW}"
        }
        require(budgetMillis >= 1)
        require(contextChars in 1..128)
    }
}

/** One tensor the encoder wants the runtime to feed. */
internal sealed interface ModelTensor {
    val name: String
    val shape: LongArray

    data class Floats(
        override val name: String,
        override val shape: LongArray,
        val values: FloatArray,
    ) : ModelTensor

    data class Longs(
        override val name: String,
        override val shape: LongArray,
        val values: LongArray,
    ) : ModelTensor

    /** Raw strings. [values] is row-major and its length equals the product of [shape]. */
    data class Strings(
        override val name: String,
        override val shape: LongArray,
        val values: Array<String>,
    ) : ModelTensor
}

internal data class NeuralRerankInput(
    val contextBefore: String,
    val preedit: String,
    val candidates: List<Candidate>,
)

/**
 * Turns text into the tensors a model expects.
 * [ContextCandidateEncoder] emits character features. [DirectStringEncoder]
 * emits the context and candidate strings. A custom encoder can emit token
 * ids. The runtime ([com.fskeyboard.ime.rerank.onnx.OnnxRerankModel]) stays
 * the same and reads tensor names from this list.
 */
internal fun interface RerankEncoder {
    fun encode(input: NeuralRerankInput): List<ModelTensor>
}

/**
 * Scores the candidates in [NeuralRerankInput]. The returned array aligns
 * with [NeuralRerankInput.candidates]: higher is better.
 */
internal interface RerankModel : Closeable {
    val available: Boolean

    /**
     * Opens the model once. The default does nothing.
     * Implementations record a bad file and may throw; [OnnxCandidateRanker]
     * catches that so input-method startup continues.
     */
    fun load() {}

    fun score(input: NeuralRerankInput): FloatArray
}
