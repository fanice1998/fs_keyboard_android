package com.fskeyboard.ime.candidate

import com.fskeyboard.ime.keyboard.ZhuyinClass
import com.fskeyboard.ime.keyboard.ZhuyinPhonology
import com.fskeyboard.ime.keyboard.ZhuyinTone

/** One typed syllable. [tone] stays [ZhuyinTone.NONE] until the user marks it. */
internal data class SyllableReading(val body: String, val tone: ZhuyinTone)

/**
 * Bopomofo for a sentence that has not been given to the engine syllable by syllable.
 * A key that cannot extend the open syllable starts the next one, so ㄋㄧㄏㄠ
 * stays two syllables instead of the second initial replacing the first.
 */
internal class BopomofoChain {
    private val syllables = ArrayList<StringBuilder>()
    private val tones = ArrayList<ZhuyinTone>()

    val isEmpty: Boolean
        get() = syllables.isEmpty()

    val size: Int
        get() = syllables.size

    val text: String
        get() = buildString {
            for (index in syllables.indices) {
                append(syllables[index])
                append(tones[index].mark)
            }
        }

    fun lastToneChosen(): Boolean = tones.lastOrNull()?.chosen == true

    fun readings(): List<SyllableReading> {
        return syllables.indices.map { SyllableReading(syllables[it].toString(), tones[it]) }
    }

    fun clear() {
        syllables.clear()
        tones.clear()
    }

    /** Puts [earlier] ahead of the syllables already typed. */
    fun insertFront(earlier: List<SyllableReading>) {
        if (earlier.isEmpty()) return
        val bodies = ArrayList<StringBuilder>(earlier.size + syllables.size)
        val marked = ArrayList<ZhuyinTone>(earlier.size + tones.size)
        for (reading in earlier) {
            bodies += StringBuilder(reading.body)
            marked += reading.tone
        }
        bodies.addAll(syllables)
        marked.addAll(tones)
        syllables.clear()
        syllables.addAll(bodies)
        tones.clear()
        tones.addAll(marked)
    }

    /** Drops the first [count] syllables after the user accepts a candidate that covers them. */
    fun dropFront(count: Int) {
        repeat(count.coerceAtMost(syllables.size)) {
            syllables.removeAt(0)
            tones.removeAt(0)
        }
    }

    fun append(ch: Char): Boolean {
        val kind = ZhuyinPhonology.classify(ch) ?: return false
        if (kind == ZhuyinClass.TONE) {
            if (syllables.isEmpty() || syllables.last().isEmpty()) return false
            tones[tones.lastIndex] = toneOf(ch)
            return true
        }
        if (syllables.isNotEmpty() && tones.last() == ZhuyinTone.NONE && canExtend(syllables.last(), ch)) {
            syllables.last().append(ch)
            return true
        }
        syllables += StringBuilder().append(ch)
        tones += ZhuyinTone.NONE
        return true
    }

    /**
     * Marks the open syllable as 平聲. A leading space, with nothing typed, does nothing.
     * A tone already on that syllable stays, so space does not strip it.
     */
    fun markLevel(): Boolean {
        if (syllables.isEmpty() || syllables.last().isEmpty()) return false
        if (tones.last() != ZhuyinTone.NONE) return false
        tones[tones.lastIndex] = ZhuyinTone.LEVEL
        return true
    }

    /** Clears a chosen tone before it deletes a symbol. */
    fun backspace() {
        if (syllables.isEmpty()) return
        if (tones.last() != ZhuyinTone.NONE) {
            tones[tones.lastIndex] = ZhuyinTone.NONE
            return
        }
        val body = syllables.last()
        if (body.isNotEmpty()) body.deleteCharAt(body.lastIndex)
        if (body.isEmpty()) {
            syllables.removeAt(syllables.lastIndex)
            tones.removeAt(tones.lastIndex)
        }
    }

    private fun toneOf(ch: Char): ZhuyinTone = when (ch) {
        'ˊ' -> ZhuyinTone.SECOND
        'ˇ' -> ZhuyinTone.THIRD
        'ˋ' -> ZhuyinTone.FOURTH
        '˙' -> ZhuyinTone.NEUTRAL
        else -> ZhuyinTone.NONE
    }

    private fun canExtend(body: StringBuilder, ch: Char): Boolean {
        val kind = ZhuyinPhonology.classify(ch) ?: return false
        if (kind == ZhuyinClass.TONE || body.isEmpty()) return body.isEmpty() && kind != ZhuyinClass.TONE
        var seenInitial = false
        var seenMedial = false
        var seenFinal = false
        var initial: Char? = null
        var last = ZhuyinClass.INITIAL
        for (glyph in body) {
            val classified = ZhuyinPhonology.classify(glyph) ?: return false
            last = classified
            when (classified) {
                ZhuyinClass.INITIAL -> {
                    seenInitial = true
                    initial = glyph
                }
                ZhuyinClass.MEDIAL -> seenMedial = true
                ZhuyinClass.FINAL -> seenFinal = true
                ZhuyinClass.TONE -> return false
            }
        }
        if (kind == ZhuyinClass.MEDIAL && !ZhuyinPhonology.medialFits(initial, ch)) return false
        val already = when (kind) {
            ZhuyinClass.INITIAL -> seenInitial
            ZhuyinClass.MEDIAL -> seenMedial
            ZhuyinClass.FINAL -> seenFinal
            ZhuyinClass.TONE -> true
        }
        if (already) return false
        return kind.ordinal > last.ordinal
    }
}

/**
 * One composition string: chosen Hanzi, then the bopomofo still being typed.
 * The editor underlines it, and the bubble on the candidate bar shows the same text.
 */
internal fun compositionText(composing: String, reading: String): String = composing + reading
