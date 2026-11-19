package com.fskeyboard.ime.lingxi

import java.io.File
import java.util.zip.CRC32
import kotlin.math.ln
import kotlin.math.pow

/** Counts that the public pack must have. A zero refuses the 靈犀 path. */
internal data class LingxiCensus(
    val characters: Int,
    val words: Int,
    val unigrams: Int,
    val multiCharBigrams: Int,
    val singleCharBigrams: Int,
    val englishWords: Int,
) {
    fun requireFull() {
        require(characters > 0) { "lingxi character entries empty" }
        require(words > 0) { "lingxi word entries empty" }
        require(unigrams > 0) { "lingxi unigrams empty" }
        require(multiCharBigrams > 0) { "lingxi multi-character bigrams empty" }
        require(singleCharBigrams > 0) { "lingxi single-character bigrams empty" }
        require(englishWords > 0) { "lingxi english subpack empty" }
    }
}

internal class LexHit(
    val surface: String,
    val prw: Float,
    val rare: Boolean,
    val supplement: Boolean = false,
    /** Letters in the lexicon syllable that the typed body did not include. */
    val extra: Int = 0,
)

/** Natural-log penalty for one onset, medial, or vowel the user has not typed yet. */
internal const val LINGXI_EXTRA_PENALTY = 2.0

/** A lexicon word whose reading is strictly longer than the typed prefix. */
internal class LongHit(
    val surface: String,
    val prw: Float,
    val syllables: Int,
    val reading: String,
)

/**
 * Compact lexicon (`ZUINLEX`). Entries stay in the file layout.
 * A fully toned reading binary-searches the reading-id sequence.
 * An untoned reading binary-searches [byBody], the same entries ordered by
 * tone-stripped bodies, so a long phrase is one range instead of five tones
 * raised to the syllable count.
 */
internal class LingxiLexicon(
    private val syllableIds: HashMap<String, Int>,
    private val syllableForms: Array<String>,
    private val rd: ShortArray,
    private val rdOff: IntArray,
    private val sfOff: IntArray,
    private val sfLen: IntArray,
    private val rdLen: ByteArray,
    private val flags: ByteArray,
    private val prw: FloatArray,
    private val strs: ByteArray,
    private val byText: IntArray,
    val supplementMask: Int = LINGXI_SUPPLEMENT_FLAG,
) {
    val entryCount: Int get() = rdOff.size

    /** Syllable id to tone-stripped body id. Body ids follow the body text. */
    private val bodyOfSyllable: IntArray

    /** Tone-stripped bopomofo body to [bodyOfSyllable]. Ids follow the body text, so one prefix is one range. */
    private val bodyIds: HashMap<String, Int>

    /** Body text in [bodyIds] order. */
    private val bodyForms: Array<String>

    /** Entry indexes sorted by tone-stripped reading, then by syllable count. */
    private val byBody: IntArray

    init {
        val forms = ArrayList<String>()
        val seen = HashMap<String, Int>(syllableForms.size)
        for (form in syllableForms) {
            val body = form.trimEnd('ˊ', 'ˇ', 'ˋ', '˙')
            if (seen.containsKey(body)) continue
            seen[body] = forms.size
            forms += body
        }
        val order = forms.indices.sortedBy { forms[it] }
        val ranked = Array(forms.size) { "" }
        val rankedIds = HashMap<String, Int>(seen.size)
        for ((rank, old) in order.withIndex()) {
            ranked[rank] = forms[old]
            rankedIds[forms[old]] = rank
        }
        bodyForms = ranked
        bodyIds = rankedIds
        bodyOfSyllable = IntArray(syllableForms.size) { id ->
            rankedIds.getValue(syllableForms[id].trimEnd('ˊ', 'ˇ', 'ˋ', '˙'))
        }
        val boxed = Array(entryCount) { it }
        boxed.sortWith { left, right -> compareBodyEntries(left, right) }
        byBody = IntArray(entryCount) { boxed[it] }
    }

    /**
     * [openTone] marks syllables whose tone was not chosen. Those match every
     * tone of the same body. A chosen 平聲 stays the unmarked reading only.
     */
    fun exact(
        syllables: List<String>,
        openTone: BooleanArray = BooleanArray(0),
        includeSupplements: Boolean = true,
    ): List<LexHit> {
        if (syllables.isEmpty()) return emptyList()
        val open = openTone.size == syllables.size && openTone.any { it }
        if (!open) {
            if (queryIds(syllables) != null) return exactFixed(syllables, includeSupplements)
            val tones = if (openTone.size == syllables.size) openTone else BooleanArray(syllables.size) { true }
            return prefixHits(syllables, tones, includeSupplements)
        }
        if (bodyQuery(syllables) != null) return exactOpen(syllables, openTone, includeSupplements)
        return prefixHits(syllables, openTone, includeSupplements)
    }

    private fun queryIds(syllables: List<String>): IntArray? {
        val query = IntArray(syllables.size)
        for (index in syllables.indices) {
            query[index] = syllableIds[syllables[index]] ?: return null
        }
        return query
    }

    private fun longerFixed(query: IntArray, limit: Int, deadlineNs: Long): List<LongHit> {
        var lo = 0
        var hi = entryCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareReading(mid, query) < 0) lo = mid + 1 else hi = mid
        }
        val out = ArrayList<LongHit>(limit)
        var seen = 0
        var stepped = 0
        while (lo < entryCount && seen < limit) {
            // Thousands of supplements can sit in front of one real longer word.
            if ((stepped and 31) == 0 && past(deadlineNs)) break
            stepped++
            if (!startsWith(lo, query)) break
            val len = rdLen[lo].toInt() and 0xFF
            // Supplements have no bigram. Counting them would push a real prediction out of the 32.
            if (len > query.size && !isSupplement(lo)) {
                seen++
                val reading = readingOf(lo, len)
                if (reading != null) out += LongHit(surfaceOf(lo), prw[lo], len, reading)
            }
            lo++
        }
        return out
    }

    private fun startsWith(index: Int, query: IntArray): Boolean {
        val len = rdLen[index].toInt() and 0xFF
        if (len < query.size) return false
        val off = rdOff[index]
        for (step in query.indices) {
            if ((rd[off + step].toInt() and 0xFFFF) != query[step]) return false
        }
        return true
    }

    private fun readingOf(index: Int, len: Int): String? {
        val off = rdOff[index]
        val reading = StringBuilder()
        for (step in 0 until len) {
            val id = rd[off + step].toInt() and 0xFFFF
            val form = syllableForms.getOrNull(id) ?: return null
            reading.append(form)
        }
        return reading.toString()
    }

    private fun surfaceOf(index: Int): String {
        return String(strs, sfOff[index], sfLen[index], Charsets.UTF_8)
    }

    private fun past(deadlineNs: Long): Boolean {
        return deadlineNs != Long.MAX_VALUE && System.nanoTime() > deadlineNs
    }

    private fun exactFixed(syllables: List<String>, includeSupplements: Boolean): List<LexHit> {
        val query = IntArray(syllables.size)
        for (index in syllables.indices) {
            query[index] = syllableIds[syllables[index]] ?: return emptyList()
        }
        var lo = 0
        var hi = entryCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareReading(mid, query) < 0) lo = mid + 1 else hi = mid
        }
        val out = ArrayList<LexHit>()
        while (lo < entryCount && compareReading(lo, query) == 0) {
            if (includeSupplements || !isSupplement(lo)) out += hit(lo)
            lo++
        }
        return out
    }

    /**
     * Words whose reading starts with [syllables] and continues.
     * At most [limit] strictly longer entries are examined.
     * Open tones share one toneless range. A deadline already passed stops the walk.
     */
    fun longer(
        syllables: List<String>,
        openTone: BooleanArray = BooleanArray(0),
        limit: Int = 32,
        deadlineNs: Long = Long.MAX_VALUE,
    ): List<LongHit> {
        if (syllables.isEmpty() || limit <= 0) return emptyList()
        if (past(deadlineNs)) return emptyList()
        val open = openTone.size == syllables.size && openTone.any { it }
        if (!open) {
            val query = queryIds(syllables) ?: return emptyList()
            return longerFixed(query, limit, deadlineNs)
        }
        return longerOpen(syllables, openTone, limit, deadlineNs)
    }

    /** Every tone of a body, filtered where the user already chose a tone. */
    private fun exactOpen(
        syllables: List<String>,
        openTone: BooleanArray,
        includeSupplements: Boolean,
    ): List<LexHit> {
        val query = bodyQuery(syllables) ?: return emptyList()
        val chosen = chosenIds(syllables, openTone) ?: return emptyList()
        var lo = lowerBody(query)
        val out = ArrayList<LexHit>()
        while (lo < entryCount && compareBody(byBody[lo], query) == 0) {
            val index = byBody[lo]
            if (tonesMatch(index, chosen) && (includeSupplements || !isSupplement(index))) out += hit(index)
            lo++
        }
        return out
    }

    /**
     * A typed body that is not itself a syllable, such as ㄋ or ㄏ.
     * The walk stays in that first syllable's range, then the second body's range.
     * It does not pair every legal syllable of one initial with every syllable of the next.
     * Each first syllable keeps its own best rows.
     */
    private fun prefixHits(
        syllables: List<String>,
        openTone: BooleanArray,
        includeSupplements: Boolean,
    ): List<LexHit> {
        val typed = Array(syllables.size) { syllables[it].trimEnd('ˊ', 'ˇ', 'ˋ', '˙') }
        if (typed.any { it.isEmpty() }) return emptyList()
        val spans = Array(typed.size) { bodySpan(typed[it]) }
        // One body is first == last. Only an empty or backwards span is a miss.
        if (spans.any { it.isEmpty() || it.first > it.last }) return emptyList()
        val anyTone = BooleanArray(syllables.size) { index -> index >= openTone.size || openTone[index] }
        val wantTone = CharArray(syllables.size) { toneMark(syllables[it]) }
        val out = ArrayList<LexHit>()
        for (bodyId in spans[0]) {
            val start = lowerByFirst(bodyId)
            val end = lowerByFirst(bodyId + 1)
            if (start >= end) continue
            val primary = TopLex(PREFIX_KEEP)
            val supplements = if (includeSupplements) TopLex(PREFIX_KEEP) else null
            if (syllables.size == 1) {
                var pos = start
                while (pos < end) {
                    val index = byBody[pos]
                    if ((rdLen[index].toInt() and 0xFF) != 1) break
                    offerPrefix(primary, supplements, index, typed, anyTone, wantTone)
                    pos++
                }
            } else {
                val second = spans[1]
                var pos = lowerBySecond(start, end, second.first)
                // lowerBySecond is an exclusive end. last + 1 keeps spans[1].last.
                val to = lowerBySecond(start, end, second.last + 1)
                while (pos < to) {
                    offerPrefix(primary, supplements, byBody[pos], typed, anyTone, wantTone)
                    pos++
                }
            }
            out += primary.hits()
            val extra = supplements?.hits().orEmpty()
            if (extra.isNotEmpty()) out += extra
        }
        return out
    }

    private fun offerPrefix(
        primary: TopLex,
        supplements: TopLex?,
        index: Int,
        typed: Array<String>,
        anyTone: BooleanArray,
        wantTone: CharArray,
    ) {
        val unmatched = unmatchedPrefix(index, typed, anyTone, wantTone)
        if (unmatched < 0) return
        val supplement = isSupplement(index)
        if (supplement && supplements == null) return
        val score = prefixScore(prw[index], unmatched)
        if (supplement) supplements?.add(index, score, unmatched) else primary.add(index, score, unmatched)
    }

    /** -1 when this entry is not the typed syllable count, tone, or body prefix. */
    private fun unmatchedPrefix(
        index: Int,
        typed: Array<String>,
        anyTone: BooleanArray,
        wantTone: CharArray,
    ): Int {
        val len = rdLen[index].toInt() and 0xFF
        if (len != typed.size) return -1
        val off = rdOff[index]
        var extra = 0
        for (step in typed.indices) {
            val id = rd[off + step].toInt() and 0xFFFF
            val form = syllableForms.getOrNull(id) ?: return -1
            val body = form.trimEnd('ˊ', 'ˇ', 'ˋ', '˙')
            if (!body.startsWith(typed[step])) return -1
            if (!anyTone[step] && toneMark(form) != wantTone[step]) return -1
            extra += body.length - typed[step].length
        }
        return extra
    }

    private fun prefixScore(prior: Float, extra: Int): Double {
        val safe = if (prior > 0f) prior.toDouble() else 1e-300
        return ln(safe) - extra * LINGXI_EXTRA_PENALTY
    }

    private fun toneMark(reading: String): Char {
        val last = reading.lastOrNull() ?: return '\u0000'
        return if (last == 'ˊ' || last == 'ˇ' || last == 'ˋ' || last == '˙') last else '\u0000'
    }

    /** Inclusive start, exclusive end, of bodies whose text starts with [prefix]. */
    private fun bodySpan(prefix: String): IntRange {
        return lowerBodyForm(prefix) until upperBodyForm(prefix)
    }

    private fun lowerBodyForm(prefix: String): Int {
        var lo = 0
        var hi = bodyForms.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (bodyForms[mid] < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun upperBodyForm(prefix: String): Int {
        var lo = 0
        var hi = bodyForms.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val body = bodyForms[mid]
            if (body.startsWith(prefix) || body < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun lowerByFirst(bodyId: Int): Int {
        var lo = 0
        var hi = entryCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (firstBodyAt(mid) < bodyId) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun lowerBySecond(start: Int, end: Int, bodyId: Int): Int {
        var lo = start
        var hi = end
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (secondBodyAt(mid) < bodyId) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun firstBodyAt(pos: Int): Int {
        val index = byBody[pos]
        if ((rdLen[index].toInt() and 0xFF) <= 0) return Int.MAX_VALUE
        val id = rd[rdOff[index]].toInt() and 0xFFFF
        return bodyOfSyllable[id]
    }

    /** A one-syllable entry sorts before every longer word of the same first body. */
    private fun secondBodyAt(pos: Int): Int {
        val index = byBody[pos]
        if ((rdLen[index].toInt() and 0xFF) < 2) return -1
        val id = rd[rdOff[index] + 1].toInt() and 0xFFFF
        return bodyOfSyllable[id]
    }

    private inner class TopLex(private val limit: Int) {
        private val entries = IntArray(limit)
        private val scores = DoubleArray(limit)
        private val extras = IntArray(limit)
        private var size = 0

        fun add(entry: Int, value: Double, unmatched: Int) {
            if (size == limit && value <= scores[size - 1]) return
            var slot = if (size < limit) size else limit - 1
            while (slot > 0 && value > scores[slot - 1]) {
                entries[slot] = entries[slot - 1]
                scores[slot] = scores[slot - 1]
                extras[slot] = extras[slot - 1]
                slot--
            }
            entries[slot] = entry
            scores[slot] = value
            extras[slot] = unmatched
            if (size < limit) size++
        }

        fun hits(): List<LexHit> = List(size) { hit(entries[it], extras[it]) }
    }

    /** One toneless prefix scan. [limit] counts longer entries whose chosen tones match. */
    private fun longerOpen(
        syllables: List<String>,
        openTone: BooleanArray,
        limit: Int,
        deadlineNs: Long,
    ): List<LongHit> {
        val query = bodyQuery(syllables) ?: return emptyList()
        val chosen = chosenIds(syllables, openTone) ?: return emptyList()
        var lo = lowerBody(query)
        val out = ArrayList<LongHit>(limit)
        var seen = 0
        var stepped = 0
        while (lo < entryCount && seen < limit) {
            if ((stepped and 31) == 0 && past(deadlineNs)) break
            stepped++
            val index = byBody[lo]
            if (!startsWithBody(index, query)) break
            val len = rdLen[index].toInt() and 0xFF
            if (len > query.size && tonesMatch(index, chosen) && !isSupplement(index)) {
                seen++
                val reading = readingOf(index, len)
                if (reading != null) out += LongHit(surfaceOf(index), prw[index], len, reading)
            }
            lo++
        }
        return out
    }

    private fun bodyQuery(syllables: List<String>): IntArray? {
        val query = IntArray(syllables.size)
        for (index in syllables.indices) {
            val body = syllables[index].trimEnd('ˊ', 'ˇ', 'ˋ', '˙')
            query[index] = bodyIds[body] ?: return null
        }
        return query
    }

    /** Chosen tones keep their syllable id. An open tone is -1 and matches any tone. */
    private fun chosenIds(syllables: List<String>, openTone: BooleanArray): IntArray? {
        val chosen = IntArray(syllables.size)
        for (index in syllables.indices) {
            if (openTone[index]) {
                chosen[index] = -1
            } else {
                chosen[index] = syllableIds[syllables[index]] ?: return null
            }
        }
        return chosen
    }

    private fun lowerBody(query: IntArray): Int {
        var lo = 0
        var hi = entryCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareBody(byBody[mid], query) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun compareBodyEntries(left: Int, right: Int): Int {
        val leftOff = rdOff[left]
        val rightOff = rdOff[right]
        val leftLen = rdLen[left].toInt() and 0xFF
        val rightLen = rdLen[right].toInt() and 0xFF
        val shared = minOf(leftLen, rightLen)
        for (step in 0 until shared) {
            val leftBody = bodyOfSyllable[rd[leftOff + step].toInt() and 0xFFFF]
            val rightBody = bodyOfSyllable[rd[rightOff + step].toInt() and 0xFFFF]
            if (leftBody != rightBody) return leftBody - rightBody
        }
        return leftLen - rightLen
    }

    private fun compareBody(index: Int, query: IntArray): Int {
        val off = rdOff[index]
        val len = rdLen[index].toInt() and 0xFF
        val shared = minOf(len, query.size)
        for (step in 0 until shared) {
            val body = bodyOfSyllable[rd[off + step].toInt() and 0xFFFF]
            if (body != query[step]) return body - query[step]
        }
        return len - query.size
    }

    private fun startsWithBody(index: Int, query: IntArray): Boolean {
        val len = rdLen[index].toInt() and 0xFF
        if (len < query.size) return false
        val off = rdOff[index]
        for (step in query.indices) {
            val body = bodyOfSyllable[rd[off + step].toInt() and 0xFFFF]
            if (body != query[step]) return false
        }
        return true
    }

    private fun tonesMatch(index: Int, chosen: IntArray): Boolean {
        val off = rdOff[index]
        val len = rdLen[index].toInt() and 0xFF
        if (len < chosen.size) return false
        for (step in chosen.indices) {
            val want = chosen[step]
            if (want < 0) continue
            if ((rd[off + step].toInt() and 0xFFFF) != want) return false
        }
        return true
    }

    fun hasSurface(surface: String): Boolean {
        val key = surface.toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = entryCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareSurface(byText[mid], key) < 0) lo = mid + 1 else hi = mid
        }
        // A supplement-only surface is not a language-model word. A shorter suffix keeps the bigram.
        while (lo < entryCount && compareSurface(byText[lo], key) == 0) {
            if (!isSupplement(byText[lo])) return true
            lo++
        }
        return false
    }

    /** Highest-prior supplements for this reading. Non-supplements are not materialized. */
    fun topSupplements(
        syllables: List<String>,
        openTone: BooleanArray = BooleanArray(0),
        skip: Collection<String> = emptyList(),
        limit: Int = 8,
    ): List<LexHit> {
        if (limit <= 0 || syllables.isEmpty()) return emptyList()
        val skipKeys = skip.map { it.toByteArray(Charsets.UTF_8) }
        val best = IntArray(limit)
        var kept = 0
        fun skipped(index: Int): Boolean {
            if (sfLen[index] == 0) return true
            for (key in skipKeys) {
                if (compareBytes(strs, sfOff[index], sfLen[index], key) == 0) return true
            }
            return false
        }
        fun consider(index: Int) {
            if (!isSupplement(index) || skipped(index)) return
            // Body order is not prior order. The later tone replaces the chip only when its prior is higher.
            for (slot in 0 until kept) {
                if (compareRanges(
                        strs, sfOff[index], sfLen[index],
                        strs, sfOff[best[slot]], sfLen[best[slot]],
                    ) != 0
                ) {
                    continue
                }
                if (!supplementBefore(index, best[slot])) return
                var from = slot
                while (from < kept - 1) {
                    best[from] = best[from + 1]
                    from++
                }
                kept--
                break
            }
            if (kept == limit && !supplementBefore(index, best[kept - 1])) return
            var at = if (kept < limit) kept else kept - 1
            while (at > 0 && supplementBefore(index, best[at - 1])) {
                best[at] = best[at - 1]
                at--
            }
            best[at] = index
            if (kept < limit) kept++
        }
        val open = openTone.size == syllables.size && openTone.any { it }
        if (!open) {
            val query = queryIds(syllables) ?: return emptyList()
            var lo = 0
            var hi = entryCount
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (compareReading(mid, query) < 0) lo = mid + 1 else hi = mid
            }
            while (lo < entryCount && compareReading(lo, query) == 0) {
                consider(lo)
                lo++
            }
        } else {
            val query = bodyQuery(syllables) ?: return emptyList()
            val chosen = chosenIds(syllables, openTone) ?: return emptyList()
            var lo = lowerBody(query)
            while (lo < entryCount && compareBody(byBody[lo], query) == 0) {
                val index = byBody[lo]
                if (tonesMatch(index, chosen)) consider(index)
                lo++
            }
        }
        return List(kept) { hit(best[it]) }
    }

    private fun supplementBefore(left: Int, right: Int): Boolean {
        val prior = prw[left].compareTo(prw[right])
        if (prior != 0) return prior > 0
        return compareRanges(strs, sfOff[left], sfLen[left], strs, sfOff[right], sfLen[right]) < 0
    }

    fun census(): Pair<Int, Int> {
        var characters = 0
        var words = 0
        for (index in 0 until entryCount) {
            val runes = utf8Runes(strs, sfOff[index], sfLen[index])
            if (runes == 1) characters++ else if (runes > 1) words++
        }
        return characters to words
    }

    private fun isSupplement(index: Int): Boolean {
        return flags[index].toInt() and supplementMask != 0
    }

    internal fun copyRows(): LexiconRows {
        val forms = ArrayList<String>(syllableForms.size)
        forms.addAll(syllableForms)
        val rows = ArrayList<LexiconRow>(entryCount)
        for (index in 0 until entryCount) {
            val len = rdLen[index].toInt() and 0xFF
            val off = rdOff[index]
            val reading = IntArray(len) { step -> rd[off + step].toInt() and 0xFFFF }
            rows += LexiconRow(reading, surfaceOf(index), prw[index], flags[index].toInt() and 0xFF)
        }
        return LexiconRows(forms, rows)
    }

    private fun hit(index: Int, extra: Int = 0): LexHit {
        val flag = flags[index].toInt() and 0xFF
        return LexHit(
            surface = String(strs, sfOff[index], sfLen[index], Charsets.UTF_8),
            prw = prw[index],
            rare = flag and 2 != 0,
            supplement = flag and supplementMask != 0,
            extra = extra,
        )
    }

    private fun compareReading(index: Int, query: IntArray): Int {
        val off = rdOff[index]
        val len = rdLen[index].toInt() and 0xFF
        val shared = minOf(len, query.size)
        for (step in 0 until shared) {
            val left = rd[off + step].toInt() and 0xFFFF
            val right = query[step]
            if (left != right) return left - right
        }
        return len - query.size
    }

    private fun compareSurface(index: Int, key: ByteArray): Int {
        return compareBytes(strs, sfOff[index], sfLen[index], key)
    }

    /** Lowers packaged personal-name priors. Returns how many rows changed. */
    internal fun demotePersonalNames(unigram: (String) -> Double): Int {
        var changed = 0
        for (index in 0 until entryCount) {
            if (utf8Runes(strs, sfOff[index], sfLen[index]) !in 3..4) continue
            val surface = surfaceOf(index)
            if (!isPersonalName(surface)) continue
            if (unigram(surface) >= PERSONAL_NAME_COMMON_UNI) continue
            if (commonSlice(surface, unigram)) continue
            val scaled = prw[index] * PERSONAL_NAME_SCALE
            val next = if (scaled < PERSONAL_NAME_FLOOR) PERSONAL_NAME_FLOOR else scaled
            if (next >= prw[index]) continue
            prw[index] = next
            changed++
        }
        return changed
    }

    private fun commonSlice(surface: String, unigram: (String) -> Double): Boolean {
        for (index in 0 until surface.length - 1) {
            if (unigram(surface.substring(index, index + 2)) >= PERSONAL_NAME_SLICE_UNI) return true
        }
        return false
    }
}

/** Compact unigram and bigram model (`ZUINLM`). Bigram miss is 0. Unigram miss is 1e-8. */
internal class LingxiLm(
    private val strs: ByteArray,
    private val off: IntArray,
    private val uni: FloatArray,
    private val leftStart: IntArray,
    private val right: ByteArray,
    private val q: ByteArray,
) {
    val unigrams: Int get() = uni.size
    val bigrams: Int get() = q.size

    fun uni(word: String): Double = uniAt(idOf(word))

    fun has(word: String): Boolean = idOf(word) >= 0

    /** Unigram row, or -1 when the word was never counted. */
    fun idOf(word: String): Int = find(word)

    fun uniAt(index: Int): Double = if (index >= 0) uni[index].toDouble() else UNSEEN

    fun bi(left: String, word: String): Double = biAt(idOf(left), idOf(word))

    fun biAt(leftId: Int, rightId: Int): Double {
        if (q.isEmpty() || leftId < 0 || rightId < 0) return 0.0
        if (leftId >= uni.size || leftStart[leftId] == leftStart[leftId + 1]) return 0.0
        var lo = leftStart[leftId]
        val hiLimit = leftStart[leftId + 1]
        var hi = hiLimit
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (rightAt(mid) < rightId) lo = mid + 1 else hi = mid
        }
        return if (lo < hiLimit && rightAt(lo) == rightId) Q_TABLE[q[lo].toInt() and 0xFF] else 0.0
    }

    /** Both sides one rune, and both sides longer than one rune. Mixed pairs are neither. */
    fun bigramClasses(): Pair<Int, Int> {
        val runes = IntArray(uni.size)
        for (index in uni.indices) {
            runes[index] = utf8Runes(strs, off[index], off[index + 1] - off[index])
        }
        var single = 0
        var multi = 0
        for (left in uni.indices) {
            val from = leftStart[left]
            val to = leftStart[left + 1]
            val leftRunes = runes[left]
            for (slot in from until to) {
                val rightRunes = runes[rightAt(slot)]
                if (leftRunes == 1 && rightRunes == 1) single++
                else if (leftRunes > 1 && rightRunes > 1) multi++
            }
        }
        return single to multi
    }

    private fun find(word: String): Int {
        val key = word.toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = uni.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val length = off[mid + 1] - off[mid]
            if (compareBytes(strs, off[mid], length, key) < 0) lo = mid + 1 else hi = mid
        }
        if (lo >= uni.size) return -1
        val length = off[lo + 1] - off[lo]
        return if (compareBytes(strs, off[lo], length, key) == 0) lo else -1
    }

    private fun rightAt(index: Int): Int {
        val at = index * 3
        return (right[at].toInt() and 0xFF) or
            ((right[at + 1].toInt() and 0xFF) shl 8) or
            ((right[at + 2].toInt() and 0xFF) shl 16)
    }

    private companion object {
        const val UNSEEN = 1e-8
        val Q_TABLE = DoubleArray(256) { byte -> 10.0.pow(-byte / 255.0 * 8.0) }
    }
}

internal class LingxiTables(
    val lexicon: LingxiLexicon,
    val lm: LingxiLm,
    val census: LingxiCensus,
)

internal fun loadLingxiTables(root: File): LingxiTables {
    val english = lingxiFile(root, "data/english/words.bin", "english/words.bin")
    require(english.isFile && english.length() > 0L) { "lingxi english subpack missing" }
    val englishWords = countEnglish(english.readBytes())
    require(englishWords > 0) { "lingxi english subpack empty" }
    val lexiconFile = lingxiFile(root, "data/lexicon.bin", "lexicon.bin")
    val mask = supplementMaskOf(File(lexiconFile.parentFile, "lexicon.merged"))
    val lexicon = decodeLexicon(lexiconFile.readBytes(), mask)
    val lm = decodeLm(lingxiFile(root, "data/lm.bin", "lm.bin").readBytes())
    require(lm.bigrams > 0) { "lingxi bigrams empty" }
    val (characters, words) = lexicon.census()
    val (single, multi) = lm.bigramClasses()
    val census = LingxiCensus(characters, words, lm.unigrams, multi, single, englishWords)
    census.requireFull()
    return LingxiTables(lexicon, lm, census)
}

internal fun lingxiFile(root: File, nested: String, flat: String): File {
    val primary = File(root, nested)
    return if (primary.isFile) primary else File(root, flat)
}

internal const val LINGXI_SUPPLEMENT_FLAG = 4

private const val PREFIX_KEEP = 8
private const val LEX_MAGIC = "ZUINLEX\u0000"
private const val LM_MAGIC = "ZUINLM\u0000\u0000"
private val ENGLISH_MAGIC = byteArrayOf(0x30, 0x63, 0x45, 0x4E, 0x00, 0x01)

private class BinReader(private val data: ByteArray, private val limit: Int) {
    var pos = 0
    fun u32(): Int {
        val raw = take(4)
        return (raw[0].toInt() and 0xFF) or
            ((raw[1].toInt() and 0xFF) shl 8) or
            ((raw[2].toInt() and 0xFF) shl 16) or
            ((raw[3].toInt() and 0xFF) shl 24)
    }

    fun u16s(): ShortArray {
        val count = count(2)
        val out = ShortArray(count)
        for (index in 0 until count) {
            val at = pos + index * 2
            out[index] = ((data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)).toShort()
        }
        pos += count * 2
        return out
    }

    fun u32s(): IntArray {
        val count = count(4)
        val out = IntArray(count)
        for (index in 0 until count) out[index] = u32()
        return out
    }

    fun f32s(): FloatArray {
        val bits = u32s()
        return FloatArray(bits.size) { java.lang.Float.intBitsToFloat(bits[it]) }
    }

    fun bytes8(): ByteArray {
        val count = count(1)
        val out = data.copyOfRange(pos, pos + count)
        pos += count
        return out
    }

    fun done() {
        require(pos == limit) { "lingxi trailing bytes ${limit - pos}" }
    }

    private fun count(size: Int): Int {
        val n = u32()
        require(n >= 0) { "lingxi count overflow" }
        require(n.toLong() * size <= (limit - pos).toLong()) { "lingxi truncated" }
        return n
    }

    private fun take(n: Int): ByteArray {
        require(pos + n <= limit) { "lingxi truncated" }
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }
}

private fun openPack(bytes: ByteArray, magic: String, version: Int): BinReader {
    require(bytes.size >= magic.length + 8) { "lingxi short file" }
    require(String(bytes, 0, magic.length, Charsets.ISO_8859_1) == magic) { "lingxi magic" }
    val crc = CRC32()
    crc.update(bytes, 0, bytes.size - 4)
    val end = bytes.size
    val stored = (bytes[end - 4].toInt() and 0xFF) or
        ((bytes[end - 3].toInt() and 0xFF) shl 8) or
        ((bytes[end - 2].toInt() and 0xFF) shl 16) or
        ((bytes[end - 1].toInt() and 0xFF) shl 24)
    require(crc.value.toInt() == stored) { "lingxi crc" }
    val reader = BinReader(bytes, bytes.size - 4)
    reader.pos = magic.length
    val found = reader.u32()
    require(found == version) { "lingxi version $found" }
    return reader
}

internal fun supplementMaskOf(marker: File): Int {
    if (!marker.isFile) return LINGXI_SUPPLEMENT_FLAG
    val mask = marker.readText().trim().toIntOrNull() ?: return LINGXI_SUPPLEMENT_FLAG
    if (mask <= 0 || mask > 128 || mask and (mask - 1) != 0 || mask == 2) return LINGXI_SUPPLEMENT_FLAG
    return mask
}

internal fun decodeLexicon(bytes: ByteArray, supplementMask: Int = LINGXI_SUPPLEMENT_FLAG): LingxiLexicon {
    val reader = openPack(bytes, LEX_MAGIC, 1)
    val syllableBytes = reader.bytes8()
    val rd = reader.u16s()
    val rdOff = reader.u32s()
    val sfOff = reader.u32s()
    val sfLenShort = reader.u16s()
    val rdLen = reader.bytes8()
    val flags = reader.bytes8()
    val prw = reader.f32s()
    val strs = reader.bytes8()
    val byText = reader.u32s()
    reader.done()
    val n = rdOff.size
    require(sfOff.size == n && sfLenShort.size == n && rdLen.size == n && flags.size == n && prw.size == n && byText.size == n) {
        "lingxi lexicon columns"
    }
    val sfLen = IntArray(n) { sfLenShort[it].toInt() and 0xFFFF }
    for (index in 0 until n) {
        val readingEnd = rdOff[index].toLong() + (rdLen[index].toInt() and 0xFF)
        require(readingEnd <= rd.size.toLong()) { "lingxi reading offset" }
        require(sfOff[index].toLong() + sfLen[index] <= strs.size.toLong()) { "lingxi surface offset" }
    }
    val syllables = String(syllableBytes, Charsets.UTF_8).split(' ').filter { it.isNotEmpty() }
    val ids = HashMap<String, Int>(syllables.size)
    syllables.forEachIndexed { index, syllable -> ids[syllable] = index }
    return LingxiLexicon(
        ids,
        syllables.toTypedArray(),
        rd,
        rdOff,
        sfOff,
        sfLen,
        rdLen,
        flags,
        prw,
        strs,
        byText,
        supplementMask,
    )
}

private fun decodeLm(bytes: ByteArray): LingxiLm {
    val reader = openPack(bytes, LM_MAGIC, 2)
    val strs = reader.bytes8()
    val off = reader.u32s()
    val uni = reader.f32s()
    val leftStart = reader.u32s()
    val right = reader.bytes8()
    val q = reader.bytes8()
    reader.done()
    val n = uni.size
    require(off.size == n + 1 && leftStart.size == n + 1) { "lingxi lm columns" }
    require(right.size == 3 * q.size) { "lingxi bigram width" }
    require(leftStart[n] == q.size) { "lingxi bigram index" }
    return LingxiLm(strs, off, uni, leftStart, right, q)
}

private fun countEnglish(bytes: ByteArray): Int {
    require(bytes.size in 1..(16 shl 20)) { "lingxi english size" }
    require(bytes.size >= ENGLISH_MAGIC.size + 4) { "lingxi english header" }
    for (index in ENGLISH_MAGIC.indices) require(bytes[index] == ENGLISH_MAGIC[index]) { "lingxi english magic" }
    var pos = ENGLISH_MAGIC.size
    val count = (bytes[pos].toInt() and 0xFF) or
        ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
        ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
        ((bytes[pos + 3].toInt() and 0xFF) shl 24)
    pos += 4
    require(count in 1..200_000) { "lingxi english count" }
    var previous = ByteArray(0)
    repeat(count) {
        require(pos < bytes.size) { "lingxi english truncated" }
        val length = bytes[pos].toInt() and 0xFF
        pos++
        require(length in 1..255 && pos + length + 9 <= bytes.size) { "lingxi english word" }
        val text = bytes.copyOfRange(pos, pos + length)
        pos += length
        var bits = 0L
        repeat(8) { shift ->
            bits = bits or ((bytes[pos].toLong() and 0xFF) shl (shift * 8))
            pos++
        }
        val weight = Double.fromBits(bits)
        require(weight > 0.0 && weight.isFinite()) { "lingxi english weight" }
        val flag = bytes[pos].toInt() and 0xFF
        pos++
        require(flag <= 1) { "lingxi english flag" }
        require(compareBytes(previous, 0, previous.size, text) < 0) { "lingxi english order" }
        previous = text
    }
    require(pos == bytes.size) { "lingxi english trailing" }
    return count
}

private fun compareBytes(data: ByteArray, start: Int, length: Int, key: ByteArray): Int {
    return compareRanges(data, start, length, key, 0, key.size)
}

internal class LexiconRow(
    val reading: IntArray,
    val surface: String,
    var prw: Float,
    val flags: Int,
)

internal class LexiconRows(
    val syllables: MutableList<String>,
    val rows: MutableList<LexiconRow>,
)

internal fun LingxiLexicon.encode(): ByteArray {
    val copied = copyRows()
    return encodeLexicon(copied.syllables, copied.rows)
}

/** Entries are stored in reading-id order. [byText] is rebuilt from the surfaces. */
internal fun encodeLexicon(syllables: List<String>, rows: List<LexiconRow>): ByteArray {
    val order = Array(rows.size) { it }
    order.sortWith { left, right -> compareReadings(rows[left].reading, rows[right].reading) }
    var readingCount = 0
    var surfaceBytes = 0
    val encoded = Array(rows.size) { ByteArray(0) }
    for (index in rows.indices) {
        val row = rows[index]
        readingCount += row.reading.size
        val bytes = row.surface.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 0xFFFF) { "lingxi surface too long" }
        encoded[index] = bytes
        surfaceBytes += bytes.size
    }
    val rd = ShortArray(readingCount)
    val rdOff = IntArray(rows.size)
    val sfOff = IntArray(rows.size)
    val sfLen = IntArray(rows.size)
    val rdLen = ByteArray(rows.size)
    val flags = ByteArray(rows.size)
    val prw = FloatArray(rows.size)
    val strs = ByteArray(surfaceBytes)
    var rdCursor = 0
    var sfCursor = 0
    for (slot in order.indices) {
        val row = rows[order[slot]]
        require(row.reading.size <= 255) { "lingxi reading too long" }
        rdOff[slot] = rdCursor
        rdLen[slot] = row.reading.size.toByte()
        for (id in row.reading) {
            require(id in 0 until syllables.size) { "lingxi reading syllable" }
            rd[rdCursor++] = (id and 0xFFFF).toShort()
        }
        val bytes = encoded[order[slot]]
        sfOff[slot] = sfCursor
        sfLen[slot] = bytes.size
        bytes.copyInto(strs, sfCursor)
        sfCursor += bytes.size
        flags[slot] = (row.flags and 0xFF).toByte()
        prw[slot] = row.prw
    }
    val byTextOrder = Array(rows.size) { it }
    byTextOrder.sortWith { left, right ->
        compareRanges(strs, sfOff[left], sfLen[left], strs, sfOff[right], sfLen[right])
    }
    val byText = IntArray(rows.size) { byTextOrder[it] }
    val writer = BinWriter()
    writer.raw(LEX_MAGIC.toByteArray(Charsets.ISO_8859_1))
    writer.u32(1)
    writer.bytes8(syllables.joinToString(" ").toByteArray(Charsets.UTF_8))
    writer.u16s(rd)
    writer.u32s(rdOff)
    writer.u32s(sfOff)
    writer.u16s(sfLen)
    writer.bytes8(rdLen)
    writer.bytes8(flags)
    writer.f32s(prw)
    writer.bytes8(strs)
    writer.u32s(byText)
    val body = writer.toByteArray()
    val crc = CRC32()
    crc.update(body)
    writer.u32(crc.value.toInt())
    return writer.toByteArray()
}

private fun compareReadings(left: IntArray, right: IntArray): Int {
    val shared = minOf(left.size, right.size)
    for (index in 0 until shared) {
        if (left[index] != right[index]) return left[index] - right[index]
    }
    return left.size - right.size
}

private fun compareRanges(
    left: ByteArray,
    leftAt: Int,
    leftLen: Int,
    right: ByteArray,
    rightAt: Int,
    rightLen: Int,
): Int {
    val shared = minOf(leftLen, rightLen)
    for (index in 0 until shared) {
        val leftByte = left[leftAt + index].toInt() and 0xFF
        val rightByte = right[rightAt + index].toInt() and 0xFF
        if (leftByte != rightByte) return leftByte - rightByte
    }
    return leftLen - rightLen
}

private class BinWriter {
    private var data = ByteArray(64)
    private var size = 0

    fun u32(value: Int) {
        ensure(4)
        data[size] = value.toByte()
        data[size + 1] = (value ushr 8).toByte()
        data[size + 2] = (value ushr 16).toByte()
        data[size + 3] = (value ushr 24).toByte()
        size += 4
    }

    fun raw(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        ensure(bytes.size)
        bytes.copyInto(data, size)
        size += bytes.size
    }

    fun bytes8(bytes: ByteArray) {
        u32(bytes.size)
        raw(bytes)
    }

    fun u16s(values: ShortArray) {
        u32(values.size)
        for (value in values) u16(value.toInt() and 0xFFFF)
    }

    fun u16s(values: IntArray) {
        u32(values.size)
        for (value in values) u16(value and 0xFFFF)
    }

    fun u32s(values: IntArray) {
        u32(values.size)
        for (value in values) u32(value)
    }

    fun f32s(values: FloatArray) {
        u32(values.size)
        for (value in values) u32(java.lang.Float.floatToRawIntBits(value))
    }

    fun toByteArray(): ByteArray = data.copyOf(size)

    private fun u16(value: Int) {
        ensure(2)
        data[size] = value.toByte()
        data[size + 1] = (value ushr 8).toByte()
        size += 2
    }

    private fun ensure(extra: Int) {
        val need = size + extra
        if (need <= data.size) return
        var next = maxOf(data.size, 64)
        while (next < need) next *= 2
        data = data.copyOf(next)
    }
}

internal fun utf8Runes(data: ByteArray, start: Int, length: Int): Int {
    var count = 0
    var index = start
    val end = start + length
    while (index < end) {
        val lead = data[index].toInt() and 0xFF
        index += when {
            lead < 0x80 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            else -> 4
        }
        count++
    }
    return count
}
