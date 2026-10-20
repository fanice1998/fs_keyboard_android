package com.fskeyboard.ime.candidate

import com.fskeyboard.ime.keyboard.KeyboardMode

internal data class CandidateUiState(
    val candidates: List<Candidate> = emptyList(),
    val message: String = "",
)

internal data class ImeUiState(
    val mode: KeyboardMode = KeyboardMode.ZHUYIN,
    val candidates: CandidateUiState = CandidateUiState(),
)
