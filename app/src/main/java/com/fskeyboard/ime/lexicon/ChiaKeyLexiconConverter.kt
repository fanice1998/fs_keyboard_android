package com.fskeyboard.ime.lexicon

import com.fskeyboard.ime.candidate.BopomofoOrder
import com.fskeyboard.ime.engine.ChiaKeyUnigramScan
import java.io.File

/**
 * Writes ChiaKey unigrams as [SOURCE_BUILTIN] rows.
 * The read is [ChiaKeyUnigramScan.read]: `qstring`, `current`, `probability`.
 * One row has no left context, so next-word suggestions stay on lexicon.bin.
 */
internal object ChiaKeyLexiconConverter {
    fun convert(root: File, lexicon: FsLexicon) {
        lexicon.replaceBuiltin(rows { accept -> ChiaKeyUnigramScan.read(root, accept) })
    }

    fun rows(
        read: (accept: (qstring: String, text: String, probability: Double) -> Unit) -> Unit,
    ): List<Row> {
        val out = ArrayList<Row>()
        read { qstring, text, probability ->
            val reading = readingOf(qstring) ?: return@read
            if (!FsLexicon.fieldOk(text) || !probability.isFinite()) return@read
            out += Row(reading, text, probability, FsLexicon.SOURCE_BUILTIN)
        }
        return out
    }

    /** Bopomofo with tone marks, syllables joined, the same spelling the keyboard stores. */
    fun readingOf(qstring: String): String? {
        val syllables = BopomofoOrder.decode(qstring) ?: return null
        if (syllables.isEmpty()) return null
        val reading = buildString {
            for (syllable in syllables) {
                append(syllable.body)
                append(syllable.tone.mark)
            }
        }
        return reading.takeIf { FsLexicon.fieldOk(it) }
    }
}
