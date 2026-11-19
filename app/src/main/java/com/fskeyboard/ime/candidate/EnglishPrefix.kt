package com.fskeyboard.ime.candidate

/** ASCII English word touching the end of [text]. Case is kept. No letter means empty. */
internal fun englishPrefixBefore(text: String): String {
    var i = text.length
    while (i > 0) {
        val ch = text[i - 1]
        if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '\'' || ch == '-') {
            i--
        } else {
            break
        }
    }
    val raw = text.substring(i)
    var start = 0
    while (start < raw.length) {
        val ch = raw[start]
        if (ch in 'a'..'z' || ch in 'A'..'Z') break
        start++
    }
    if (start == raw.length) return ""
    return raw.substring(start)
}
