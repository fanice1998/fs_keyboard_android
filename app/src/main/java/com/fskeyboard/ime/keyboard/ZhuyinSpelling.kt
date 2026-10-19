package com.fskeyboard.ime.keyboard

/**
 * The schema speller only accepts the ASCII key on each standard key position.
 * Those codes are not pinyin and must not be committed. This maps both ways.
 */
internal object ZhuyinSpelling {
    private val glyphToKey: Map<Char, Char>
    private val keyToGlyph: Map<Char, Char>

    init {
        val glyphs = HashMap<Char, Char>()
        val keys = HashMap<Char, Char>()
        for (key in ZhuyinLayout.keys) {
            require(key.label.length == 1) { key.label }
            val glyph = key.label[0]
            glyphs[glyph] = key.key
            keys[key.key] = glyph
        }
        glyphToKey = glyphs
        keyToGlyph = keys
    }

    fun keysOf(glyphs: String): String {
        val encoded = StringBuilder(glyphs.length)
        for (glyph in glyphs) {
            encoded.append(glyphToKey[glyph] ?: return "")
        }
        return encoded.toString()
    }

    /** True when [raw] is only key-position codes such as "8" for ㄚ. */
    fun isKeySpelling(raw: String): Boolean {
        return raw.isNotEmpty() && raw.all { it in keyToGlyph }
    }

    /**
     * Accepts either zhuyin glyphs or key-position codes.
     * Returns null when the string is neither, so a real commit is left alone.
     */
    fun toGlyphs(raw: String): String? {
        if (raw.isEmpty()) return ""
        if (raw.all { ZhuyinPhonology.classify(it) != null }) return raw
        if (!isKeySpelling(raw)) return null
        return raw.map { keyToGlyph.getValue(it) }.joinToString("")
    }
}
