package com.fskeyboard.ime.candidate

/** Prefix hits from [EnglishLexicon.complete]. [words] is most frequent first. */
internal data class EnglishHits(
    val exact: Boolean,
    val words: List<String>,
)

/**
 * A frequency-sorted English word list. [load] keeps words most frequent first,
 * so a prefix scan can stop collecting once it has enough.
 */
internal class EnglishLexicon {
    private val words = ArrayList<String>()
    private val userWords = LinkedHashMap<String, Int>()

    val size: Int
        get() = words.size + userWords.size

    fun load(lines: Iterable<String>) {
        val parsed = ArrayList<Pair<String, Int>>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val tab = trimmed.indexOf('\t')
            if (tab <= 0) continue
            val word = trimmed.substring(0, tab)
            val count = trimmed.substring(tab + 1).trim().toIntOrNull() ?: continue
            if (word.isNotEmpty() && count > 0) parsed.add(word to count)
        }
        parsed.sortWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        words.clear()
        words.addAll(parsed.map { it.first })
    }

    fun loadUser(lines: Iterable<String>) {
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val tab = trimmed.indexOf('\t')
            val word = (if (tab > 0) trimmed.substring(0, tab) else trimmed).lowercase()
            val count = if (tab > 0) trimmed.substring(tab + 1).trim().toIntOrNull() ?: 1 else 1
            if (isValidWord(word) && count > 0) {
                userWords[word] = (userWords[word] ?: 0) + count
            }
        }
    }

    /**
     * Records [word] into the dynamic user dictionary.
     * Returns true if the word was valid and recorded.
     */
    fun learn(word: String): Boolean {
        val key = word.trim().lowercase()
        if (!isValidWord(key)) return false
        val next = (userWords[key] ?: 0) + 1
        userWords[key] = next.coerceAtMost(MAX_COUNT)
        return true
    }

    fun userEntries(): List<Pair<String, Int>> =
        userWords.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }

    /** [prefix] is lowercase. [exact] is set even when the word itself is rarer than [limit] hits. */
    fun complete(prefix: String, limit: Int): EnglishHits {
        if (prefix.isEmpty() || limit <= 0) return EnglishHits(exact = false, words = emptyList())
        var exact = false
        val found = ArrayList<String>(limit)
        val seen = HashSet<String>()

        val userMatches = userWords.entries
            .filter { it.key.startsWith(prefix) }
            .sortedByDescending { it.value }

        for ((word, _) in userMatches) {
            if (word == prefix) exact = true
            if (seen.add(word) && found.size < limit) {
                found.add(word)
            }
        }

        for (word in words) {
            if (!word.startsWith(prefix)) continue
            if (word == prefix) exact = true
            if (seen.add(word) && found.size < limit) {
                found.add(word)
            }
            if (found.size >= limit && exact) break
        }
        return EnglishHits(exact, found)
    }

    companion object {
        const val MAX_COUNT = 100_000

        fun isValidWord(word: String): Boolean {
            if (word.length < 2 || word.length > 40) return false
            var hasLetter = false
            for (ch in word) {
                if (ch.isLetter()) {
                    hasLetter = true
                } else if (ch != '\'' && ch != '-') {
                    return false
                }
            }
            return hasLetter
        }
    }
}

/** What the candidate bar shows. Space and Enter commit the first prediction. */
internal object EnglishSuggest {
    fun candidates(typed: String, hits: EnglishHits, limit: Int): List<String> {
        if (typed.isEmpty() || limit <= 0) return emptyList()
        val cased = hits.words.map { applyCase(typed, it) }
        if (hits.exact) {
            val exact = applyCase(typed, typed.lowercase())
            return buildList {
                add(exact)
                for (word in cased) if (!word.equals(exact, ignoreCase = true)) add(word)
            }.take(limit)
        }
        if (cased.isEmpty()) return listOf(typed)
        return buildList {
            add(cased.first())
            add(typed)
            for (word in cased.drop(1)) if (!word.equals(typed, ignoreCase = true)) add(word)
        }.take(limit)
    }

    /** All caps stays all caps. A single leading capital capitalizes the suggestion. */
    fun applyCase(typed: String, word: String): String {
        val letters = typed.filter { it.isLetter() }
        if (letters.isEmpty()) return word
        if (letters.all { it.isUpperCase() }) return word.uppercase()
        val lead = typed.first()
        if (lead.isLetter() && lead.isUpperCase() && letters.drop(1).all { it.isLowerCase() }) {
            return word.replaceFirstChar { if (it.isLowerCase()) it.uppercaseChar() else it }
        }
        return word
    }

    /**
     * Space and Enter take [prediction] when there is one.
     * Every other key keeps [typed], the letters actually pressed.
     */
    fun commit(typed: String, prediction: String?, takePrediction: Boolean): String {
        if (!takePrediction) return typed
        return prediction?.takeIf { it.isNotEmpty() } ?: typed
    }
}
