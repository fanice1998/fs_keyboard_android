package com.fskeyboard.ime.candidate

internal enum class CandidateSource {
    RIME,
    SYNTHETIC,

    /** Offered from learned symbol frequency, with nothing being composed. It uses no syllables. */
    SUGGESTION,

    /**
     * The clipboard chip. [Candidate.text] is only the short label.
     * The paste uses the offer cached by the controller.
     */
    CLIPBOARD,
}

/**
 * [engineIndex] is librime's index before reranking.
 * A Rime candidate keeps that index so [com.fskeyboard.ime.engine.InputEngine.select]
 * can update the built-in user dictionary.
 * A candidate invented by a reranker uses a negative index and [CandidateSource.SYNTHETIC].
 * [span] is how many syllables, from the front of the composition, the candidate uses.
 * [reading] is the lexicon reading it was found under; empty for one the lexicon did not give.
 * [clipboardImageUri] is set only for an image clipboard chip, so the row can draw a preview.
 */
internal data class Candidate(
    val text: String,
    val comment: String = "",
    val engineIndex: Int,
    val source: CandidateSource =
        if (engineIndex >= 0) CandidateSource.RIME else CandidateSource.SYNTHETIC,
    val span: Int = 1,
    val reading: String = "",
    val clipboardImageUri: String? = null,
)
