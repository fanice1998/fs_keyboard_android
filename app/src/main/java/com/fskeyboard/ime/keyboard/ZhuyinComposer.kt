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

    /**
     * Readings that replace one uncertain key with a neighbour.
     * A key is uncertain when its own touch probability is below [TOUCH_CORRECTION_CONFIDENT].
     * Each result changes one glyph, keeps the other syllables, and drops a swap that
     * would make an illegal initial-medial pair. The most likely swap comes first.
     */
    fun corrections(limit: Int = TOUCH_CORRECTION_LIMIT): List<TouchCorrection> {
        val base = queries() ?: return emptyList()
        val found = ArrayList<TouchCorrection>()
        parts.forEachIndexed { syllableIndex, syllable ->
            val body = syllable.body.toString()
            syllable.bodyTouches.forEachIndexed { glyphIndex, options ->
                for (option in options) {
                    val replaced = substitutedBody(body, glyphIndex, option.label[0]) ?: continue
                    val queries = base.toMutableList()
                    queries[syllableIndex] = syllableQuery(replaced, syllable.tone)
                    found += TouchCorrection(queries, option.probability)
                }
            }
            for (option in syllable.toneTouches) {
                val tone = toneOf(option.label.firstOrNull() ?: continue)
                if (tone == ZhuyinTone.NONE || tone == syllable.tone) continue
                val queries = base.toMutableList()
                queries[syllableIndex] = syllableQuery(body, tone)
                found += TouchCorrection(queries, option.probability)
            }
        }
        return found
            .sortedByDescending { it.probability }
            .distinctBy { it.queries }
            .take(limit)
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

    fun append(glyph: String, nearby: List<NearbyKey> = emptyList()): Boolean {
        if (glyph.length != 1) return false
        val ch = glyph[0]
        if (ZhuyinPhonology.classify(ch) == null) return false
        val touches = touchAlternatives(ch, nearby)
        if (caret < text.length) return insert(ch, touches)
        val ok = push(ch, touches)
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
                part.toneTouches = emptyList()
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
    private fun push(ch: Char, touches: List<NearbyKey>): Boolean {
        val kind = ZhuyinPhonology.classify(ch) ?: return false
        if (parts.isEmpty()) {
            if (kind == ZhuyinClass.TONE) return false
            parts += Syllable().also { it.addBody(ch, touches) }
            return true
        }
        val current = parts.last()
        if (kind == ZhuyinClass.TONE) {
            if (current.body.isEmpty()) return false
            current.tone = toneOf(ch)
            current.toneTouches = touches
            return true
        }
        if (current.tone == ZhuyinTone.NONE && canAppendBody(current.body, ch, kind)) {
            current.addBody(ch, touches)
            return true
        }
        parts += Syllable().also { it.addBody(ch, touches) }
        return true
    }

    private fun pushLevel(touches: List<NearbyKey>) {
        val current = parts.lastOrNull() ?: return
        if (current.body.isEmpty()) return
        current.tone = ZhuyinTone.LEVEL
        current.toneTouches = touches
    }

    /**
     * Inserts [ch] at the caret and replays the syllables, so an illegal pair still splits.
     * A tone replaces the tone already on that syllable, including 平聲.
     */
    private fun insert(ch: Char, touches: List<NearbyKey>): Boolean {
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
        glyphs.add(at, Glyph(ch, touches))
        rewrite(glyphs)
        caret = (caret + 1).coerceAtMost(text.length)
        return true
    }

    private fun rewrite(glyphs: List<Glyph>) {
        parts.clear()
        for (glyph in glyphs) {
            val ch = glyph.ch
            if (ch == null) pushLevel(glyph.touches) else push(ch, glyph.touches)
        }
    }

    private fun flatten(): ArrayList<Glyph> {
        val glyphs = ArrayList<Glyph>()
        for (part in parts) {
            for (index in part.body.indices) {
                val touches = if (index < part.bodyTouches.size) part.bodyTouches[index] else emptyList()
                glyphs.add(Glyph(part.body[index], touches))
            }
            when (part.tone) {
                ZhuyinTone.NONE -> Unit
                ZhuyinTone.LEVEL -> glyphs.add(Glyph(null, part.toneTouches))
                else -> if (part.tone.mark.isNotEmpty()) glyphs.add(Glyph(part.tone.mark[0], part.toneTouches))
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
    val bodyTouches: List<List<NearbyKey>> = emptyList(),
    val toneTouches: List<NearbyKey> = emptyList(),
) {
    val display: String
        get() = body + tone.mark
}

/**
 * One neighbour swapped into the typed reading.
 * [queries] is the whole composition, in [ZhuyinComposer.queries] form.
 * [probability] is that neighbour's share of the touch.
 */
internal data class TouchCorrection(val queries: List<String>, val probability: Float)

/** Above this, the key under the finger is sure enough that its neighbours are not searched. */
internal const val TOUCH_CORRECTION_CONFIDENT = 0.85f

/** A neighbour below this is too unlikely to spend a lookup on. */
internal const val TOUCH_CORRECTION_MIN = 0.15f

/** How many single-key swaps are looked up for one composition. */
internal const val TOUCH_CORRECTION_LIMIT = 4

/** One visible bopomofo glyph, or 平聲 when [ch] is null. */
private class Glyph(val ch: Char?, val touches: List<NearbyKey>)

private fun isToneGlyph(glyph: Glyph): Boolean {
    val ch = glyph.ch ?: return true
    return ZhuyinPhonology.classify(ch) == ZhuyinClass.TONE
}

private class Syllable {
    val body = StringBuilder()
    val bodyTouches = ArrayList<List<NearbyKey>>()
    var tone: ZhuyinTone = ZhuyinTone.NONE
    var toneTouches: List<NearbyKey> = emptyList()

    fun addBody(ch: Char, touches: List<NearbyKey>) {
        body.append(ch)
        bodyTouches.add(touches)
    }

    fun display(): String = body.toString() + tone.mark

    fun query(): String = syllableQuery(body.toString(), tone)

    fun snapshot(): ZhuyinSyllable = ZhuyinSyllable(
        body.toString(),
        tone,
        if (bodyTouches.all { it.isEmpty() }) emptyList() else bodyTouches.toList(),
        toneTouches,
    )

    companion object {
        fun of(syllable: ZhuyinSyllable): Syllable = Syllable().apply {
            body.append(syllable.body)
            tone = syllable.tone
            bodyTouches.addAll(syllable.bodyTouches)
            while (bodyTouches.size < body.length) bodyTouches.add(emptyList())
            if (bodyTouches.size > body.length) {
                repeat(bodyTouches.size - body.length) { bodyTouches.removeAt(bodyTouches.lastIndex) }
            }
            toneTouches = syllable.toneTouches
        }
    }
}

/** Neighbours of [typed] that are the same bopomofo class and likely enough to search. */
private fun touchAlternatives(typed: Char, nearby: List<NearbyKey>): List<NearbyKey> {
    val own = nearby.find { it.label == typed.toString() }?.probability
    val typedProbability = own ?: if (nearby.isEmpty()) 1f else 0f
    if (typedProbability >= TOUCH_CORRECTION_CONFIDENT) return emptyList()
    val kind = ZhuyinPhonology.classify(typed) ?: return emptyList()
    return nearby
        .filter { it.probability >= TOUCH_CORRECTION_MIN && it.label.length == 1 && it.label[0] != typed }
        .filter { ZhuyinPhonology.classify(it.label[0]) == kind }
        .sortedByDescending { it.probability }
}

private fun syllableQuery(body: String, tone: ZhuyinTone): String {
    return if (tone.chosen) body + tone.mark else "$body*"
}

/** [replacement] at [index], or null when that syllable would be illegal. */
private fun substitutedBody(body: String, index: Int, replacement: Char): String? {
    if (index !in body.indices || body[index] == replacement) return null
    if (ZhuyinPhonology.classify(body[index]) != ZhuyinPhonology.classify(replacement)) return null
    val next = buildString {
        append(body, 0, index)
        append(replacement)
        append(body, index + 1, body.length)
    }
    val initial = next.firstOrNull { ZhuyinPhonology.classify(it) == ZhuyinClass.INITIAL }
    val medial = next.firstOrNull { ZhuyinPhonology.classify(it) == ZhuyinClass.MEDIAL }
    if (medial != null && !ZhuyinPhonology.medialFits(initial, medial)) return null
    return next
}
