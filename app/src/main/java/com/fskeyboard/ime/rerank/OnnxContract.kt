package com.fskeyboard.ime.rerank

/**
 * Tensor names. Character models use [FEATURES] and [MASK]. String models
 * use [CONTEXT] and [CANDIDATES]. Both read one score per candidate from
 * [SCORES]. A new [RerankEncoder] can emit different input names.
 */
internal object OnnxContract {
    const val FEATURES = "features"
    const val MASK = "mask"
    const val CONTEXT = "context"
    const val CANDIDATES = "candidates"
    const val SCORES = "scores"
    const val FEATURE_DIM = 64
}
