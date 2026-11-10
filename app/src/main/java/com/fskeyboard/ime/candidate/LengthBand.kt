package com.fskeyboard.ime.candidate

/**
 * Stable bands after one preference pass.
 * <= 0 keeps [items]. == 1 groups by [Candidate.extra] only. >= 2 is sentence, word, then character.
 * Inside a group, lower extra comes first and a created row leads that extra, not a lower one.
 */
internal object LengthBand {
    fun order(
        items: List<Candidate>,
        syllables: Int,
        isCreated: (Candidate) -> Boolean,
    ): List<Candidate> {
        if (syllables <= 0 || items.size < 2) return items
        // false sorts first, so created leads its extra group. Created alone would cross extra.
        val byExtraThenCreated = compareBy<Candidate> { it.extra }.thenBy { !isCreated(it) }
        if (syllables == 1) return items.sortedWith(byExtraThenCreated)
        val sentence = ArrayList<Candidate>()
        val word = ArrayList<Candidate>()
        val character = ArrayList<Candidate>()
        for (item in items) {
            when {
                item.span >= syllables -> sentence += item
                item.span > 1 -> word += item
                else -> character += item
            }
        }
        return sentence.sortedWith(byExtraThenCreated) +
            word.sortedWith(byExtraThenCreated) +
            character.sortedWith(byExtraThenCreated)
    }
}
