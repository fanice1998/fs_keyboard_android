package com.fskeyboard.ime.engine

/**
 * JSON reader for the lexicon release documents. Unit tests run against the
 * Android SDK stub, whose `org.json` throws, so this stays on the JDK.
 */
internal object MinimalJson {
    fun parse(text: String): Any? = Parser(text).parseValue()

    private class Parser(private val text: String) {
        private var index = 0

        fun parseValue(): Any? {
            skip()
            if (index >= text.length) error("truncated json")
            return when (text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            index++
            val values = linkedMapOf<String, Any?>()
            skip()
            if (peek('}')) {
                index++
                return values
            }
            while (index < text.length) {
                skip()
                val key = parseString()
                skip()
                expect(':')
                values[key] = parseValue()
                skip()
                when {
                    peek(',') -> index++
                    peek('}') -> {
                        index++
                        return values
                    }
                    else -> error("expected object delimiter")
                }
            }
            error("truncated object")
        }

        private fun parseArray(): List<Any?> {
            index++
            val values = mutableListOf<Any?>()
            skip()
            if (peek(']')) {
                index++
                return values
            }
            while (index < text.length) {
                values += parseValue()
                skip()
                when {
                    peek(',') -> index++
                    peek(']') -> {
                        index++
                        return values
                    }
                    else -> error("expected array delimiter")
                }
            }
            error("truncated array")
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (index < text.length) {
                val char = text[index++]
                when (char) {
                    '"' -> return out.toString()
                    '\\' -> out.append(escape())
                    else -> out.append(char)
                }
            }
            error("truncated string")
        }

        private fun escape(): Char {
            if (index >= text.length) error("truncated escape")
            return when (val char = text[index++]) {
                '"', '\\', '/' -> char
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (index + 4 > text.length) error("truncated unicode escape")
                    val code = text.substring(index, index + 4).toInt(16)
                    index += 4
                    code.toChar()
                }
                else -> error("bad escape")
            }
        }

        private fun parseNumber(): Any {
            val start = index
            if (peek('-')) index++
            while (index < text.length && text[index].isDigit()) index++
            var real = false
            if (peek('.')) {
                real = true
                index++
                while (index < text.length && text[index].isDigit()) index++
            }
            if (peek('e') || peek('E')) {
                real = true
                index++
                if (peek('+') || peek('-')) index++
                while (index < text.length && text[index].isDigit()) index++
            }
            val token = text.substring(start, index)
            return if (real) token.toDouble() else token.toLong()
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, index)) error("expected $word")
            index += word.length
            return value
        }

        private fun skip() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun expect(char: Char) {
            if (!peek(char)) error("expected $char")
            index++
        }

        private fun peek(char: Char) = index < text.length && text[index] == char
    }
}
