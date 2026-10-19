package com.fskeyboard.ime.rerank

/**
 * Feeds the context and the candidate texts as strings.
 * `context` is a string tensor of shape [1], capped at
 * [RerankLimits.CONTEXT_CHARS]. `candidates` is a string tensor of shape
 * [N], N at most [RerankLimits.MAX_WINDOW]. Preedit is left for an encoder
 * that needs it; this contract is only the two inputs above.
 */
internal class DirectStringEncoder : RerankEncoder {
    override fun encode(input: NeuralRerankInput): List<ModelTensor> {
        val texts = input.candidates
            .take(RerankLimits.MAX_WINDOW)
            .map { it.text }
            .toTypedArray()
        if (texts.isEmpty()) return emptyList()
        val context = input.contextBefore.takeLast(RerankLimits.CONTEXT_CHARS)
        return listOf(
            ModelTensor.Strings(
                name = OnnxContract.CONTEXT,
                shape = longArrayOf(1),
                values = arrayOf(context),
            ),
            ModelTensor.Strings(
                name = OnnxContract.CANDIDATES,
                shape = longArrayOf(texts.size.toLong()),
                values = texts,
            ),
        )
    }
}
