package com.fskeyboard.ime.candidate

import com.fskeyboard.ime.keyboard.KeyboardMode
import com.fskeyboard.ime.keyboard.NumberPad

internal data class CandidateUiState(
    val candidates: List<Candidate> = emptyList(),
    val message: String = "",
    /** Syllables still open. 0 keeps the accent on the first ordinary chip. */
    val openSyllables: Int = 0,
)

internal data class ImeUiState(
    val mode: KeyboardMode = KeyboardMode.ZHUYIN,
    /** The letter keyboard the symbol pages return to. */
    val base: KeyboardMode = KeyboardMode.ZHUYIN,
    val candidates: CandidateUiState = CandidateUiState(),
    val hasUnconfirmedText: Boolean = false,
    /** Digit-pad extras for [KeyboardMode.NUMBER]. Empty on every other keyboard. */
    val numberPad: NumberPad = NumberPad.NONE,
    /** Chosen Hanzi plus unfinished bopomofo. Underlined in the editor and shown in the bubble. */
    val preview: String = "",
    /** Untyped tail drawn after [preview] on the bubble. The underline does not include it. */
    val previewHint: String = "",
)

/** Symbol pages keep the row clear. Zhuyin and Latin both show what was just looked up. */
internal fun shownCandidates(mode: KeyboardMode, candidates: List<Candidate>): List<Candidate> {
    return if (mode.isLetters) candidates else emptyList()
}
