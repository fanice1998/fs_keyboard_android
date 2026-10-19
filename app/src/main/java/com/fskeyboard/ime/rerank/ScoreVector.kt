package com.fskeyboard.ime.rerank

/**
 * Reads an ONNX score tensor of shape [N], [1, N], or [1, 1, N] into a
 * vector aligned with the candidate window.
 */
internal fun readScoreVector(raw: Any?): FloatArray {
    return when (raw) {
        is FloatArray -> raw.copyOf()
        is Array<*> -> unwrapFloatRow(raw) ?: error("unsupported score tensor")
        else -> error("unsupported score tensor")
    }
}

private fun unwrapFloatRow(raw: Array<*>): FloatArray? {
    if (raw.isEmpty()) return floatArrayOf()
    return when (val child = raw[0]) {
        is FloatArray -> child.copyOf()
        is Array<*> -> unwrapFloatRow(child)
        else -> null
    }
}
