package com.fskeyboard.ime.keyboard

import java.text.BreakIterator
import kotlin.math.abs

/**
 * Moves a cursor by graphemes horizontally and by newline-delimited lines vertically.
 * A horizontal step stops at the ends of its line, so it does not wrap to the next one.
 * A vertical step keeps the column, and never lands in the middle of a grapheme.
 */
internal object CursorMotion {
    fun move(text: String, cursor: Int, dx: Int, dy: Int): Int {
        var index = cursor.coerceIn(0, text.length)
        if (dx != 0) index = shiftGraphemes(text, index, dx)
        if (dy != 0) index = shiftLines(text, index, dy)
        return index
    }

    private fun shiftGraphemes(text: String, cursor: Int, delta: Int): Int {
        val start = lineStart(text, cursor)
        val end = lineEnd(text, cursor)
        val breaks = BreakIterator.getCharacterInstance()
        breaks.setText(text)
        var index = cursor.coerceIn(start, end)
        repeat(abs(delta)) {
            val next = if (delta > 0) breaks.following(index) else breaks.preceding(index)
            if (next == BreakIterator.DONE || next < start || next > end) return index
            index = next
        }
        return index
    }

    private fun shiftLines(text: String, cursor: Int, lines: Int): Int {
        val column = cursor - lineStart(text, cursor)
        var start = lineStart(text, cursor)
        repeat(abs(lines)) {
            if (lines > 0) {
                val end = lineEnd(text, start)
                if (end >= text.length) return snap(text, (start + column).coerceAtMost(end))
                start = end + 1
            } else {
                if (start == 0) return snap(text, column.coerceAtMost(lineEnd(text, 0)))
                start = lineStart(text, start - 1)
            }
        }
        val end = lineEnd(text, start)
        return snap(text, (start + column).coerceIn(start, end))
    }

    private fun lineStart(text: String, cursor: Int): Int {
        if (cursor <= 0) return 0
        val newline = text.lastIndexOf('\n', cursor - 1)
        return if (newline < 0) 0 else newline + 1
    }

    private fun lineEnd(text: String, start: Int): Int {
        val newline = text.indexOf('\n', start)
        return if (newline < 0) text.length else newline
    }

    /** Pulls an index that split a grapheme back to that grapheme's start. */
    private fun snap(text: String, index: Int): Int {
        val clamped = index.coerceIn(0, text.length)
        if (clamped == 0 || clamped == text.length) return clamped
        val breaks = BreakIterator.getCharacterInstance()
        breaks.setText(text)
        if (breaks.isBoundary(clamped)) return clamped
        val previous = breaks.preceding(clamped)
        return if (previous == BreakIterator.DONE) 0 else previous
    }
}
