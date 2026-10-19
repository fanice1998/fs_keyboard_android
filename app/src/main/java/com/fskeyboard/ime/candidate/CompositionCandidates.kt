package com.fskeyboard.ime.candidate

import com.fskeyboard.ime.keyboard.ZhuyinReadings

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
