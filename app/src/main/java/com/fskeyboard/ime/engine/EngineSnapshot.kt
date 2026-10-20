package com.fskeyboard.ime.engine

import com.fskeyboard.ime.candidate.Candidate

internal data class EngineSnapshot(
    val consumed: Boolean = false,
    val commit: String? = null,
    val preedit: String = "",
    val composingPreview: String = "",
    val candidates: List<Candidate> = emptyList(),
    val highlighted: Int = 0,
    /** Milliseconds spent looking up candidates for this call. */
    val decodeMillis: Long = 0,
) {
    val isComposing: Boolean
        get() = preedit.isNotEmpty() || composingPreview.isNotEmpty()
}
