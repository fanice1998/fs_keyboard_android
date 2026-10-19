package com.fskeyboard.ime.rerank

import kotlin.math.sqrt

/**
 * Default features for a small model. Layout of `features` is float32 [1, N, 64]:
 * 0 rank prior, 1 candidate length, 2 context length, 3 preedit length,
 * 4 overlap with the tail of the context, 5..63 signed character bigrams.
 * `mask` is float32 [1, N], 1 for every real candidate.
 */
internal class ContextCandidateEncoder : RerankEncoder {
    override fun encode(input: NeuralRerankInput): List<ModelTensor> {
        val count = input.candidates.size
        val context = input.contextBefore.takeLast(RerankLimits.CONTEXT_CHARS)
        val preedit = input.preedit
        val features = FloatArray(count * OnnxContract.FEATURE_DIM)
        input.candidates.forEachIndexed { index, candidate ->
            val offset = index * OnnxContract.FEATURE_DIM
            features[offset] = index.toFloat() / RerankLimits.MAX_WINDOW
            features[offset + 1] = candidate.text.length.coerceAtMost(16) / 16f
            features[offset + 2] = context.length / RerankLimits.CONTEXT_CHARS.toFloat()
            features[offset + 3] = preedit.length.coerceAtMost(16) / 16f
            features[offset + 4] = if (overlaps(context, candidate.text)) 1f else 0f
            hashBigrams(
                source = context + "\n" + preedit + "\t" + candidate.text,
                dest = features,
                offset = offset + HASH_OFFSET,
                width = OnnxContract.FEATURE_DIM - HASH_OFFSET,
            )
        }
        return listOf(
            ModelTensor.Floats(
                name = OnnxContract.FEATURES,
                shape = longArrayOf(1, count.toLong(), OnnxContract.FEATURE_DIM.toLong()),
                values = features,
            ),
            ModelTensor.Floats(
                name = OnnxContract.MASK,
                shape = longArrayOf(1, count.toLong()),
                values = FloatArray(count) { 1f },
            ),
        )
    }

    private fun overlaps(context: String, text: String): Boolean {
        if (context.isEmpty() || text.isEmpty()) return false
        val tail = context.takeLast(4)
        return text.any { it in tail }
    }

    private fun hashBigrams(source: String, dest: FloatArray, offset: Int, width: Int) {
        if (source.length < 2 || width <= 0) return
        var count = 0
        for (index in 0 until source.length - 1) {
            val hash = (source[index].code * 131 + source[index + 1].code) and Int.MAX_VALUE
            val bucket = offset + (hash % width)
            dest[bucket] += if ((hash and 1) == 0) 1f else -1f
            count++
        }
        val scale = 1f / sqrt(count.toFloat())
        for (index in offset until offset + width) {
            dest[index] *= scale
        }
    }

    private companion object {
        const val HASH_OFFSET = 5
    }
}
