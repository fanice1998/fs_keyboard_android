package com.fskeyboard.ime.candidate

internal enum class CandidateSource {
    RIME,
    SYNTHETIC,

    /**
     * Offered with nothing being composed. It uses no syllables.
     * A Han word is the next word after the caret; anything else is a learned symbol.
     */
    SUGGESTION,

    /**
     * The clipboard chip. [Candidate.text] is only the short label.
     * The paste uses the offer cached by the controller.
     */
    CLIPBOARD,
}

/**
 * [engineIndex] is the zhuyin engine's index before reranking.
 * A candidate keeps that index so [com.fskeyboard.ime.engine.ZhuyinEngine.select]
 * can apply the choice.
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
    /** Unmatched onset, medial, or vowel. Lower is a fuller reading. */
    val extra: Int = 0,
    /**
     * A longer lexicon word on the left of the homophone row.
     * [text] is the whole word a tap commits. The chip draws [labelReading] and then [labelTail].
     */
    val completion: Boolean = false,
    /** Typed bopomofo drawn on a completion chip. Empty on every other row. */
    val labelReading: String = "",
    /** Predicted Hanzi drawn after [labelReading] in the hint color. */
    val labelTail: String = "",
)

/** The chip Enter commits: it covers every open syllable and is not the prediction. */
internal fun Candidate.coversOpenSyllables(openSyllables: Int): Boolean {
    return openSyllables > 0 &&
        !completion &&
        source != CandidateSource.CLIPBOARD &&
        text.isNotEmpty() &&
        span == openSyllables
}

/** Index of the chip drawn with the accent. -1 when this row has nothing to emphasize. */
internal fun accentIndex(candidates: List<Candidate>, openSyllables: Int): Int {
    if (openSyllables > 0) return candidates.indexOfFirst { it.coversOpenSyllables(openSyllables) }
    return candidates.indexOfFirst {
        it.source != CandidateSource.CLIPBOARD && !it.completion && it.text.isNotEmpty()
    }
}

/** Bopomofo plus the gray tail on a completion chip. Other chips keep their Hanzi label. */
internal fun Candidate.visibleLabel(): String {
    if (labelReading.isNotEmpty()) return labelReading + labelTail
    return if (comment.isEmpty()) text else "$text $comment"
}
