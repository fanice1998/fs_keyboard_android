package com.fskeyboard.ime.lingxi

import com.fskeyboard.ime.candidate.BopomofoOrder
import java.io.File
import kotlin.math.exp

internal class ChiaUnigram(
    val qstring: String,
    val surface: String,
    val probability: Double,
)

internal class MergedLexicon(
    val bytes: ByteArray,
    val supplementMask: Int,
    val added: Int,
)

/**
 * Keeps a Lingxi row when the surface and reading already exist.
 * [supplementMask] null chooses a flag bit that no current row uses.
 */
internal fun mergeChiaUnigrams(
    lexiconBytes: ByteArray,
    unigrams: List<ChiaUnigram>,
    supplementMask: Int? = null,
): MergedLexicon {
    val copied = decodeLexicon(lexiconBytes).copyRows()
    val mask = supplementMask ?: chooseSupplementMask(copied.rows)
    val syllables = copied.syllables
    val rows = copied.rows
    val syllableIndex = HashMap<String, Int>(syllables.size * 2)
    syllables.forEachIndexed { index, syllable -> syllableIndex[syllable] = index }
    val locked = HashSet<String>(rows.size * 2)
    for (row in rows) locked += rowKey(row.surface, row.reading)
    val addedAt = HashMap<String, Int>()
    var added = 0
    for (unigram in unigrams) {
        val reading = readingIds(unigram, syllables, syllableIndex) ?: continue
        val key = rowKey(unigram.surface, reading)
        if (key in locked) continue
        val prior = priorOf(unigram.probability) ?: continue
        val existing = addedAt[key]
        if (existing != null) {
            if (prior > rows[existing].prw) rows[existing].prw = prior
            continue
        }
        addedAt[key] = rows.size
        rows += LexiconRow(reading, unigram.surface, prior, mask or 2)
        added += 1
    }
    return MergedLexicon(encodeLexicon(syllables, rows), mask, added)
}

/** Bit 2 (value 4) unless a packed row already uses it. Never the rare bit. */
internal fun chooseSupplementMask(rows: List<LexiconRow>): Int {
    var used = 0
    for (row in rows) used = used or row.flags
    if (used and LINGXI_SUPPLEMENT_FLAG == 0) return LINGXI_SUPPLEMENT_FLAG
    val candidates = intArrayOf(8, 16, 32, 64, 128, 1)
    for (mask in candidates) {
        if (used and mask == 0) return mask
    }
    error("lingxi flags have no free supplement bit")
}

private fun readingIds(
    unigram: ChiaUnigram,
    syllables: MutableList<String>,
    syllableIndex: HashMap<String, Int>,
): IntArray? {
    if (unigram.surface.isEmpty() || !unigram.probability.isFinite()) return null
    val decoded = BopomofoOrder.decode(unigram.qstring) ?: return null
    val count = decoded.size
    if (count !in 1..8) return null
    if (unigram.surface.codePointCount(0, unigram.surface.length) != count) return null
    val reading = IntArray(count)
    for (index in decoded.indices) {
        val syllable = decoded[index]
        val form = syllable.body + syllable.tone.mark
        if (form.isEmpty()) return null
        reading[index] = syllableId(form, syllables, syllableIndex)
    }
    return reading
}

private fun syllableId(
    form: String,
    syllables: MutableList<String>,
    syllableIndex: HashMap<String, Int>,
): Int {
    syllableIndex[form]?.let { return it }
    val id = syllables.size
    require(id <= 0xFFFF) { "lingxi syllable id overflow" }
    syllables.add(form)
    syllableIndex[form] = id
    return id
}

private fun priorOf(probability: Double): Float? {
    if (!probability.isFinite()) return null
    val prior = exp(probability).toFloat().coerceIn(1e-6f, 1f)
    if (!prior.isFinite() || prior <= 0f) return null
    return prior
}

private fun rowKey(surface: String, reading: IntArray): String {
    val out = StringBuilder(surface.length + reading.size + 1)
    out.append(surface).append('\u0000')
    for (id in reading) out.append((id and 0xFFFF).toChar())
    return out.toString()
}

/** `sqlite3 .mode ascii` rows: fields split by 0x1F, records by 0x1E. */
internal fun chiaRowsFromAscii(bytes: ByteArray): List<ChiaUnigram> {
    if (bytes.isEmpty()) return emptyList()
    val records = bytes.toString(Charsets.UTF_8).split('\u001e')
    val out = ArrayList<ChiaUnigram>(records.size)
    for (record in records) {
        if (record.isEmpty()) continue
        val fields = record.split('\u001f')
        if (fields.size < 3) continue
        val probability = fields[2].toDoubleOrNull() ?: continue
        out += ChiaUnigram(fields[0], fields[1], probability)
    }
    return out
}

fun main(args: Array<String>) {
    require(args.size == 4) { "usage: LingxiLexiconMerge lexicon rows lexiconOut markerOut" }
    val lexicon = File(args[0]).readBytes()
    val rows = chiaRowsFromAscii(File(args[1]).readBytes())
    val merged = mergeChiaUnigrams(lexicon, rows)
    val lexiconOut = File(args[2])
    val markerOut = File(args[3])
    lexiconOut.parentFile?.mkdirs()
    markerOut.parentFile?.mkdirs()
    val partial = File(lexiconOut.parentFile, "${lexiconOut.name}.partial")
    partial.writeBytes(merged.bytes)
    if (!partial.renameTo(lexiconOut)) {
        partial.copyTo(lexiconOut, overwrite = true)
        partial.delete()
    }
    markerOut.writeText("${merged.supplementMask}\n")
    println("lingxi merge added=${merged.added} mask=${merged.supplementMask} bytes=${merged.bytes.size}")
}
