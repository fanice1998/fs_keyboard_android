package com.fskeyboard.ime.lingxi

import com.fskeyboard.ime.engine.ChiaKeyUnigramScan
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.ln

/** Shown row size. The model only reorders this list. */
internal const val LINGXI_N_BEST = 8

/** Candidate index that is not a ChiaKey row. Accept stores the reading under this index. */
internal const val LINGXI_CANDIDATE = -2

private const val MU = 0.7
private const val MAX_WORD = 8
private const val BEAM = 32
internal const val LINGXI_CONTEXT_RUNES = 64
internal const val LINGXI_KEY_BUDGET_NS = 25_000_000L

/**
 * Drops the open bopomofo from the end of the editor text. The cursor sits inside
 * that underline, so the text before it includes the reading. Chosen Hanzi in front
 * of the reading stays. The editor can still be one key behind the syllable just typed.
 */
internal fun withoutOpenReading(context: String, syllables: List<String>): String {
    if (context.isEmpty() || syllables.isEmpty()) return context
    val reading = buildString(syllables.sumOf { it.length }) { for (syllable in syllables) append(syllable) }
    if (reading.isEmpty()) return context
    val limit = minOf(reading.length, context.length)
    for (cut in limit downTo 1) {
        if (context.regionMatches(context.length - cut, reading, 0, cut)) return context.dropLast(cut)
    }
    return context
}

/** A finished score past [deadlineNs] is a miss, including when the row was discarded. */
internal fun lingxiReorderLabel(reordered: List<String>?, deadlineNs: Long, now: Long): String {
    if (deadlineNs != Long.MAX_VALUE && now > deadlineNs) return "miss"
    if (reordered == null) return "skip"
    return "ok"
}

internal data class LingxiCompletion(
    val surface: String,
    val tail: String,
    val reading: String,
)

internal data class LingxiResult(
    val candidates: List<String>,
    /** `ok` when the model reordered, `miss` when it ran past the budget or failed, `skip` when it was not asked. */
    val reorder: String,
    /** A longer lexicon word continuing [candidates]'s first row. Never one of those rows. */
    val completion: LingxiCompletion? = null,
)

/**
 * Lexicon proposal, unigram/bigram Viterbi, then an optional reorder of at most [LINGXI_N_BEST].
 * [useModel] false keeps the statistical order so a test can see that the model changed the first row.
 */
internal class LingxiEngine(
    private val tables: LingxiTables,
    private val scorer: LingxiScorer?,
) {
    val census: LingxiCensus get() = tables.census

    fun select(
        syllables: List<String>,
        context: String,
        remembered: String?,
        deadlineNs: Long = Long.MAX_VALUE,
        useModel: Boolean = true,
        openTone: BooleanArray = BooleanArray(0),
    ): LingxiResult {
        val committed = withoutOpenReading(context, syllables)
        val left = lingxiLeftWord(tables, committed)
        val statistical = viterbi(syllables, openTone, left)
        val texts = statistical.map { it.text }
        val nonRare = statistical.count { !it.rare }
        // A character outside the vocab must not make the model skip the Lingxi rows.
        val fillers = if (nonRare >= LINGXI_N_BEST || statistical.size >= LINGXI_N_BEST) {
            emptyList()
        } else {
            seatSupplements(syllables, openTone, left, texts, LINGXI_N_BEST - statistical.size)
        }
        // The lexicon walk happens before the model, and stops once the key budget is gone.
        val scanned = scanContinuations(syllables, openTone, deadlineNs)
        fun finish(rows: List<String>, label: String): LingxiResult {
            val shown = pin(if (fillers.isEmpty()) rows else rows + fillers, remembered)
            val hint = chooseCompletion(scanned, shown.firstOrNull().orEmpty(), committed)
            return LingxiResult(shown, label, hint)
        }
        if (!useModel || scorer == null || statistical.size < 2) {
            return finish(texts, "skip")
        }
        if (System.nanoTime() > deadlineNs) {
            return finish(texts, "miss")
        }
        val reordered = try {
            scoreWithin(deadlineNs) { reorder(syllables, committed, statistical, deadlineNs) }
        } catch (_: TimeoutException) {
            return finish(texts, "miss")
        } catch (_: Exception) {
            return finish(texts, "miss")
        }
        val label = lingxiReorderLabel(reordered, deadlineNs, System.nanoTime())
        if (label != "ok" || reordered == null) return finish(texts, label)
        return finish(reordered, "ok")
    }

    /**
     * One longer lexicon word that continues [head]. Null when nothing is confident.
     * The model is not asked. A deadline already passed returns null.
     */
    /** Lexicon rows for exactly these syllables. Empty when a syllable is not in the table. */
    fun exactHits(
        syllables: List<String>,
        openTone: BooleanArray = BooleanArray(0),
        includeSupplements: Boolean = true,
    ): List<LexHit> {
        return tables.lexicon.exact(syllables, openTone, includeSupplements)
    }

    fun topSupplements(
        syllables: List<String>,
        openTone: BooleanArray = BooleanArray(0),
        skip: Collection<String> = emptyList(),
        limit: Int = 8,
    ): List<LexHit> {
        return tables.lexicon.topSupplements(syllables, openTone, skip, limit)
    }

    /** Real lexicon readings of [surface]. Empty when the word is not in the lexicon table. */
    fun readingsOf(surface: String): List<String> {
        return tables.lexicon.readingsOf(surface)
    }

    fun completion(
        syllables: List<String>,
        openTone: BooleanArray,
        context: String,
        head: String,
        deadlineNs: Long = Long.MAX_VALUE,
    ): LingxiCompletion? {
        if (head.isEmpty()) return null
        val committed = withoutOpenReading(context, syllables)
        return chooseCompletion(scanContinuations(syllables, openTone, deadlineNs), head, committed)
    }

    private fun scanContinuations(
        syllables: List<String>,
        openTone: BooleanArray,
        deadlineNs: Long,
    ): List<LongHit> {
        if (syllables.isEmpty()) return emptyList()
        val openCount = if (openTone.size == syllables.size) openTone.count { it } else 0
        if (openCount > MAX_OPEN_TONES) return emptyList()
        if (deadlineNs != Long.MAX_VALUE && System.nanoTime() > deadlineNs) return emptyList()
        return tables.lexicon.longer(syllables, openTone, LONGER_LIMIT, deadlineNs).filter { hit ->
            val extra = hit.syllables - syllables.size
            extra in 1..MAX_EXTRA && hit.syllables <= MAX_WORD
        }
    }

    private fun chooseCompletion(
        hits: List<LongHit>,
        head: String,
        committed: String,
    ): LingxiCompletion? {
        if (head.isEmpty() || hits.isEmpty()) return null
        val left = lingxiLeftWord(tables, committed)
        var best: LongHit? = null
        var bestLog = Double.NEGATIVE_INFINITY
        for (hit in hits) {
            if (hit.surface.codePointCount(0, hit.surface.length) <= head.codePointCount(0, head.length)) continue
            if (!hit.surface.startsWith(head)) continue
            if (tables.lm.bi(left, hit.surface) <= 0.0) continue
            val score = logProb(left, hit.surface, hit.prw)
            if (score > bestLog) {
                bestLog = score
                best = hit
            }
        }
        val chosen = best ?: return null
        val tail = chosen.surface.substring(head.length)
        if (tail.isEmpty()) return null
        return LingxiCompletion(chosen.surface, tail, chosen.reading)
    }

    /** A finite budget waits on the scorer only until the deadline, then keeps the statistical row. */
    private fun scoreWithin(deadlineNs: Long, block: () -> List<String>?): List<String>? {
        if (deadlineNs == Long.MAX_VALUE) return block()
        val remaining = deadlineNs - System.nanoTime()
        if (remaining <= 0L) throw TimeoutException()
        val pending = SCORER.submit(block)
        return try {
            pending.get(remaining, TimeUnit.NANOSECONDS)
        } catch (error: TimeoutException) {
            pending.cancel(true)
            throw error
        }
    }

    private fun reorder(
        syllables: List<String>,
        context: String,
        statistical: List<Row>,
        deadlineNs: Long,
    ): List<String>? {
        val model = scorer ?: return null
        val textOnly = syllables.size == 1 && model.manifest.textWeight > 0.0
        if (textOnly && context.isEmpty()) return null
        if (!textOnly && syllables.size !in 2..32) return null
        val texts = statistical.map { it.text }
        if (texts.any { it.codePointCount(0, it.length) != syllables.size }) return null
        val scores = model.score(context, syllables, texts, textOnly)
        if (scores.size != statistical.size) return null
        // A score that lands after the budget is not a skip. select() labels it miss.
        if (System.nanoTime() > deadlineNs) return null
        val weight = if (textOnly) model.manifest.textWeight else model.manifest.weight
        return statistical.indices.sortedWith(
            compareByDescending<Int> { statistical[it].log + weight * scores[it] }.thenBy { it },
        ).map { texts[it] }
    }

    private fun viterbi(syllables: List<String>, openTone: BooleanArray, left: String): List<Row> {
        if (syllables.isEmpty()) return emptyList()
        val unspecified = openTone.size == syllables.size
        val n = syllables.size
        val wordIds = HashMap<String, Int>(256)
        fun wordId(text: String): Int {
            wordIds[text]?.let { return it }
            val id = tables.lm.idOf(text)
            wordIds[text] = id
            return id
        }
        val states = Array(n + 1) { HashMap<String, ArrayList<Hyp>>() }
        states[0][left] = arrayListOf(Hyp(null, "", 0.0, left, false))
        for (at in 0 until n) {
            val hyps = prune(states[at])
            if (hyps.isEmpty()) break
            val room = minOf(MAX_WORD, n - at)
            val leftIds = IntArray(hyps.size) { wordId(hyps[it].last) }
            for (len in 1..room) {
                val tones = if (unspecified) openTone.copyOfRange(at, at + len) else BooleanArray(0)
                // Supplements stay out of the beam. A common syllable has thousands of them.
                val entries = tables.lexicon.exact(
                    syllables.subList(at, at + len),
                    tones,
                    includeSupplements = false,
                )
                if (entries.isEmpty()) continue
                val rightIds = IntArray(entries.size) { wordId(entries[it].surface) }
                for (hypIndex in hyps.indices) {
                    val hyp = hyps[hypIndex]
                    val leftId = leftIds[hypIndex]
                    for (entryIndex in entries.indices) {
                        val entry = entries[entryIndex]
                        if (entry.surface.isEmpty() || entry.prw <= 0f) continue
                        val score = hyp.log + arcLog(leftId, rightIds[entryIndex], entry.prw, entry.extra)
                        offer(
                            states[at + len],
                            Hyp(hyp, entry.surface, score, entry.surface, hyp.rare || entry.rare),
                        )
                    }
                }
            }
        }
        val best = HashMap<String, Hyp>()
        for (group in states[n].values) {
            for (hyp in group) {
                val text = hyp.text()
                if (text.isEmpty()) continue
                val old = best[text]
                if (old == null || hyp.log > old.log) best[text] = hyp
            }
        }
        val ranked = best.values.sortedWith(
            compareBy<Hyp> { it.rare }
                .thenByDescending { it.log }
                .thenBy { block(it.text()) }
                .thenBy { it.text() },
        ).take(LINGXI_N_BEST)
        val out = ArrayList<Row>(ranked.size)
        for (hyp in ranked) out += Row(hyp.text(), hyp.log, hyp.rare)
        return out
    }

    private fun seatSupplements(
        syllables: List<String>,
        openTone: BooleanArray,
        left: String,
        taken: List<String>,
        room: Int,
    ): List<String> {
        if (room <= 0 || syllables.isEmpty()) return emptyList()
        val n = syllables.size
        val tones = if (openTone.size == n) openTone else BooleanArray(0)
        val have = HashSet<String>(taken.size * 2)
        have += taken
        val leftId = tables.lm.idOf(left)
        val extras = ArrayList<Row>()
        for (entry in tables.lexicon.exact(syllables, tones)) {
            if (!entry.supplement || entry.surface.isEmpty() || entry.prw <= 0f) continue
            if (entry.surface.codePointCount(0, entry.surface.length) != n) continue
            if (!have.add(entry.surface)) continue
            extras += Row(
                entry.surface,
                scored(leftId, tables.lm.idOf(entry.surface), entry.prw, entry.extra),
                rare = true,
            )
        }
        extras.sortWith(compareByDescending<Row> { it.log }.thenBy { block(it.text) }.thenBy { it.text })
        val out = ArrayList<String>(room)
        for (row in extras) {
            if (out.size >= room) break
            out += row.text
        }
        return out
    }

    private fun arcLog(leftId: Int, rightId: Int, prw: Float, extra: Int): Double {
        return scored(leftId, rightId, prw, extra)
    }

    private fun logProb(left: String, surface: String, prw: Float): Double {
        return scored(tables.lm.idOf(left), tables.lm.idOf(surface), prw, 0)
    }

    private fun scored(leftId: Int, rightId: Int, prw: Float, extra: Int): Double {
        val probability = MU * tables.lm.biAt(leftId, rightId) + (1.0 - MU) * tables.lm.uniAt(rightId)
        val safe = if (probability > 0.0) probability else 1e-300
        val prior = if (prw > 0f) prw.toDouble() else 1e-300
        return ln(safe) + ln(prior) - extra * LINGXI_EXTRA_PENALTY
    }

    private companion object {
        const val LONGER_LIMIT = 32
        const val MAX_EXTRA = 4
        const val MAX_OPEN_TONES = 2
        val SCORER = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "fskeyboard-lingxi-score").apply { isDaemon = true }
        }
    }
}

private class Row(val text: String, val log: Double, val rare: Boolean)

/**
 * One lexicon arc. [prev] is the path before [piece]. The start node has no [prev].
 * The joined surface is built only for a path that stays in the beam.
 */
private class Hyp(
    val prev: Hyp?,
    val piece: String,
    val log: Double,
    val last: String,
    val rare: Boolean,
) {
    private var rendered: String? = null

    fun text(): String {
        rendered?.let { return it }
        val value = if (prev == null) {
            ""
        } else {
            val parts = ArrayList<String>()
            var node: Hyp? = this
            while (node?.prev != null) {
                parts.add(node.piece)
                node = node.prev
            }
            buildString {
                for (index in parts.lastIndex downTo 0) append(parts[index])
            }
        }
        rendered = value
        return value
    }
}

private fun offer(groups: MutableMap<String, ArrayList<Hyp>>, hyp: Hyp) {
    val rows = groups.getOrPut(hyp.last) { ArrayList() }
    if (rows.size >= LINGXI_N_BEST && hyp.log <= rows[rows.lastIndex].log) return
    val text = hyp.text()
    val existing = rows.indexOfFirst { it.text() == text }
    if (existing >= 0) {
        if (rows[existing].log >= hyp.log) return
        rows.removeAt(existing)
    }
    var at = rows.indexOfFirst { it.log < hyp.log }
    if (at < 0) at = rows.size
    if (at >= LINGXI_N_BEST) return
    rows.add(at, hyp)
    if (rows.size > LINGXI_N_BEST) rows.removeAt(rows.lastIndex)
}

private fun prune(groups: Map<String, List<Hyp>>): List<Hyp> {
    return groups.entries
        .sortedByDescending { it.value.first().log }
        .take(BEAM)
        .flatMap { it.value }
}

private fun pin(candidates: List<String>, remembered: String?): List<String> {
    if (remembered.isNullOrEmpty()) return candidates
    val index = candidates.indexOf(remembered)
    if (index <= 0) return candidates
    val out = ArrayList(candidates)
    out.removeAt(index)
    out.add(0, remembered)
    return out
}

private fun block(text: String): Int {
    if (text.isEmpty()) return 3
    val point = text.codePointAt(0)
    return when {
        point in 0x4E00..0x9FFF -> 0
        point in 0x3400..0x4DBF -> 2
        point <= 0xFFFF -> 1
        else -> 3
    }
}

internal fun lingxiLeftWord(tables: LingxiTables, context: String): String {
    if (context.isEmpty()) return "<s>"
    val points = codePoints(context)
    val room = minOf(6, points.size)
    for (length in room downTo 1) {
        val suffix = String(points, points.size - length, length)
        if (tables.lm.has(suffix) || tables.lexicon.hasSurface(suffix)) return suffix
    }
    val last = points.last()
    if (Character.UnicodeScript.of(last) == Character.UnicodeScript.HAN) {
        return String(intArrayOf(last), 0, 1)
    }
    return "<s>"
}

private fun codePoints(text: String): IntArray {
    val count = text.codePointCount(0, text.length)
    val out = IntArray(count)
    var index = 0
    var offset = 0
    while (offset < text.length) {
        val point = text.codePointAt(offset)
        out[index++] = point
        offset += Character.charCount(point)
    }
    return out
}

/** Absent file is not an error. The native engine is not opened. */
internal fun readUserUnigramRows(file: File): List<ChiaUnigram> {
    if (!file.isFile) return emptyList()
    val out = ArrayList<ChiaUnigram>()
    ChiaKeyUnigramScan.readUserFile(file) { qstring, text, probability ->
        out += ChiaUnigram(qstring, text, probability)
    }
    return out
}

internal fun mergeUserUnigrams(
    tables: LingxiTables,
    file: File?,
    log: (String) -> Unit = {},
): LingxiTables {
    if (file == null) return tables
    val rows = try {
        readUserUnigramRows(file)
    } catch (error: Exception) {
        log("lingxi user unigrams skipped ${error.javaClass.simpleName}")
        return tables
    }
    if (rows.isEmpty()) return tables
    return try {
        val merged = mergeChiaUnigrams(tables.lexicon.encode(), rows, tables.lexicon.supplementMask)
        val lexicon = decodeLexicon(merged.bytes, merged.supplementMask)
        val (characters, words) = lexicon.census()
        LingxiTables(lexicon, tables.lm, tables.census.copy(characters = characters, words = words))
    } catch (error: Exception) {
        log("lingxi user unigrams skipped ${error.javaClass.simpleName}")
        tables
    }
}

internal fun loadLingxiEngine(
    root: File,
    userUnigrams: File? = null,
    log: (String) -> Unit = {},
): LingxiEngine {
    val loaded = loadLingxiTables(root)
    // User phrases are merged after this, so a saved phrase is not treated as a stock name.
    val demoted = loaded.lexicon.demotePersonalNames { loaded.lm.uni(it) }
    if (demoted > 0) log("lingxi personal names demoted=$demoted")
    val tables = mergeUserUnigrams(loaded, userUnigrams, log)
    val model = lingxiFile(root, "select/model.onnx", "select/model.onnx")
    val vocab = lingxiFile(root, "select/vocab.json", "select/vocab.json")
    val manifest = lingxiFile(root, "select/manifest.json", "select/manifest.json")
    require(model.isFile && vocab.isFile && manifest.isFile) { "lingxi select weights missing" }
    val scorer = LingxiSequence.open(manifest, vocab, model)
    scorer.warm()
    return LingxiEngine(tables, scorer)
}
