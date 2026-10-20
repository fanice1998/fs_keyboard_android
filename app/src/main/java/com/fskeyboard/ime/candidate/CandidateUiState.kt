package com.fskeyboard.ime.candidate

import com.fskeyboard.ime.keyboard.KeyboardMode

internal data class CandidateUiState(
    val candidates: List<Candidate> = emptyList(),
    val message: String = "",
)

internal data class ImeUiState(
    val mode: KeyboardMode = KeyboardMode.ZHUYIN,
    /** The letter keyboard the symbol pages return to. */
    val base: KeyboardMode = KeyboardMode.ZHUYIN,
    val candidates: CandidateUiState = CandidateUiState(),
)

/** Symbol pages keep the row clear. Zhuyin and Latin both show what was just looked up. */
internal fun shownCandidates(mode: KeyboardMode, candidates: List<Candidate>): List<Candidate> {
    return if (mode.isLetters) candidates else emptyList()
}
