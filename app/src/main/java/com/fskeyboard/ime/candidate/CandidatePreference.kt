package com.fskeyboard.ime.candidate

/**
 * One step of display rank for a reading and a word, kept beside the lexicon.
 * Hidden words stay out of the row. The lexicon file itself is left alone.
 * [Pref.created] marks a word the user added. [Pref.shows] counts how many times
 * that memory was offered and not chosen. A later choice clears the count.
 */
internal class CandidatePreference {
    private val entries = LinkedHashMap<String, Pref>()

    fun remembersAny(): Boolean = entries.isNotEmpty()

    fun load(lines: Iterable<String>) {
        entries.clear()
        for (line in lines) {
            val parts = line.trim().split('\t')
            if (parts.size != 4 && parts.size != 6) continue
            val reading = parts[0]
            val text = parts[1]
            if (reading.indexOf('\n') >= 0 || text.indexOf('\n') >= 0) continue
            val steps = parts[2].toIntOrNull() ?: continue
            val hidden = parts[3] == "1"
            val created = parts.size == 6 && parts[4] == "1"
            val shows = if (parts.size == 6) parts[5].toIntOrNull() ?: continue else 0
            if (steps == 0 && !hidden && !created && shows == 0) continue
            entries[key(reading, text)] = Pref(
                steps.coerceIn(-CAP, CAP),
                hidden,
                created,
                shows.coerceAtLeast(0),
            )
        }
    }

    fun serialize(): List<String> {
        val lines = ArrayList<String>()
        for ((id, pref) in entries) {
            if (pref.steps == 0 && !pref.hidden && !pref.created && pref.shows == 0) continue
            val tab = id.indexOf('\t')
            if (tab < 0 || tab == id.lastIndex) continue
            val reading = id.substring(0, tab)
            val text = id.substring(tab + 1)
            val hidden = if (pref.hidden) 1 else 0
            val base = "$reading\t$text\t${pref.steps}\t$hidden"
            lines += if (pref.created || pref.shows != 0) {
                "$base\t${if (pref.created) 1 else 0}\t${pref.shows}"
            } else {
                base
            }
        }
        return lines
    }

    fun promote(candidate: Candidate) = step(candidate, 1)

    fun demote(candidate: Candidate) = step(candidate, -1)

    /** Keeps a word the user added. A later [promote] still raises it. */
    fun remember(candidate: Candidate) {
        val id = key(candidate) ?: return
        val current = entries[id]
        entries[id] = Pref(
            steps = current?.steps ?: 0,
            hidden = false,
            created = true,
            shows = 0,
        )
    }

    fun isCreated(candidate: Candidate): Boolean {
        val id = key(candidate) ?: return false
        return entries[id]?.created == true
    }

    /** Phrases the user assembled for this exact reading. Hidden ones stay out. */
    fun createdMatching(reading: String): List<Candidate> {
        if (reading.isEmpty() || entries.isEmpty()) return emptyList()
        val prefix = "$reading\t"
        val out = ArrayList<Candidate>()
        for ((id, pref) in entries) {
            if (!pref.created || pref.hidden) continue
            if (!id.startsWith(prefix)) continue
            val text = id.substring(prefix.length)
            if (text.isEmpty()) continue
            out += Candidate(
                text = text,
                engineIndex = -1,
                source = CandidateSource.SYNTHETIC,
                reading = reading,
            )
        }
        return out
    }

    /**
     * Removes a word the user added. A base-lexicon row is left in place,
     * including one whose rank was only raised or lowered.
     */
    fun forget(candidate: Candidate): Boolean {
        val id = key(candidate) ?: return false
        val current = entries[id] ?: return false
        if (!current.created) return false
        entries.remove(id)
        return true
    }

    fun hide(candidate: Candidate) {
        val id = key(candidate) ?: return
        val current = entries[id]
        entries[id] = Pref(
            current?.steps ?: 0,
            hidden = true,
            created = current?.created == true,
            shows = current?.shows ?: 0,
        )
    }

    /**
     * Counts one showing of each remembered row in [shown].
     * The 21st show drops that memory when [dropUnused] is set.
     * Returns whether the file should be written again.
     */
    fun noteShown(shown: List<Candidate>, dropUnused: Boolean): Boolean {
        if (entries.isEmpty() || shown.isEmpty()) return false
        var changed = false
        for (candidate in shown) {
            val id = key(candidate) ?: continue
            val current = entries[id] ?: continue
            if (current.hidden) continue
            if (current.steps == 0 && !current.created && current.shows == 0) continue
            val shows = current.shows + 1
            changed = true
            if (dropUnused && shows > UNCHOSEN_SHOWS) {
                entries.remove(id)
            } else {
                entries[id] = current.copy(shows = shows)
            }
        }
        return changed
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
        val created = current?.created == true
        // Choosing the word clears the unused-show count and still moves it up.
        val shows = if (delta > 0) 0 else current?.shows ?: 0
        if (steps == 0 && !created && shows == 0) {
            entries.remove(id)
        } else {
            entries[id] = Pref(steps, hidden = false, created = created, shows = shows)
        }
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

    private data class Pref(
        val steps: Int,
        val hidden: Boolean,
        val created: Boolean = false,
        val shows: Int = 0,
    )

    private companion object {
        const val CAP = 64

        /** Shown this many times without a choice, the next show drops the memory. */
        const val UNCHOSEN_SHOWS = 20
    }
}

internal enum class CandidateWeight {
    PROMOTE,
    DEMOTE,
    HIDE,
}
