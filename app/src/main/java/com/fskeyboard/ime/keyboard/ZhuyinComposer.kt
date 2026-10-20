package com.fskeyboard.ime.keyboard

internal enum class ZhuyinClass {
    INITIAL,
    MEDIAL,
    FINAL,
    TONE,
}

internal object ZhuyinPhonology {
    private val initials = setOf(
        'ㄅ', 'ㄆ', 'ㄇ', 'ㄈ',
        'ㄉ', 'ㄊ', 'ㄋ', 'ㄌ',
        'ㄍ', 'ㄎ', 'ㄏ',
        'ㄐ', 'ㄑ', 'ㄒ',
        'ㄓ', 'ㄔ', 'ㄕ', 'ㄖ',
        'ㄗ', 'ㄘ', 'ㄙ',
    )
    private val medials = setOf('ㄧ', 'ㄨ', 'ㄩ')
    private val finals = setOf(
        'ㄚ', 'ㄛ', 'ㄜ', 'ㄝ',
        'ㄞ', 'ㄟ', 'ㄠ', 'ㄡ',
        'ㄢ', 'ㄣ', 'ㄤ', 'ㄥ',
        'ㄦ',
    )
    private val tones = setOf('ˊ', 'ˇ', 'ˋ', '˙')

    fun classify(ch: Char): ZhuyinClass? = when (ch) {
        in initials -> ZhuyinClass.INITIAL
        in medials -> ZhuyinClass.MEDIAL
        in finals -> ZhuyinClass.FINAL
        in tones -> ZhuyinClass.TONE
        else -> null
    }

    /** Taiwan bopomofo: a medial only follows the initials that can take it. */
    fun medialFits(initial: Char?, medial: Char): Boolean {
        val head = initial ?: return true
        return when (head) {
            'ㄅ', 'ㄆ', 'ㄇ' -> medial != 'ㄩ'
            'ㄈ' -> medial == 'ㄨ'
            'ㄉ', 'ㄊ', 'ㄋ', 'ㄌ' -> true
            'ㄍ', 'ㄎ', 'ㄏ' -> medial == 'ㄨ'
            'ㄐ', 'ㄑ', 'ㄒ' -> medial == 'ㄧ' || medial == 'ㄩ'
            'ㄓ', 'ㄔ', 'ㄕ', 'ㄖ', 'ㄗ', 'ㄘ', 'ㄙ' -> medial == 'ㄨ'
            else -> true
        }
    }
}

/**
 * 平聲 has no glyph. [ZhuyinTone.NONE] means the syllable is still unfinished,
 * so an unmarked reading is not the same as first tone.
 */
internal enum class ZhuyinTone {
    NONE,
    LEVEL,
    SECOND,
    THIRD,
    FOURTH,
    NEUTRAL,
    ;

    val mark: String
        get() = when (this) {
            NONE, LEVEL -> ""
            SECOND -> "ˊ"
            THIRD -> "ˇ"
            FOURTH -> "ˋ"
            NEUTRAL -> "˙"
        }

    val chosen: Boolean
        get() = this != NONE
}

/**
 * Accumulates bopomofo in syllable order.
 * A key that cannot extend the current syllable starts the next one, so
 * ㄓㄨㄥㄍㄨㄛ stays one composition instead of being committed key by key.
 * A tone replaces the tone already on the open syllable. A leading tone is ignored.
 * 平聲 is stored on the syllable and does not add a character.
 */
internal class ZhuyinComposer {
    private val parts = ArrayList<Syllable>()

    val text: String
        get() = parts.joinToString("") { it.display() }

    val syllables: List<String>
        get() = parts.map { it.display() }

    val isEmpty: Boolean
        get() = parts.isEmpty()

    /**
     * True when every syllable has a tone, including 平聲.
     * An unfinished syllable stays false so lookup can offer every tone.
     */
    val exact: Boolean
        get() = parts.isNotEmpty() && parts.all { it.tone.chosen }

    /**
     * Syllable codes for lookup. Each syllable keeps the tone already chosen.
     * 平聲 and an unfinished syllable contribute the body alone.
     * Null when nothing has been typed.
     */
    fun queries(): List<String>? = partialExactQueries()

    fun partialExactQueries(): List<String>? {
        if (parts.isEmpty()) return null
        return parts.map { it.body.toString() + it.tone.mark }
    }

    fun append(glyph: String): Boolean {
        if (glyph.length != 1) return false
        val ch = glyph[0]
        val kind = ZhuyinPhonology.classify(ch) ?: return false
        if (parts.isEmpty()) {
            if (kind == ZhuyinClass.TONE) return false
            parts += Syllable().also { it.body.append(ch) }
            return true
        }
        val current = parts.last()
        if (kind == ZhuyinClass.TONE) {
            if (current.body.isEmpty()) return false
            current.tone = toneOf(ch)
            return true
        }
        if (current.tone == ZhuyinTone.NONE && canAppendBody(current.body, ch, kind)) {
            current.body.append(ch)
            return true
        }
        parts += Syllable().also { it.body.append(ch) }
        return true
    }

    /** Marks the open syllable as 平聲 and drops any tone mark already on it. */
    fun markLevelTone(): Boolean {
        val current = parts.lastOrNull() ?: return false
        if (current.body.isEmpty()) return false
        current.tone = ZhuyinTone.LEVEL
        return true
    }

    fun backspace() {
        val current = parts.lastOrNull() ?: return
        if (current.tone != ZhuyinTone.NONE) {
            current.tone = ZhuyinTone.NONE
            return
        }
        if (current.body.isEmpty()) {
            parts.removeAt(parts.lastIndex)
            return
        }
        current.body.deleteCharAt(current.body.lastIndex)
        if (current.body.isEmpty()) parts.removeAt(parts.lastIndex)
    }

    fun clear() {
        parts.clear()
    }

    fun spelling(): String = ZhuyinSpelling.keysOf(text)

    fun adopt(raw: String) {
        clear()
        val glyphs = ZhuyinSpelling.toGlyphs(raw) ?: return
        for (ch in glyphs) append(ch.toString())
    }

    private fun toneOf(ch: Char): ZhuyinTone = when (ch) {
        'ˊ' -> ZhuyinTone.SECOND
        'ˇ' -> ZhuyinTone.THIRD
        'ˋ' -> ZhuyinTone.FOURTH
        '˙' -> ZhuyinTone.NEUTRAL
        else -> ZhuyinTone.NONE
    }

    private fun canAppendBody(current: StringBuilder, ch: Char, kind: ZhuyinClass): Boolean {
        if (kind == ZhuyinClass.TONE) return false
        if (current.isEmpty()) return true
        var seenInitial = false
        var seenMedial = false
        var seenFinal = false
        var initial: Char? = null
        var last = ZhuyinClass.INITIAL
        for (glyph in current) {
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

private class Syllable {
    val body = StringBuilder()
    var tone: ZhuyinTone = ZhuyinTone.NONE

    fun display(): String = body.toString() + tone.mark
}
