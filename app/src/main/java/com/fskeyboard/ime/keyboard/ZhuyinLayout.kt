package com.fskeyboard.ime.keyboard

/**
 * Standard Taiwan bopomofo positions, shared by the soft keyboard and the
 * schema algebra. Each token is the glyph plus the ASCII key on that position.
 * The speller only consumes ASCII, so the glyph is never the keysym.
 */
internal object ZhuyinLayout {
    data class Key(val label: String, val key: Char, val tone: Boolean)

    private val tones = setOf("ˇ", "ˋ", "ˊ", "˙")

    val rows: List<List<Key>> = listOf(
        row("ㄅ1", "ㄉ2", "ˇ3", "ˋ4", "ㄓ5", "ˊ6", "˙7", "ㄚ8", "ㄞ9", "ㄢ0", "ㄦ-"),
        row("ㄆq", "ㄊw", "ㄍe", "ㄐr", "ㄔt", "ㄗy", "ㄧu", "ㄛi", "ㄟo", "ㄣp"),
        row("ㄇa", "ㄋs", "ㄎd", "ㄑf", "ㄕg", "ㄘh", "ㄨj", "ㄜk", "ㄠl", "ㄤ;"),
        row("ㄈz", "ㄌx", "ㄏc", "ㄒv", "ㄖb", "ㄙn", "ㄩm", "ㄝ,", "ㄡ.", "ㄥ/"),
    )

    val keys: List<Key> = rows.flatten()

    const val ALPHABET = "1234567890-qwertyuiopasdfghjkl;zxcvbnm,./"

    private fun row(vararg tokens: String): List<Key> {
        return tokens.map { token ->
            val label = token.dropLast(1)
            Key(label = label, key = token.last(), tone = label in tones)
        }
    }
}
