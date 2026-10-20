package com.fskeyboard.ime.candidate

import com.fskeyboard.ime.keyboard.ZhuyinComposer
import com.fskeyboard.ime.keyboard.ZhuyinSyllable

/**
 * What a finished composition commits, and the phrases to [com.fskeyboard.ime.engine.ZhuyinLexicon.learn]
 * from it as (reading, text): each two words the user chose one after the other.
 */
internal data class Commit(val text: String, val learned: List<Pair<String, String>> = emptyList())

/**
 * Zhuyin still being typed, plus the words already chosen for its front syllables.
 * Chosen words stay in the composition until every syllable is chosen.
 * Backspace edits the zhuyin first; with none left it turns the last chosen word
 * back into the zhuyin it was typed with.
 */
internal class ZhuyinComposition {
    /** The syllables not chosen yet. Candidates are looked up for its front. */
    val pending = ZhuyinComposer()
    private val chosen = ArrayList<Choice>()

    val isEmpty: Boolean
        get() = pending.isEmpty && chosen.isEmpty()

    val text: String
        get() = chosenText + pending.text

    /** The words chosen so far, in order. */
    val chosenText: String
        get() = chosen.joinToString("") { it.text }

    fun append(glyph: String): Boolean = pending.append(glyph)

    fun markLevelTone(): Boolean = pending.markLevelTone()

    /**
     * Chooses [candidate] for the front syllables it spans.
     * Returns the commit when that used the last syllable, and empties the composition.
     */
    fun choose(candidate: Candidate): Commit? {
        if (pending.isEmpty) return null
        chosen += Choice(candidate.text, candidate.reading, pending.take(candidate.span.coerceAtLeast(1)))
        if (!pending.isEmpty) return null
        return Commit(chosenText, learned()).also { clear() }
    }

    fun backspace() {
        if (!pending.isEmpty) {
            pending.backspace()
        } else if (chosen.isNotEmpty()) {
            pending.restore(chosen.removeAt(chosen.lastIndex).syllables)
        }
    }

    /**
     * The chosen words, then the rest front to back with the first candidate each time,
     * and empties the composition. [first] is the candidate already on screen, if any.
     */
    fun convertAll(first: Candidate?, candidates: (ZhuyinComposer) -> List<Candidate>): Commit {
        val learned = learned()
        val out = StringBuilder(chosenText)
        var pick = first
        while (!pending.isEmpty) {
            val candidate = pick ?: candidates(pending).firstOrNull()
            if (candidate == null) {
                out.append(pending.text)
                break
            }
            out.append(candidate.text)
            pending.take(candidate.span.coerceAtLeast(1))
            pick = null
        }
        clear()
        return Commit(out.toString(), learned)
    }

    fun clear() {
        pending.clear()
        chosen.clear()
    }

    /** Each two words chosen one after the other, while both came from the lexicon and fit a phrase. */
    private fun learned(): List<Pair<String, String>> {
        return chosen.zipWithNext().mapNotNull { (left, right) ->
            if (left.reading.isEmpty() || right.reading.isEmpty()) return@mapNotNull null
            val reading = "${left.reading} ${right.reading}"
            if (reading.split(' ').size > MAX_LEARNED_SYLLABLES) return@mapNotNull null
            reading to left.text + right.text
        }
    }

    private data class Choice(val text: String, val reading: String, val syllables: List<ZhuyinSyllable>)

    private companion object {
        /** The lexicon's longest phrase. */
        const val MAX_LEARNED_SYLLABLES = 8
    }
}
