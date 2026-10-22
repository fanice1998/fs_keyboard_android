package com.fskeyboard.ime.candidate

/**
 * Symbols the user has typed after a word, counted per suffix of that word.
 * A longer suffix weighs more, so a habit on「你好嗎」outranks a habit on「嗎」
 * unless the shorter one has been used enough times to pass it.
 * Nothing is predicted when [before] is empty or ends with a space.
 */
internal class SymbolFrequency {
    private val counts = HashMap<String, HashMap<String, Int>>()

    /** Records [symbol] after [before]. False when there is no word for it to follow. */
    fun learn(before: String, symbol: String): Boolean {
        if (!isSymbol(symbol)) return false
        val word = trailingWord(before) ?: return false
        for (suffix in suffixes(word)) {
            val row = counts.getOrPut(suffix) { HashMap() }
            row[symbol] = (row[symbol] ?: 0) + 1
        }
        trim()
        return true
    }

    /** Symbols for the word touching the caret, most weight first. Empty after a space or no text. */
    fun predict(before: String, limit: Int = SUGGESTIONS): List<String> {
        val word = trailingWord(before) ?: return emptyList()
        val scores = HashMap<String, Int>()
        for (suffix in suffixes(word)) {
            val row = counts[suffix] ?: continue
            for ((symbol, count) in row) {
                scores[symbol] = (scores[symbol] ?: 0) + count * suffix.length
            }
        }
        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key }
    }

    fun load(lines: List<String>) {
        counts.clear()
        for (line in lines) {
            val parts = line.split('\t')
            if (parts.size != 3) continue
            val count = parts[2].toIntOrNull() ?: continue
            if (count <= 0 || parts[0].isEmpty() || !isSymbol(parts[1])) continue
            counts.getOrPut(parts[0]) { HashMap() }[parts[1]] = count
        }
    }

    fun serialize(): List<String> {
        return counts.entries
            .sortedBy { it.key }
            .flatMap { (context, row) ->
                row.entries.sortedBy { it.key }.map { (symbol, count) -> "$context\t$symbol\t$count" }
            }
    }

    private fun trim() {
        if (counts.size <= MAX_KEYS) return
        val ranked = counts.entries.sortedWith(
            compareBy<Map.Entry<String, HashMap<String, Int>>> { entry -> entry.value.values.sum() }
                .thenBy { it.key },
        )
        var extra = counts.size - MAX_KEYS
        for (entry in ranked) {
            if (extra <= 0) break
            counts.remove(entry.key)
            extra -= 1
        }
    }

    private companion object {
        const val MAX_CONTEXT = 8
        const val MAX_KEYS = 400
        const val SUGGESTIONS = 8
    }
}

/** Punctuation and other marks. Letters, digits, and Han characters are words, not symbols. */
internal fun isSymbol(text: String): Boolean {
    if (text.isEmpty()) return false
    var index = 0
    while (index < text.length) {
        val code = text.codePointAt(index)
        if (Character.isWhitespace(code) || Character.isLetterOrDigit(code)) return false
        index += Character.charCount(code)
    }
    return true
}

/**
 * The word touching the end of [before].
 * Symbols after that word are skipped, so「你好嗎？」still learns「你好嗎」.
 * Empty when the caret has no text, or the last character is a space.
 */
internal fun trailingWord(before: String): String? {
    if (before.isEmpty()) return null
    if (Character.isWhitespace(before.codePointBefore(before.length))) return null
    var end = before.length
    while (end > 0) {
        val code = before.codePointBefore(end)
        if (!isSymbolCodePoint(code)) break
        end -= Character.charCount(code)
    }
    if (end == 0 || Character.isWhitespace(before.codePointBefore(end))) return null
    var start = end
    while (start > 0) {
        val code = before.codePointBefore(start)
        if (Character.isWhitespace(code) || isSymbolCodePoint(code)) break
        start -= Character.charCount(code)
    }
    if (start == end) return null
    return takeLastCodePoints(before.substring(start, end), 8)
}

private fun isSymbolCodePoint(code: Int): Boolean {
    return !Character.isWhitespace(code) && !Character.isLetterOrDigit(code)
}

private fun suffixes(word: String): List<String> {
    val out = ArrayList<String>()
    var index = 0
    while (index < word.length) {
        out.add(word.substring(index))
        index += Character.charCount(word.codePointAt(index))
    }
    return out
}

private fun takeLastCodePoints(text: String, max: Int): String {
    val count = text.codePointCount(0, text.length)
    if (count <= max) return text
    return text.substring(text.offsetByCodePoints(text.length, -max))
}
