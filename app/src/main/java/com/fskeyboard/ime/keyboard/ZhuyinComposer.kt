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

    val size: Int
        get() = parts.size

    /**
     * Index in [text] where the next glyph is inserted.
     * Backspace deletes the glyph before this. A chosen tone, including 平聲,
     * is cleared first when the caret sits on the boundary after that syllable.
     * 平聲 has no width, so that boundary is still the end of the visible syllable.
     */
    var caret: Int = 0
        private set

    fun moveCaret(index: Int) {
        caret = index.coerceIn(0, text.length)
    }

    /**
     * Syllable codes for lookup. A chosen tone keeps its mark and 平聲 has none.
     * A syllable without a tone yet ends with `*`, so every tone of it is eligible.
     * Null when nothing has been typed.
     */
    fun queries(): List<String>? {
        if (parts.isEmpty()) return null
        return parts.map { it.query() }
    }

    /** Removes and returns the first [count] syllables. */
    fun take(count: Int): List<ZhuyinSyllable> {
        val taken = parts.take(count.coerceIn(0, parts.size)).map { it.snapshot() }
        val removed = taken.sumOf { it.display.length }
        repeat(taken.size) { parts.removeAt(0) }
        caret = if (caret <= removed) 0 else caret - removed
        return taken
    }

    /** Puts syllables returned by [take] back in front of what is left. */
    fun restore(syllables: List<ZhuyinSyllable>) {
        val added = syllables.sumOf { it.display.length }
        parts.addAll(0, syllables.map { Syllable.of(it) })
        caret = (caret + added).coerceIn(0, text.length)
    }

    /**
     * Appends [glyph]. [nearby] is the touch the key came from; it does not produce
     * another reading, so only the typed glyph is kept.
     */
    @Suppress("UNUSED_PARAMETER")
    fun append(glyph: String, nearby: List<NearbyKey> = emptyList()): Boolean {
        if (glyph.length != 1) return false
        val ch = glyph[0]
        if (ZhuyinPhonology.classify(ch) == null) return false
        if (caret < text.length) return insert(ch)
        val ok = push(ch)
        if (ok) caret = text.length
        return ok
    }

    /**
     * Marks the syllable at the caret as 平聲 and drops any tone mark already on it.
     * The caret at the boundary after a syllable marks that syllable. The caret at
     * the start marks nothing.
     */
    fun markLevelTone(): Boolean {
        if (caret <= 0) return false
        var display = 0
        for (part in parts) {
            val end = display + part.body.length + part.tone.mark.length
            if (caret > display && caret <= end) {
                if (part.body.isEmpty()) return false
                val mark = part.tone.mark.length
                val bodyEnd = display + part.body.length
                part.tone = ZhuyinTone.LEVEL
                if (mark > 0 && caret > bodyEnd) caret -= mark
                caret = caret.coerceIn(0, text.length)
                return true
            }
            display = end
        }
        return false
    }

    fun backspace() {
        if (parts.isEmpty()) return
        val glyphs = flatten()
        val at = flattenIndex(glyphs, caret.coerceIn(0, text.length))
        if (at <= 0) return
        val removedVisible = glyphs[at - 1].ch != null
        glyphs.removeAt(at - 1)
        rewrite(glyphs)
        if (removedVisible) caret -= 1
        caret = caret.coerceIn(0, text.length)
    }

    fun clear() {
        parts.clear()
        caret = 0
    }

    /** Appends one glyph at the end. Phonology decides whether it extends the last syllable. */
    private fun push(ch: Char): Boolean {
        val kind = ZhuyinPhonology.classify(ch) ?: return false
        if (parts.isEmpty()) {
            if (kind == ZhuyinClass.TONE) return false
            parts += Syllable().also { it.addBody(ch) }
            return true
        }
        val current = parts.last()
        if (kind == ZhuyinClass.TONE) {
            if (current.body.isEmpty()) return false
            current.tone = toneOf(ch)
            return true
        }
        if (current.tone == ZhuyinTone.NONE && canAppendBody(current.body, ch, kind)) {
            current.addBody(ch)
            return true
        }
        parts += Syllable().also { it.addBody(ch) }
        return true
    }

    private fun pushLevel() {
        val current = parts.lastOrNull() ?: return
        if (current.body.isEmpty()) return
        current.tone = ZhuyinTone.LEVEL
    }

    /**
     * Inserts [ch] at the caret and replays the syllables, so an illegal pair still splits.
     * A tone replaces the tone already on that syllable, including 平聲.
     */
    private fun insert(ch: Char): Boolean {
        val glyphs = flatten()
        var at = flattenIndex(glyphs, caret.coerceIn(0, text.length))
        if (at == 0 && ZhuyinPhonology.classify(ch) == ZhuyinClass.TONE) return false
        if (ZhuyinPhonology.classify(ch) == ZhuyinClass.TONE) {
            while (at < glyphs.size && isToneGlyph(glyphs[at])) glyphs.removeAt(at)
            var before = at - 1
            while (before >= 0 && isToneGlyph(glyphs[before])) {
                glyphs.removeAt(before)
                before -= 1
                at -= 1
            }
        }
        glyphs.add(at, Glyph(ch))
        rewrite(glyphs)
        caret = (caret + 1).coerceAtMost(text.length)
        return true
    }

    private fun rewrite(glyphs: List<Glyph>) {
        parts.clear()
        for (glyph in glyphs) {
            val ch = glyph.ch
            if (ch == null) pushLevel() else push(ch)
        }
    }

    private fun flatten(): ArrayList<Glyph> {
        val glyphs = ArrayList<Glyph>()
        for (part in parts) {
            for (index in part.body.indices) {
                glyphs.add(Glyph(part.body[index]))
            }
            when (part.tone) {
                ZhuyinTone.NONE -> Unit
                ZhuyinTone.LEVEL -> glyphs.add(Glyph(null))
                else -> if (part.tone.mark.isNotEmpty()) glyphs.add(Glyph(part.tone.mark[0]))
            }
        }
        return glyphs
    }

    /**
     * Flatten index where a caret at [caret] inserts.
     * A 平聲 marker has no width and stays with the syllable before the caret.
     */
    private fun flattenIndex(glyphs: List<Glyph>, caret: Int): Int {
        var display = 0
        var index = 0
        while (index < glyphs.size) {
            if (glyphs[index].ch == null) {
                index++
                continue
            }
            if (display == caret) return index
            display++
            index++
        }
        return glyphs.size
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

/** A finished or unfinished syllable taken out of a [ZhuyinComposer]. */
internal data class ZhuyinSyllable(
    val body: String,
    val tone: ZhuyinTone,
) {
    val display: String
        get() = body + tone.mark
}

/** One visible bopomofo glyph, or 平聲 when [ch] is null. */
private class Glyph(val ch: Char?)

private fun isToneGlyph(glyph: Glyph): Boolean {
    val ch = glyph.ch ?: return true
    return ZhuyinPhonology.classify(ch) == ZhuyinClass.TONE
}

private class Syllable {
    val body = StringBuilder()
    var tone: ZhuyinTone = ZhuyinTone.NONE

    fun addBody(ch: Char) {
        body.append(ch)
    }

    fun display(): String = body.toString() + tone.mark

    fun query(): String = syllableQuery(body.toString(), tone)

    fun snapshot(): ZhuyinSyllable = ZhuyinSyllable(body.toString(), tone)

    companion object {
        fun of(syllable: ZhuyinSyllable): Syllable = Syllable().apply {
            body.append(syllable.body)
            tone = syllable.tone
        }
    }
}

private fun syllableQuery(body: String, tone: ZhuyinTone): String {
    return if (tone.chosen) body + tone.mark else "$body*"
}
