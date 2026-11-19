package com.fskeyboard.ime.compose

import android.view.inputmethod.EditorInfo
import com.fskeyboard.ime.candidate.Candidate
import com.fskeyboard.ime.candidate.CandidateSource
import com.fskeyboard.ime.candidate.EnglishLexicon
import com.fskeyboard.ime.candidate.EnglishSuggest
import com.fskeyboard.ime.candidate.englishPrefixBefore
import com.fskeyboard.ime.candidate.isSymbol
import com.fskeyboard.ime.engine.EngineSnapshot
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.state.KeyFate

/**
 * One English word under the caret.
 * [visualOffset] is how many characters of the composition sit before [text].
 * [length] is the owned caret span, which is not always [text]'s length.
 */
internal data class CompositionCaret(
    val text: String,
    val caret: Int,
    val visualOffset: Int,
    val length: Int,
)

/** What English composition still asks the controller to do. Field writes stay on the editor session. */
internal interface EnglishHost {
    fun editorInfo(): EditorInfo?
    fun textBefore(n: Int): String?
    /** Null when the field has no caret-aware after-read. An empty string means the caret sits at the end. */
    fun textAfter(n: Int): String?
    fun deleteBefore(n: Int)
    fun commit(text: String)
    fun acceptCommitted(text: String)
    fun apply(snapshot: EngineSnapshot)
    fun finishComposing()
    fun clearUnderline()
    fun clearOwnedCaretSpan()
    fun presented(): List<Candidate>
    fun publish(snapshot: EngineSnapshot)
    /** Last composed letter was deleted. The owned span is already clear. */
    fun showClearedWord()
    fun refreshIdleSuggestions()
    fun showEmailDomainsAfterAt()
    fun isEmailContext(): Boolean
    fun beforeUsername(): String
    fun learnEnglish(word: String)
    fun recordEmail(choice: String, username: String)
    fun emailCandidates(typed: String, before: String, limit: Int): List<String>
    fun learnSymbol(symbol: String)
    fun noteSpaceUndo(typed: String, committed: String)
}

/**
 * English composition for one field. It does not read the keyboard mode.
 * Latin and the symbol pages share this word, so leaving Latin for symbols can keep it open.
 */
internal class EnglishCompositionSession(
    private val lexicon: EnglishLexicon,
    private val host: EnglishHost,
) {
    private val buffer = StringBuilder()
    private var caret = 0
    private var suggestKey: String = ""

    val active: Boolean
        get() = buffer.isNotEmpty()

    /**
     * True only when the [handle] call in progress inserted a character.
     * Cleared on entry so a later `@` is not skipped because the previous letter inserted.
     */
    var lastCharacterInserted: Boolean = false

    /**
     * True only when the [handle] call in progress consumed an English backspace.
     * [com.fskeyboard.ime.candidate.CandidateController] clears this before it asks a state,
     * so a zhuyin backspace cannot read the previous English result.
     */
    var consumedBackspace: Boolean = false

    /** Meaningful when [consumedBackspace] is true. False when the caret was already at 0. */
    private var backspaceDidMove = false

    fun backspaceMoved(): Boolean = backspaceDidMove

    fun handle(action: KeyAction, allowCompose: Boolean): KeyFate {
        lastCharacterInserted = false
        return when (action) {
            is KeyAction.Character -> onCharacter(action.value, allowCompose)
            KeyAction.Space -> onSpace()
            KeyAction.Enter -> onEnter()
            KeyAction.Backspace -> onBackspace()
            else -> KeyFate.Continue
        }
    }

    fun finish(trailing: String, takePrediction: Boolean): String? {
        if (!active) return null
        val currentTyped = buffer.toString()
        val shown = host.presented()
        val key = suggestKey.ifEmpty { currentTyped }
        val extra = if (currentTyped.isNotEmpty() && key.endsWith(currentTyped) && key.length > currentTyped.length) {
            key.length - currentTyped.length
        } else {
            0
        }
        // Drop the owned span before the field write. A mismatch still drops it, and does not write.
        clear()

        val before = host.textBefore(currentTyped.length).orEmpty()
        if (before != currentTyped && !before.endsWith(currentTyped)) {
            host.finishComposing()
            host.publish(EngineSnapshot())
            return null
        }

        val isEmail = host.isEmailContext()
        val word = if (isEmail) {
            if (takePrediction && shown.isNotEmpty()) shown.first().text else currentTyped
        } else {
            EnglishSuggest.commit(key, shown.firstOrNull()?.text, takePrediction)
        }
        val trailingChar = if (isEmail && trailing == " ") "" else trailing
        val committed = word + trailingChar
        if (extra > 0 && !isEmail) host.deleteBefore(extra)
        host.acceptCommitted(committed)
        if (isEmail) {
            host.recordEmail(word, host.beforeUsername())
        } else {
            host.learnEnglish(word)
        }
        if (isSymbol(trailingChar)) host.learnSymbol(trailingChar)
        host.refreshIdleSuggestions()
        return committed
    }

    fun clear() {
        host.clearOwnedCaretSpan()
        buffer.setLength(0)
        caret = 0
        suggestKey = ""
    }

    fun restore(typed: String) {
        buffer.setLength(0)
        buffer.append(typed)
        caret = buffer.length
        suggestKey = typed
        show()
    }

    /** Remembered field caret wins. An unknown selection leaves [caret] alone. */
    fun alignCaretToField(selStart: Int, selEnd: Int, spanStart: Int, spanEnd: Int) {
        if (!active) return
        if (selStart < 0 || selEnd < 0 || selStart != selEnd) return
        if (spanStart < 0 || spanEnd < spanStart) return
        if (selStart < spanStart || selStart > spanEnd) return
        caret = (selStart - spanStart).coerceIn(0, buffer.length)
    }

    fun reload() {
        if (active) show()
    }

    fun caretTarget(): CompositionCaret? {
        if (!active) return null
        val text = buffer.toString()
        return CompositionCaret(
            text = text,
            caret = caret,
            visualOffset = 0,
            length = text.length,
        )
    }

    fun moveCaret(next: Int) {
        caret = next
    }

    /** Commits [candidate] plus a trailing space, or without the space in an email. */
    fun acceptCandidate(candidate: Candidate): Boolean {
        if (!active) return false
        val isEmail = host.isEmailContext()
        val text = candidate.text
        val trailing = if (isEmail) "" else " "
        val currentTyped = buffer.toString()
        val key = suggestKey.ifEmpty { currentTyped }
        val extra = if (currentTyped.isNotEmpty() && key.endsWith(currentTyped) && key.length > currentTyped.length) {
            key.length - currentTyped.length
        } else {
            0
        }
        clear()
        if (extra > 0 && !isEmail) host.deleteBefore(extra)
        host.acceptCommitted(text + trailing)
        if (isEmail) {
            host.recordEmail(text, host.beforeUsername())
        } else {
            host.learnEnglish(text)
        }
        return true
    }

    /** Shape only. The caller has already required a Latin base. */
    fun adjusts(candidate: Candidate): Boolean {
        if (candidate.reading.isNotEmpty() || candidate.source == CandidateSource.CLIPBOARD) return false
        return EnglishLexicon.isValidWord(candidate.text)
    }

    private fun onCharacter(value: Char, allowCompose: Boolean): KeyFate {
        if (allowCompose && continues(value)) {
            insert(value)
            return KeyFate.Consumed
        }
        if (active) {
            finish(trailing = value.toString(), takePrediction = false)
            return KeyFate.Consumed
        }
        return KeyFate.Continue
    }

    private fun onSpace(): KeyFate {
        if (!active) return KeyFate.Continue
        val typed = buffer.toString()
        val committed = finish(trailing = " ", takePrediction = false)
        if (committed != null) host.noteSpaceUndo(typed, committed)
        return KeyFate.Consumed
    }

    private fun onEnter(): KeyFate {
        if (!active) return KeyFate.Continue
        finish(trailing = "", takePrediction = true)
        return KeyFate.Consumed
    }

    private fun onBackspace(): KeyFate {
        if (!active) return KeyFate.Continue
        return backspaceOne()
    }

    /** Set when this stroke's backspace is consumed, including a caret that was already at 0. */
    private fun backspaceOne(): KeyFate {
        consumedBackspace = true
        if (caret <= 0) {
            backspaceDidMove = false
            return KeyFate.Consumed
        }
        val previous = buffer.toString()
        buffer.deleteCharAt(caret - 1)
        caret -= 1
        backspaceDidMove = true
        val now = buffer.toString()
        suggestKey = if (suggestKey.endsWith(previous)) suggestKey.dropLast(previous.length) + now else now
        if (buffer.isEmpty()) {
            clear()
            host.showClearedWord()
        } else {
            show()
        }
        return KeyFate.Consumed
    }

    /** A letter always extends the word. An apostrophe, hyphen, or digit does only after one has started. */
    private fun continues(value: Char): Boolean {
        if (value.isLetter()) return true
        if (buffer.isEmpty()) return false
        return value == '\'' || value == '-' || value.isDigit()
    }

    private fun insert(value: Char) {
        val at = caret.coerceIn(0, buffer.length)
        val previous = buffer.toString()
        val asciiLetter = value in 'a'..'z' || value in 'A'..'Z'
        // Only a field with a real caret (textAfter reads) may be read to the left.
        // Otherwise the committed text would be spliced onto the composing buffer.
        val left = if (asciiLetter && at == previous.length && host.textAfter(1) != null) {
            host.textBefore(64).orEmpty()
        } else {
            null
        }
        buffer.insert(at, value)
        caret = at + 1
        lastCharacterInserted = true
        val now = buffer.toString()
        suggestKey = if (left != null) {
            val prior = englishPrefixBefore(left)
            if (previous.isNotEmpty() && prior.endsWith(previous)) {
                prior.dropLast(previous.length) + now
            } else if (previous.isEmpty()) {
                prior + now
            } else {
                now
            }
        } else if (suggestKey.endsWith(previous)) {
            suggestKey.dropLast(previous.length) + now
        } else {
            now
        }
        show()
    }

    private fun show() {
        val typed = buffer.toString()
        val before = host.textBefore(64).orEmpty()
        val start = System.nanoTime()
        val candidates: List<Candidate> = if (host.isEmailContext()) {
            val suggestions = host.emailCandidates(typed, before, ENGLISH_CANDIDATES)
            suggestions.mapIndexed { index, text ->
                Candidate(
                    text = text,
                    engineIndex = -(index + 1),
                    source = CandidateSource.SUGGESTION,
                    span = 0,
                )
            }
        } else {
            val lookup = suggestKey.ifEmpty { typed }
            val hits = lexicon.complete(lookup.lowercase(), ENGLISH_CANDIDATES)
            val texts = EnglishSuggest.candidates(lookup, hits, ENGLISH_CANDIDATES)
            texts.mapIndexed { index, text ->
                Candidate(
                    text = text,
                    engineIndex = -(index + 1),
                    source = CandidateSource.SUGGESTION,
                    span = 0,
                )
            }
        }
        val lookupMillis = (System.nanoTime() - start) / 1_000_000L
        host.apply(
            EngineSnapshot(
                preedit = typed,
                composingPreview = typed,
                candidates = candidates,
                decodeMillis = lookupMillis,
            ),
        )
    }

    private companion object {
        const val ENGLISH_CANDIDATES = 8
    }
}
