package com.fskeyboard.ime.candidate

/**
 * Tap and space both go through this policy.
 * Only an untouched Rime candidate is selected by engine index, which is what
 * makes librime record the phrase in the user dictionary.
 */
internal object SelectionPolicy {
    sealed interface Decision {
        data class SelectEngine(val engineIndex: Int) : Decision
        data class CommitSynthetic(val text: String) : Decision
    }

    fun decide(candidate: Candidate): Decision {
        return if (candidate.source == CandidateSource.RIME && candidate.engineIndex >= 0) {
            Decision.SelectEngine(candidate.engineIndex)
        } else {
            Decision.CommitSynthetic(candidate.text)
        }
    }
}
