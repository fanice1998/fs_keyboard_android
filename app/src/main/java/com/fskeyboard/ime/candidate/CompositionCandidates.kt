package com.fskeyboard.ime.candidate

import com.fskeyboard.ime.engine.EngineSnapshot
import com.fskeyboard.ime.keyboard.ZhuyinReadings
import com.fskeyboard.ime.keyboard.ZhuyinSpelling

/**
 * Keeps engine candidates first.
 * A single syllable with no engine hit still offers its character, and the raw
 * bopomofo is always available so a reading can be committed as zhuyin.
 */
internal object CompositionCandidates {
    fun supplement(
        composition: String,
        syllables: List<String>,
        existing: List<Candidate>,
    ): List<Candidate> {
        if (composition.isEmpty()) return existing
        val seen = existing.map { it.text }.toMutableSet()
        val extra = ArrayList<Candidate>()
        fun add(text: String) {
            if (text.isEmpty() || text in seen) return
            seen += text
            extra += Candidate(text = text, engineIndex = -(extra.size + 1))
        }
        if (syllables.size == 1) {
            ZhuyinReadings.characters(syllables[0]).forEach(::add)
        }
        add(composition)
        return existing + extra
    }
}

/**
 * The engine preview is the ASCII key spelling. The on-screen composition stays
 * the bopomofo the user typed, including when 臺/通 is toggled mid-composition.
 */
internal fun EngineSnapshot.preservingComposition(
    composition: String,
    syllables: List<String>,
): EngineSnapshot {
    return copy(
        commit = commit?.takeUnless { ZhuyinSpelling.isKeySpelling(it) },
        preedit = composition,
        composingPreview = composition,
        candidates = CompositionCandidates.supplement(composition, syllables, candidates),
    )
}
