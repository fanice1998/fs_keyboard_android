package com.fskeyboard.ime.candidate

import com.fskeyboard.ime.keyboard.ZhuyinTone

/**
 * One row of ChiaKey `unigrams` or `user_unigrams`.
 * [qstring] is the absolute-order reading, two bytes per syllable.
 * [probability] is the lexicon log score. Higher is better.
 */
internal data class UnigramRow(
    val qstring: String,
    val text: String,
    val probability: Double,
) {
    companion object {
        fun of(text: String, syllables: List<String>, probability: Double): UnigramRow {
            val qstring = BopomofoOrder.qstring(syllables)
                ?: throw IllegalArgumentException("unreadable zhuyin $syllables")
            return UnigramRow(qstring, text, probability)
        }
    }
}

/**
 * Sentence guesses for zhuyin that has not been marked tone by tone.
 * Built from ChiaKey unigrams. A lookup is one hash of the syllables actually typed.
 */
internal class PartialLexicon private constructor(
    private val buckets: Map<String, List<Slot>>,
    val wordCount: Int,
) {
    fun candidates(readings: List<SyllableReading>): List<Candidate> {
        if (readings.isEmpty() || buckets.isEmpty()) return emptyList()
        val bodies = readings.map { it.body }
        val tones = readings.map { it.tone }
        val out = ArrayList<Candidate>(DISPLAY)
        coverAll(bodies, tones)?.let { out += it }
        for (slot in spansFromStart(bodies, tones)) {
            val word = slot.word
            if (out.any { it.text == word.text && it.span == word.syllables.size }) continue
            out += candidate(word, word.syllables.size)
            if (out.size == DISPLAY) break
        }
        return out
    }

    private fun coverAll(bodies: List<String>, tones: List<ZhuyinTone>): Candidate? {
        val count = bodies.size
        val score = DoubleArray(count + 1) { Double.NEGATIVE_INFINITY }
        val text = arrayOfNulls<String>(count + 1)
        val pieces = IntArray(count + 1)
        score[0] = 0.0
        text[0] = ""
        for (start in 0 until count) {
            if (score[start] == Double.NEGATIVE_INFINITY) continue
            val room = minOf(MAX_SYLLABLES, count - start)
            for (length in 1..room) {
                val bucket = buckets[queryKey(bodies, start, length)] ?: continue
                for (slot in bucket) {
                    if (!toneMatches(slot.word, tones, start)) continue
                    val end = start + length
                    val next = score[start] + slot.score
                    val nextPieces = pieces[start] + 1
                    val better = next > score[end] || (next == score[end] && nextPieces < pieces[end])
                    if (!better) continue
                    score[end] = next
                    pieces[end] = nextPieces
                    text[end] = text[start] + slot.word.text
                }
            }
        }
        val phrase = text[count] ?: return null
        if (phrase.isEmpty() || score[count] == Double.NEGATIVE_INFINITY) return null
        return Candidate(text = phrase, engineIndex = -1, source = CandidateSource.SYNTHETIC, span = count)
    }

    /** Single lexicon words that start at the first typed syllable. Longer spans come first. */
    private fun spansFromStart(bodies: List<String>, tones: List<ZhuyinTone>): List<Slot> {
        val found = ArrayList<Slot>()
        val room = minOf(MAX_SYLLABLES, bodies.size)
        for (length in 1..room) {
            val bucket = buckets[queryKey(bodies, 0, length)] ?: continue
            for (slot in bucket) {
                if (!toneMatches(slot.word, tones, 0)) continue
                found += slot
            }
        }
        found.sortWith(compareByDescending<Slot> { it.word.syllables.size }.thenByDescending { it.score })
        return found
    }

    private fun toneMatches(word: Word, tones: List<ZhuyinTone>, start: Int): Boolean {
        for (index in word.syllables.indices) {
            val typed = tones[start + index]
            if (typed != ZhuyinTone.NONE && typed != word.syllables[index].tone) return false
        }
        return true
    }

    private fun candidate(word: Word, span: Int): Candidate {
        return Candidate(text = word.text, engineIndex = -1, source = CandidateSource.SYNTHETIC, span = span)
    }

    private class Word(val text: String, val syllables: List<SyllableReading>, var probability: Double)

    private class Slot(val word: Word, val score: Double)

    class Builder {
        private val words = ArrayList<Word>()
        private val identity = HashMap<String, Int>()

        fun add(qstring: String, text: String, probability: Double) {
            if (text.isEmpty()) return
            val syllables = BopomofoOrder.decode(qstring) ?: return
            if (syllables.size !in 1..MAX_SYLLABLES) return
            val key = text + "\u0000" + qstring
            val existing = identity[key]
            if (existing != null) {
                if (probability > words[existing].probability) words[existing].probability = probability
                return
            }
            identity[key] = words.size
            words += Word(text, syllables, probability)
        }

        fun add(row: UnigramRow) = add(row.qstring, row.text, row.probability)

        fun build(): PartialLexicon {
            val buckets = HashMap<String, Top>()
            for (word in words) {
                for ((key, extra) in keysOf(word.syllables)) {
                    val top = buckets.getOrPut(key) { Top() }
                    top.add(word, word.probability - extra * PENALTY)
                }
            }
            val frozen = HashMap<String, List<Slot>>(buckets.size)
            for ((key, top) in buckets) frozen[key] = top.freeze()
            return PartialLexicon(frozen, words.size)
        }
    }

    private class Top {
        private val items = ArrayList<Slot>(PER_KEY)

        fun add(word: Word, score: Double) {
            if (items.size == PER_KEY) {
                var worst = 0
                for (index in 1 until items.size) {
                    if (items[index].score < items[worst].score) worst = index
                }
                if (score <= items[worst].score) return
                items[worst] = Slot(word, score)
            } else {
                items += Slot(word, score)
            }
        }

        fun freeze(): List<Slot> = items.sortedByDescending { it.score }
    }

    companion object {
        val EMPTY = PartialLexicon(emptyMap(), 0)

        /** Log-score penalty for a consonant, medial, or vowel the user did not type. */
        private const val PENALTY = 2.0
        private const val DISPLAY = 48
        private const val PER_KEY = 64

        /**
         * A word longer than this stays out of the index.
         * The toned walker still sees it. Four syllables is the relief valve that
         * keeps this table under the resident cap on a full ChiaKey unigram scan.
         */
        private const val MAX_SYLLABLES = 4
        private const val KEY_GAP = '\u0001'

        fun index(rows: Iterable<UnigramRow>): PartialLexicon {
            val builder = Builder()
            for (row in rows) builder.add(row)
            return builder.build()
        }

        private fun queryKey(bodies: List<String>, start: Int, length: Int): String {
            return bodies.subList(start, start + length).joinToString(KEY_GAP.toString())
        }

        private fun keysOf(syllables: List<SyllableReading>): List<Pair<String, Int>> {
            val prefixes = syllables.map { prefixesOf(it.body) }
            val out = ArrayList<Pair<String, Int>>()
            val key = StringBuilder()
            fun walk(index: Int, extra: Int) {
                if (index == syllables.size) {
                    out += key.toString() to extra
                    return
                }
                val base = key.length
                for ((prefix, prefixExtra) in prefixes[index]) {
                    if (index > 0) key.append(KEY_GAP)
                    key.append(prefix)
                    walk(index + 1, extra + prefixExtra)
                    key.setLength(base)
                }
            }
            walk(0, 0)
            return out
        }

        private fun prefixesOf(body: String): List<Pair<String, Int>> {
            val out = ArrayList<Pair<String, Int>>(body.length)
            for (length in 1..body.length) {
                out += body.substring(0, length) to (body.length - length)
            }
            return out
        }
    }
}
