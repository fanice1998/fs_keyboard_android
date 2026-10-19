package com.fskeyboard.ime.candidate

import com.fskeyboard.ime.keyboard.KeyboardMode

internal data class CandidateUiState(
    val preedit: String = "",
    val candidates: List<Candidate> = emptyList(),
    val message: String = "",
    val traditional: Boolean = true,
)

internal data class ImeUiState(
    val mode: KeyboardMode = KeyboardMode.ZHUYIN,
    val candidates: CandidateUiState = CandidateUiState(),
)
