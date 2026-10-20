package com.fskeyboard.ime.candidate

import com.fskeyboard.ime.engine.EngineSnapshot
import com.fskeyboard.ime.keyboard.ZhuyinComposer
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
 * Shows the lexicon hits for the current reading.
 * An unfinished tone uses the tone-insensitive hits. A chosen tone uses the exact list.
 * The raw bopomofo stays at the end so it can still be committed.
 */
internal fun candidatesFor(composer: ZhuyinComposer, hits: List<Candidate>): List<Candidate> {
    val text = composer.text
    if (text.isEmpty()) return emptyList()
    val queries = composer.queries() ?: return emptyList()
    return CompositionCandidates.supplement(text, queries, hits)
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
