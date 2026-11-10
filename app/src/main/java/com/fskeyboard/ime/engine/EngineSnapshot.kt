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
    /** Typed syllables. <= 0: LengthBand.order returns the list unchanged. */
    val readingSyllables: Int = 0,
    /** 靈犀 already ordered this row. Preference, length bands, and the other reranker leave it alone. */
    val rankLocked: Boolean = false,
) {
    val isComposing: Boolean
        get() = preedit.isNotEmpty() || composingPreview.isNotEmpty()
}
