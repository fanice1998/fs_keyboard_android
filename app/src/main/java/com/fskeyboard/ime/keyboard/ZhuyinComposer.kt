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
}

/**
 * Accumulates bopomofo in syllable order.
 * A key that cannot extend the current syllable starts the next one, so
 * ㄓㄨㄥㄍㄨㄛ stays one composition instead of being committed key by key.
 * A tone replaces the tone already on the open syllable. A leading tone is ignored.
 */
internal class ZhuyinComposer {
    private val parts = ArrayList<StringBuilder>()

    val text: String
        get() = parts.joinToString("") { it.toString() }

    val syllables: List<String>
        get() = parts.map { it.toString() }

    val isEmpty: Boolean
        get() = parts.isEmpty()

    fun append(glyph: String): Boolean {
        if (glyph.length != 1) return false
        val ch = glyph[0]
        val kind = ZhuyinPhonology.classify(ch) ?: return false
        if (parts.isEmpty()) {
            if (kind == ZhuyinClass.TONE) return false
            parts += StringBuilder().append(ch)
            return true
        }
        val current = parts.last()
        if (canAppend(current, kind)) {
            current.append(ch)
            return true
        }
        if (kind == ZhuyinClass.TONE && endsWithTone(current)) {
            current.setCharAt(current.lastIndex, ch)
            return true
        }
        if (kind == ZhuyinClass.TONE) return false
        parts += StringBuilder().append(ch)
        return true
    }

    fun backspace() {
        if (parts.isEmpty()) return
        val current = parts.last()
        current.deleteCharAt(current.lastIndex)
        if (current.isEmpty()) parts.removeAt(parts.lastIndex)
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

    private fun canAppend(current: StringBuilder, kind: ZhuyinClass): Boolean {
        if (current.isEmpty()) return kind != ZhuyinClass.TONE
        var seenInitial = false
        var seenMedial = false
        var seenFinal = false
        var seenTone = false
        var last = ZhuyinClass.INITIAL
        for (ch in current) {
            val classified = ZhuyinPhonology.classify(ch) ?: return false
            last = classified
            when (classified) {
                ZhuyinClass.INITIAL -> seenInitial = true
                ZhuyinClass.MEDIAL -> seenMedial = true
                ZhuyinClass.FINAL -> seenFinal = true
                ZhuyinClass.TONE -> seenTone = true
            }
        }
        if (kind == ZhuyinClass.TONE) {
            return !seenTone && (seenInitial || seenMedial || seenFinal)
        }
        if (seenTone) return false
        val already = when (kind) {
            ZhuyinClass.INITIAL -> seenInitial
            ZhuyinClass.MEDIAL -> seenMedial
            ZhuyinClass.FINAL -> seenFinal
            ZhuyinClass.TONE -> false
        }
        if (already) return false
        return kind.ordinal > last.ordinal
    }

    private fun endsWithTone(current: StringBuilder): Boolean {
        return current.isNotEmpty() && ZhuyinPhonology.classify(current.last()) == ZhuyinClass.TONE
    }
}
