package com.fskeyboard.ime.candidate

/**
 * Next words after the Han text at the caret, read from the compiled lexicon's bigram table.
 * The table is `assets/lexicon/lexicon.bin` (FSLX version 2). Pairs are kept in place;
 * only the top few are decoded when the row is idle.
 * A space, no text, or a caret that does not end on a Han character predicts nothing.
 */
internal class WordContinuations private constructor(
    private val data: ByteArray,
    private val words: Int,
    private val pairs: Int,
    private val wordsAt: Int,
    private val pairsAt: Int,
    private val stringsAt: Int,
) {
    /**
     * The most likely words after [before], highest probability first.
     * The context is the longest dictionary word in the Han run touching the caret,
     * and only within the last [CONTEXT_CHARS] characters.
     */
    fun following(before: String, limit: Int = SUGGESTIONS): List<String> {
        if (limit <= 0) return emptyList()
        val word = contextWord(before)
        if (word < 0) return emptyList()
        return successors(word, limit)
    }

    private fun contextWord(before: String): Int {
        if (before.isEmpty() || !isHan(before.codePointBefore(before.length))) return -1
        val starts = ArrayList<Int>(CONTEXT_CHARS)
        var index = before.length
        var taken = 0
        while (index > 0 && taken < CONTEXT_CHARS) {
            val code = before.codePointBefore(index)
            if (!isHan(code)) break
            index -= Character.charCount(code)
            starts.add(index)
            taken += 1
        }
        for (i in starts.indices.reversed()) {
            val found = find(before.substring(starts[i]))
            if (found >= 0) return found
        }
        return -1
    }

    private fun successors(word: Int, limit: Int): List<String> {
        val at = wordsAt + word * WORD
        val first = u32(data, at + 8)
        val count = u32(data, at + 12)
        if (first < 0 || count <= 0 || first.toLong() + count > pairs) return emptyList()
        val ranked = ArrayList<Pair<String, Float>>(count)
        for (slot in first until first + count) {
            val pair = pairsAt + slot * PAIR
            val next = u32(data, pair)
            if (next < 0 || next >= words) continue
            val text = wordText(next)
            if (text.isEmpty() || text == START) continue
            ranked.add(text to Float.fromBits(u32(data, pair + 4)))
        }
        ranked.sortWith(compareByDescending<Pair<String, Float>> { it.second }.thenBy { it.first })
        val end = minOf(limit, ranked.size)
        return List(end) { ranked[it].first }
    }

    /** Words are sorted by UTF-8, which matches [String.compareTo] for this lexicon. */
    private fun find(text: String): Int {
        var low = 0
        var high = words
        while (low < high) {
            val mid = (low + high) ushr 1
            val cmp = wordText(mid).compareTo(text)
            if (cmp < 0) low = mid + 1
            else if (cmp > 0) high = mid
            else return mid
        }
        return -1
    }

    private fun wordText(word: Int): String {
        val at = wordsAt + word * WORD
        val offset = u32(data, at)
        val length = u32(data, at + 4)
        if (offset < 0 || length < 0 || stringsAt.toLong() + offset + length > data.size) return ""
        return String(data, stringsAt + offset, length, UTF_8)
    }

    companion object {
        const val SUGGESTIONS = 8
        private const val CONTEXT_CHARS = 8
        private const val START = "<s>"
        private const val HEADER = 48
        private const val WORD = 16
        private const val PAIR = 8
        private val UTF_8 = Charsets.UTF_8

        /** Null when [data] is not a version-2 lexicon. A bad range fails closed. */
        fun open(data: ByteArray): WordContinuations? {
            if (data.size < HEADER || data[0] != 'F'.code.toByte() || data[1] != 'S'.code.toByte() ||
                data[2] != 'L'.code.toByte() || data[3] != 'X'.code.toByte()
            ) {
                return null
            }
            if (u32(data, 4) != 2) return null
            val counts = IntArray(8)
            for (index in 0 until 8) {
                val count = u32(data, 8 + 4 * index)
                if (count < 0) return null
                counts[index] = count
            }
            val sizes = intArrayOf(20, 16, 16, 8, 16, 8, 4)
            val start = IntArray(8)
            var at = HEADER
            for (index in 0 until 7) {
                start[index] = at
                val bytes = counts[index].toLong() * sizes[index]
                val next = at.toLong() + bytes
                if (bytes < 0 || next > Int.MAX_VALUE) return null
                at = next.toInt()
            }
            start[7] = at
            val stringLength = counts[7]
            if (at.toLong() + stringLength != data.size.toLong()) return null
            val wordCount = counts[4]
            val pairCount = counts[5]
            val wordsAt = start[4]
            for (index in 0 until wordCount) {
                val rec = wordsAt + index * WORD
                val offset = u32(data, rec)
                val length = u32(data, rec + 4)
                val first = u32(data, rec + 8)
                val count = u32(data, rec + 12)
                if (offset < 0 || length < 0 || offset.toLong() + length > stringLength) return null
                if (first < 0 || count < 0 || first.toLong() + count > pairCount) return null
            }
            return WordContinuations(data, wordCount, pairCount, wordsAt, start[5], start[7])
        }
    }
}

private fun u32(data: ByteArray, at: Int): Int {
    return (data[at].toInt() and 0xff) or
        ((data[at + 1].toInt() and 0xff) shl 8) or
        ((data[at + 2].toInt() and 0xff) shl 16) or
        ((data[at + 3].toInt() and 0xff) shl 24)
}

internal fun isHan(code: Int): Boolean {
    return code in 0x3400..0x4DBF ||
        code in 0x4E00..0x9FFF ||
        code in 0xF900..0xFAFF ||
        code in 0x20000..0x3134F
}
