package com.fskeyboard.ime.candidate

import com.fskeyboard.ime.keyboard.ZhuyinTone

/**
 * ChiaKey stores each syllable as two ASCII bytes from [absolute order][encode].
 * Tone 1 has no tone bits. [ZhuyinTone.NONE] is only for a syllable the user has not marked.
 */
internal object BopomofoOrder {
    fun qstring(syllables: List<String>): String? {
        if (syllables.isEmpty()) return null
        val out = StringBuilder(syllables.size * 2)
        for (syllable in syllables) {
            out.append(encodeSyllable(syllable) ?: return null)
        }
        return out.toString()
    }

    fun decode(qstring: String): List<SyllableReading>? {
        if (qstring.isEmpty() || qstring.length % 2 != 0) return null
        val out = ArrayList<SyllableReading>(qstring.length / 2)
        var index = 0
        while (index < qstring.length) {
            val low = qstring[index].code
            val high = qstring[index + 1].code
            if (low !in ORDER_BASE until ORDER_BASE + ORDER_RADIX) return null
            if (high !in ORDER_BASE until ORDER_BASE + ORDER_RADIX) return null
            val order = (high - ORDER_BASE) * ORDER_RADIX + (low - ORDER_BASE)
            val syllable = fromAbsoluteOrder(order)
            if (syllable == 0) return null
            val reading = readingOf(syllable) ?: return null
            out += reading
            index += 2
        }
        return out
    }

    private fun encodeSyllable(text: String): String? {
        if (text.isEmpty()) return null
        var syllable = 0
        for (ch in text) {
            val component = COMPONENT[ch] ?: return null
            val mask = maskOf(component)
            syllable = (syllable and mask.inv()) or component
        }
        if (syllable == 0) return null
        return orderString(absoluteOrder(syllable))
    }

    private fun absoluteOrder(syllable: Int): Int {
        return (syllable and CONSONANT) +
            ((syllable and MEDIAL) shr 5) * 22 +
            ((syllable and VOWEL) shr 7) * 22 * 4 +
            ((syllable and TONE) shr 11) * 22 * 4 * 14
    }

    private fun fromAbsoluteOrder(order: Int): Int {
        return (order % 22) or
            (((order / 22) % 4) shl 5) or
            (((order / (22 * 4)) % 14) shl 7) or
            (((order / (22 * 4 * 14)) % 5) shl 11)
    }

    private fun orderString(order: Int): String {
        val low = (ORDER_BASE + order % ORDER_RADIX).toChar()
        val high = (ORDER_BASE + order / ORDER_RADIX).toChar()
        return charArrayOf(low, high).concatToString()
    }

    private fun readingOf(syllable: Int): SyllableReading? {
        val body = buildString {
            appendGlyph(syllable and CONSONANT)
            appendGlyph(syllable and MEDIAL)
            appendGlyph(syllable and VOWEL)
        }
        if (body.isEmpty()) return null
        val shared = share(body)
        val tone = when (syllable and TONE) {
            0 -> ZhuyinTone.LEVEL
            TONE2 -> ZhuyinTone.SECOND
            TONE3 -> ZhuyinTone.THIRD
            TONE4 -> ZhuyinTone.FOURTH
            TONE5 -> ZhuyinTone.NEUTRAL
            else -> return null
        }
        return SyllableReading(shared, tone)
    }

    /** Bopomofo bodies repeat across the lexicon. One copy per spelling. */
    private fun share(body: String): String = synchronized(sharedBodies) {
        sharedBodies.getOrPut(body) { body }
    }

    private fun StringBuilder.appendGlyph(component: Int) {
        if (component == 0) return
        val glyph = GLYPH[component] ?: return
        append(glyph)
    }

    private fun maskOf(component: Int): Int = when {
        component and CONSONANT != 0 -> CONSONANT
        component and MEDIAL != 0 -> MEDIAL
        component and VOWEL != 0 -> VOWEL
        else -> TONE
    }

    private const val ORDER_BASE = 48
    private const val ORDER_RADIX = 79
    private const val CONSONANT = 0x001f
    private const val MEDIAL = 0x0060
    private const val VOWEL = 0x0780
    private const val TONE = 0x3800
    private const val TONE2 = 0x0800
    private const val TONE3 = 0x1000
    private const val TONE4 = 0x1800
    private const val TONE5 = 0x2000

    private val COMPONENT = mapOf(
        'ㄅ' to 0x0001, 'ㄆ' to 0x0002, 'ㄇ' to 0x0003, 'ㄈ' to 0x0004,
        'ㄉ' to 0x0005, 'ㄊ' to 0x0006, 'ㄋ' to 0x0007, 'ㄌ' to 0x0008,
        'ㄍ' to 0x0009, 'ㄎ' to 0x000a, 'ㄏ' to 0x000b, 'ㄐ' to 0x000c,
        'ㄑ' to 0x000d, 'ㄒ' to 0x000e, 'ㄓ' to 0x000f, 'ㄔ' to 0x0010,
        'ㄕ' to 0x0011, 'ㄖ' to 0x0012, 'ㄗ' to 0x0013, 'ㄘ' to 0x0014,
        'ㄙ' to 0x0015, 'ㄧ' to 0x0020, 'ㄨ' to 0x0040, 'ㄩ' to 0x0060,
        'ㄚ' to 0x0080, 'ㄛ' to 0x0100, 'ㄜ' to 0x0180, 'ㄝ' to 0x0200,
        'ㄞ' to 0x0280, 'ㄟ' to 0x0300, 'ㄠ' to 0x0380, 'ㄡ' to 0x0400,
        'ㄢ' to 0x0480, 'ㄣ' to 0x0500, 'ㄤ' to 0x0580, 'ㄥ' to 0x0600,
        'ㄦ' to 0x0680, 'ˊ' to TONE2, 'ˇ' to TONE3, 'ˋ' to TONE4, '˙' to TONE5,
    )

    private val GLYPH = COMPONENT.entries.associate { (glyph, component) -> component to glyph }
    private val sharedBodies = HashMap<String, String>(512)
}
