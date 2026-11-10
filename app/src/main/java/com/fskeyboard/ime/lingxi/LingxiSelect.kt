package com.fskeyboard.ime.lingxi

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

internal data class LingxiResult(
    val candidates: List<String>,
    /** `ok` when the model reordered, `miss` when it ran past the budget or failed, `skip` when it was not asked. */
    val reorder: String,
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
    ): LingxiResult {
        val committed = withoutOpenReading(context, syllables)
        val statistical = viterbi(syllables, leftWord(committed))
        val texts = statistical.map { it.text }
        if (!useModel || scorer == null || statistical.size < 2) {
            return LingxiResult(pin(texts, remembered), "skip")
        }
        if (System.nanoTime() > deadlineNs) {
            return LingxiResult(pin(texts, remembered), "miss")
        }
        val reordered = try {
            scoreWithin(deadlineNs) { reorder(syllables, committed, statistical, deadlineNs) }
        } catch (_: TimeoutException) {
            return LingxiResult(pin(texts, remembered), "miss")
        } catch (_: Exception) {
            return LingxiResult(pin(texts, remembered), "miss")
        }
        val label = lingxiReorderLabel(reordered, deadlineNs, System.nanoTime())
        if (label != "ok" || reordered == null) return LingxiResult(pin(texts, remembered), label)
        return LingxiResult(pin(reordered, remembered), "ok")
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

    private fun viterbi(syllables: List<String>, left: String): List<Row> {
        if (syllables.isEmpty()) return emptyList()
        val n = syllables.size
        val states = Array(n + 1) { HashMap<String, ArrayList<Hyp>>() }
        states[0][left] = arrayListOf(Hyp("", 0.0, left, false))
        for (at in 0 until n) {
            val hyps = prune(states[at])
            if (hyps.isEmpty()) break
            val room = minOf(MAX_WORD, n - at)
            for (len in 1..room) {
                val entries = tables.lexicon.exact(syllables.subList(at, at + len))
                if (entries.isEmpty()) continue
                for (hyp in hyps) {
                    for (entry in entries) {
                        if (entry.surface.isEmpty() || entry.prw <= 0f) continue
                        val score = hyp.log + arcLog(hyp.last, entry.surface, entry.prw)
                        offer(
                            states[at + len],
                            Hyp(hyp.text + entry.surface, score, entry.surface, hyp.rare || entry.rare),
                        )
                    }
                }
            }
        }
        val best = HashMap<String, Hyp>()
        for (group in states[n].values) {
            for (hyp in group) {
                if (hyp.text.isEmpty()) continue
                val old = best[hyp.text]
                if (old == null || hyp.log > old.log) best[hyp.text] = hyp
            }
        }
        return best.values.sortedWith(
            compareBy<Hyp> { it.rare }
                .thenByDescending { it.log }
                .thenBy { block(it.text) }
                .thenBy { it.text },
        ).take(LINGXI_N_BEST).map { Row(it.text, it.log) }
    }

    private fun arcLog(left: String, surface: String, prw: Float): Double {
        return logProb(left, surface, prw)
    }

    private fun logProb(left: String, surface: String, prw: Float): Double {
        val probability = MU * tables.lm.bi(left, surface) + (1.0 - MU) * tables.lm.uni(surface)
        val safe = if (probability > 0.0) probability else 1e-300
        val prior = if (prw > 0f) prw.toDouble() else 1e-300
        return ln(safe) + ln(prior)
    }

    private fun leftWord(context: String): String {
        if (context.isEmpty()) return BOS
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
        return BOS
    }

    private companion object {
        const val BOS = "<s>"
        val SCORER = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "fskeyboard-lingxi-score").apply { isDaemon = true }
        }
    }
}

private class Row(val text: String, val log: Double)

private class Hyp(val text: String, val log: Double, val last: String, val rare: Boolean)

private fun offer(groups: MutableMap<String, ArrayList<Hyp>>, hyp: Hyp) {
    val rows = groups.getOrPut(hyp.last) { ArrayList() }
    val existing = rows.indexOfFirst { it.text == hyp.text }
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

internal fun loadLingxiEngine(root: File): LingxiEngine {
    val tables = loadLingxiTables(root)
    val model = lingxiFile(root, "select/model.onnx", "select/model.onnx")
    val vocab = lingxiFile(root, "select/vocab.json", "select/vocab.json")
    val manifest = lingxiFile(root, "select/manifest.json", "select/manifest.json")
    require(model.isFile && vocab.isFile && manifest.isFile) { "lingxi select weights missing" }
    val scorer = LingxiSequence.open(manifest, vocab, model)
    scorer.warm()
    return LingxiEngine(tables, scorer)
}
