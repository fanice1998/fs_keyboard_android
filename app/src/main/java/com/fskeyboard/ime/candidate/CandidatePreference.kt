package com.fskeyboard.ime.candidate

/**
 * One step of display rank for a reading and a word, kept beside the lexicon.
 * Hidden words stay out of the row. The lexicon file itself is left alone.
 */
internal class CandidatePreference {
    private val entries = LinkedHashMap<String, Pref>()

    fun load(lines: Iterable<String>) {
        entries.clear()
        for (line in lines) {
            val parts = line.trim().split('\t')
            if (parts.size != 4) continue
            val reading = parts[0]
            val text = parts[1]
            if (reading.indexOf('\n') >= 0 || text.indexOf('\n') >= 0) continue
            val steps = parts[2].toIntOrNull() ?: continue
            val hidden = parts[3] == "1"
            if (steps == 0 && !hidden) continue
            entries[key(reading, text)] = Pref(steps.coerceIn(-CAP, CAP), hidden)
        }
    }

    fun serialize(): List<String> {
        val lines = ArrayList<String>()
        for ((id, pref) in entries) {
            if (pref.steps == 0 && !pref.hidden) continue
            val tab = id.indexOf('\t')
            if (tab < 0 || tab == id.lastIndex) continue
            val reading = id.substring(0, tab)
            val text = id.substring(tab + 1)
            lines += "$reading\t$text\t${pref.steps}\t${if (pref.hidden) 1 else 0}"
        }
        return lines
    }

    fun promote(candidate: Candidate) = step(candidate, 1)

    fun demote(candidate: Candidate) = step(candidate, -1)

    fun hide(candidate: Candidate) {
        val id = key(candidate) ?: return
        val current = entries[id]
        entries[id] = Pref(current?.steps ?: 0, hidden = true)
    }

    /**
     * Drops hidden rows, then moves each remaining word by its steps.
     * The same sort key keeps the word with more steps ahead, so one step
     * swaps it with the neighbour.
     */
    fun apply(candidates: List<Candidate>): List<Candidate> {
        if (entries.isEmpty() || candidates.isEmpty()) return candidates
        val kept = ArrayList<Candidate>(candidates.size)
        for (candidate in candidates) {
            if (candidate.source == CandidateSource.CLIPBOARD) {
                kept += candidate
                continue
            }
            val id = key(candidate)
            if (id != null && entries[id]?.hidden == true) continue
            kept += candidate
        }
        if (kept.size < 2) return kept
        return kept.withIndex().sortedWith(
            compareBy<IndexedValue<Candidate>> { indexed -> indexed.index - stepsOf(indexed.value) }
                .thenByDescending { indexed -> stepsOf(indexed.value) }
                .thenBy { indexed -> indexed.index },
        ).map { it.value }
    }

    private fun step(candidate: Candidate, delta: Int) {
        val id = key(candidate) ?: return
        val current = entries[id]
        if (current?.hidden == true) return
        val steps = ((current?.steps ?: 0) + delta).coerceIn(-CAP, CAP)
        if (steps == 0) entries.remove(id) else entries[id] = Pref(steps, hidden = false)
    }

    private fun stepsOf(candidate: Candidate): Int {
        val id = key(candidate) ?: return 0
        return entries[id]?.steps ?: 0
    }

    private fun key(candidate: Candidate): String? {
        if (candidate.source == CandidateSource.CLIPBOARD) return null
        if (candidate.text.isEmpty() || candidate.text.indexOf('\t') >= 0) return null
        if (candidate.reading.indexOf('\t') >= 0) return null
        return key(candidate.reading, candidate.text)
    }

    private fun key(reading: String, text: String): String = "$reading\t$text"

    private data class Pref(val steps: Int, val hidden: Boolean)

    private companion object {
        const val CAP = 64
    }
}

internal enum class CandidateWeight {
    PROMOTE,
    DEMOTE,
    HIDE,
}
