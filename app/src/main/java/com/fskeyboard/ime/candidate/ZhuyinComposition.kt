package com.fskeyboard.ime.candidate

import com.fskeyboard.ime.keyboard.ZhuyinComposer
import com.fskeyboard.ime.keyboard.ZhuyinSyllable

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
        get() = chosenText() + pending.text

    fun append(glyph: String): Boolean = pending.append(glyph)

    fun markLevelTone(): Boolean = pending.markLevelTone()

    /**
     * Chooses [candidate] for the front syllables it spans.
     * Returns the finished text when that used the last syllable, and empties the composition.
     */
    fun choose(candidate: Candidate): String? {
        if (pending.isEmpty) return null
        chosen += Choice(candidate.text, pending.take(candidate.span.coerceAtLeast(1)))
        if (!pending.isEmpty) return null
        return chosenText().also { clear() }
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
    fun convertAll(first: Candidate?, candidates: (ZhuyinComposer) -> List<Candidate>): String {
        val out = StringBuilder(chosenText())
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
        return out.toString()
    }

    fun clear() {
        pending.clear()
        chosen.clear()
    }

    private fun chosenText(): String = chosen.joinToString("") { it.text }

    private data class Choice(val text: String, val syllables: List<ZhuyinSyllable>)
}
