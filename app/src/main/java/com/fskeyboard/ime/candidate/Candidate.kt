package com.fskeyboard.ime.candidate

internal enum class CandidateSource {
    RIME,
    SYNTHETIC,
}

/**
 * [engineIndex] is librime's index before reranking.
 * A Rime candidate keeps that index so [com.fskeyboard.ime.engine.InputEngine.select]
 * can update the built-in user dictionary.
 * A candidate invented by a reranker uses a negative index and [CandidateSource.SYNTHETIC].
 */
internal data class Candidate(
    val text: String,
    val comment: String = "",
    val engineIndex: Int,
    val source: CandidateSource =
        if (engineIndex >= 0) CandidateSource.RIME else CandidateSource.SYNTHETIC,
)
