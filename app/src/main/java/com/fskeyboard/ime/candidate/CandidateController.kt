package com.fskeyboard.ime.candidate

import android.content.Context
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.fskeyboard.ime.R
import com.fskeyboard.ime.engine.EngineSnapshot
import com.fskeyboard.ime.engine.EngineStatus
import com.fskeyboard.ime.engine.KeyPolicy
import com.fskeyboard.ime.engine.ZhuyinLexicon
import com.fskeyboard.ime.keyboard.ComputerKey
import com.fskeyboard.ime.keyboard.ComputerModifiers
import com.fskeyboard.ime.keyboard.CursorMotion
import com.fskeyboard.ime.keyboard.EnterActions
import com.fskeyboard.ime.keyboard.composingSelection
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.keyboard.KeyboardMode
import com.fskeyboard.ime.keyboard.computerKeyCode
import com.fskeyboard.ime.keyboard.ZhuyinComposer
import com.fskeyboard.ime.rerank.CandidatePipeline
import com.fskeyboard.ime.rerank.RerankFallback
import com.fskeyboard.ime.rerank.RerankLimits
import com.fskeyboard.ime.rerank.RerankTrace
import com.fskeyboard.ime.rerank.acceptRerankUpdate
import com.fskeyboard.ime.traced
import java.io.File
import java.util.concurrent.Executors

internal class CandidateController(
    private val context: Context,
    private val lexicon: ZhuyinLexicon,
    private val pipeline: CandidatePipeline,
    private val connection: () -> InputConnection?,
    private val editorInfo: () -> EditorInfo?,
    private val onUi: (ImeUiState) -> Unit,
    private val post: (Runnable) -> Unit = { runnable ->
        Handler(Looper.getMainLooper()).post(runnable)
    },
    private val letterMode: () -> KeyboardMode = { KeyboardMode.ZHUYIN },
    private val clipboard: () -> ClipOffer? = { readClipboard(context) },
) {
    /** Ctrl, Alt, and Meta chosen from an English swipe, until the next key. */
    var onComputerModifiers: (Set<ComputerKey>) -> Unit = {}

    /** Modifiers locked by a hold. They stay after the finger lifts. */
    var onLockedModifiers: (Set<ComputerKey>) -> Unit = {}

    /** The user switched between zhuyin and Latin. An email field does not call this. */
    var onLetterMode: (KeyboardMode) -> Unit = {}

    private val modifiers = ComputerModifiers()
    private var mode = KeyboardMode.ZHUYIN

    /** The letter keyboard the symbol pages return to. */
    private var base = KeyboardMode.ZHUYIN
    private var presented: List<Candidate> = emptyList()
    private val composition = ZhuyinComposition()

    /** The last characters this keyboard committed, for choosing particles. */
    private val recent = StringBuilder()

    /** Letters of the English word under the caret. Empty unless the Latin keyboard is composing. */
    private val english = StringBuilder()
    private var englishCaret = 0
    private val englishLexicon = EnglishLexicon()
    private val symbolFrequency = SymbolFrequency()
    private val emailSuggest = EmailSuggest()
    private val englishWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fskeyboard-english").apply { isDaemon = true }
    }
    private val userEnglishFile by lazy { File(context.filesDir, USER_ENGLISH_FILE) }
    private val symbolFile by lazy { File(context.filesDir, SYMBOL_FILE) }
    private val emailHistoryFile by lazy { File(context.filesDir, EMAIL_DOMAINS_FILE) }
    private var englishLoaded = false
    private var generation = 0L
    private var closed = false
    private var lastLookupMillis = 0L
    private var cursorSession: CursorSession? = null
    private var composingGlide = false
    private var glideOrigin = 0
    private var glideText = ""
    private var glideBase = -1
    private var glideVisualOffset = 0

    /**
     * Inclusive caret positions of the composing text, remembered while a glide
     * calls setSelection. A late selection update from that call still lies inside
     * the word, and must not drop the composition.
     */
    private var ownedCaretSpan: IntRange? = null

    /** Widths for a space glide. Created on the first glide so unit tests never touch Paint. */
    private val caretPaint: Paint by lazy {
        Paint().apply {
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                16f,
                context.resources.displayMetrics,
            )
        }
    }
    private var selStart = -1
    private var selEnd = -1

    /** Clipboard read for this field. Cleared from the row once the user types. */
    private var clipOffer: ClipOffer? = null
    private var clipVisible = false

    private val hasComposition: Boolean
        get() = !composition.isEmpty

    fun start() {
        applySavedLetterMode()
        loadEnglish()
        loadSymbolFrequency()
        publish()
        lexicon.start(context) {
            if (!closed) {
                if (hasComposition) apply(refresh()) else publish(EngineSnapshot())
            }
        }
    }

    fun close() {
        closed = true
        generation += 1
        endCursor()
        lexicon.close()
        englishWorker.shutdown()
    }

    fun onStartInput(restarting: Boolean) {
        val info = editorInfo()
        selStart = info?.initialSelStart ?: -1
        selEnd = info?.initialSelEnd ?: -1
        if (!restarting) {
            recent.setLength(0)
        }
        cancelComposition()
        clearModifiers()
        refreshClipboard()
        if (emailSuggest.isEmailField(info)) {
            // This field opens in Latin. That must not replace the keyboard the user chose.
            mode = KeyboardMode.LATIN
            base = KeyboardMode.LATIN
        } else {
            applySavedLetterMode()
        }
        publish()
        refreshSymbolSuggestions()
    }

    fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        selStart = newSelStart
        selEnd = newSelEnd
        if (candidatesStart == -1 && candidatesEnd == -1) {
            if (composingGlide) return
            val owned = ownedCaretSpan
            if (owned != null && newSelStart == newSelEnd && newSelStart in owned) return
            ownedCaretSpan = null
            var changed = false
            if (english.isNotEmpty()) {
                clearEnglishWord()
                changed = true
            }
            if (hasComposition) {
                composition.clear()
                changed = true
            }
            if (changed) {
                presented = emptyList()
                publish(EngineSnapshot())
            }
            if (cursorSession == null && !hasComposition && english.isEmpty() && newSelStart == newSelEnd) {
                refreshSymbolSuggestions()
            }
        }
    }

    fun onFinishInput() {
        endCursor()
        commitEnglishLiteral()
        cancelComposition()
        selStart = -1
        selEnd = -1
    }

    /** Space can glide the editor caret, or the caret inside zhuyin or an English word. */
    fun canMoveCursor(): Boolean = !closed

    /**
     * Takes the editor text once. Later samples reuse it, so a drag does not re-read the field.
     * While zhuyin or an English word is open, the glide stays inside that composition and
     * does not read the field. False when there is nothing to move; the space bar then stays a tap.
     */
    fun beginCursor(): Boolean {
        if (!canMoveCursor()) return false
        if (english.isNotEmpty()) {
            glideText = english.toString()
            glideOrigin = englishCaret.coerceIn(0, glideText.length)
            glideVisualOffset = 0
            glideBase = compositionAnchor(glideOrigin)
            rememberOwnedSpan(glideBase, glideText.length)
            composingGlide = true
            cursorSession = null
            return true
        }
        if (hasComposition) {
            glideText = composition.pending.text
            glideOrigin = composition.pending.caret.coerceIn(0, glideText.length)
            glideVisualOffset = composition.chosenText.length
            glideBase = compositionAnchor(glideVisualOffset + glideOrigin)
            rememberOwnedSpan(glideBase, composition.text.length)
            composingGlide = true
            cursorSession = null
            return true
        }
        val session = readCursor() ?: return false
        dismissSuggestions()
        cursorSession = session
        glideOrigin = (session.cursor - session.startOffset).coerceIn(0, session.text.length)
        composingGlide = false
        return true
    }

    /**
     * [dxPx] and [dyPx] are pixels from where the glide engaged.
     * False when the snapped caret did not move.
     */
    fun moveCursor(dxPx: Float, dyPx: Float): Boolean {
        if (composingGlide) return moveComposingCaret(dxPx, dyPx)
        val session = cursorSession ?: return false
        val moved = CursorMotion.nearest(
            session.text,
            glideOrigin,
            dxPx,
            dyPx,
            ::textWidth,
            lineHeight(),
        )
        val absolute = session.startOffset + moved
        if (absolute == session.cursor) return false
        val applied = try {
            connection()?.setSelection(absolute, absolute) == true
        } catch (_: Exception) {
            false
        }
        if (!applied) return false
        session.cursor = absolute
        return true
    }

    fun endCursor() {
        cursorSession = null
        composingGlide = false
        glideText = ""
        glideBase = -1
    }

    fun onKey(action: KeyAction) {
        if (action is KeyAction.Computer) {
            onComputer(action.key)
            return
        }
        if (modifiers.isActive() && sendModified(action)) return
        val composeEnglish = EnglishInputPolicy.shouldCompose(editorInfo())
        val typingEnglish = mode == KeyboardMode.LATIN && composeEnglish &&
            (english.isNotEmpty() || (action is KeyAction.Character && action.value.isLetter()))
        if (action !is KeyAction.Bopomofo && !typingEnglish) dismissSuggestions()
        when (action) {
            is KeyAction.SetMode -> setMode(action.mode)
            KeyAction.Shift -> Unit
            KeyAction.NextInputMethod -> Unit
            KeyAction.Backspace -> onBackspace()
            KeyAction.Enter -> onEnter()
            KeyAction.Space -> onSpace()
            is KeyAction.Character -> onCharacter(action.value)
            is KeyAction.Bopomofo -> onBopomofo(action)
            is KeyAction.Punctuation -> onPunctuation(action.text)
            is KeyAction.Computer -> Unit
        }
    }

    /**
     * Locks [key] after a long press, or unlocks it when that key is tapped again.
     * Releasing the finger does not call this with down false.
     */
    fun setModifierHeld(key: ComputerKey, down: Boolean) {
        modifiers.hold(key, down)
        publishModifiers()
    }

    /**
     * A symbol is typed and a tap-armed modifier is cleared. A held modifier stays.
     * A modifier waits. Any other computer key is sent, with the waiting modifiers, then the tap-armed ones clear.
     */
    private fun onComputer(key: ComputerKey) {
        val symbol = key.symbol
        if (symbol != null) {
            clearLatched()
            onPunctuation(symbol.toString())
            return
        }
        if (key.modifier) {
            modifiers.toggle(key)
            publishModifiers()
            return
        }
        dismissSuggestions()
        hideClipboard()
        if (english.isNotEmpty()) finishEnglish(trailing = "", takePrediction = false)
        if (mode == KeyboardMode.ZHUYIN && hasComposition) finishComposition(convertAll())
        sendKeyEvent(key.keyCode, modifiers.metaState())
        clearLatched()
    }

    /**
     * The next letter, space, enter, or backspace after Ctrl, Alt, or Meta.
     * Returns false when this key has no key code, so the normal path still runs.
     */
    private fun sendModified(action: KeyAction): Boolean {
        val shift = action is KeyAction.Character && action.value.isLetter() && action.value.isUpperCase()
        val keyCode = when (action) {
            is KeyAction.Character -> computerKeyCode(action.value)
            KeyAction.Space -> KeyEvent.KEYCODE_SPACE
            KeyAction.Enter -> KeyEvent.KEYCODE_ENTER
            KeyAction.Backspace -> KeyEvent.KEYCODE_DEL
            else -> null
        }
        if (keyCode == null) {
            clearLatched()
            return false
        }
        dismissSuggestions()
        hideClipboard()
        if (english.isNotEmpty()) finishEnglish(trailing = "", takePrediction = false)
        if (mode == KeyboardMode.ZHUYIN && hasComposition) finishComposition(convertAll())
        sendKeyEvent(keyCode, modifiers.metaState(shift))
        clearLatched()
        return true
    }

    /** Drops a tap-armed modifier. A locked modifier stays until its key is tapped again. */
    private fun clearLatched() {
        if (!modifiers.hasLatched()) return
        modifiers.clearLatched()
        publishModifiers()
    }

    private fun clearModifiers() {
        if (!modifiers.isActive()) return
        modifiers.clear()
        publishModifiers()
    }

    private fun publishModifiers() {
        onComputerModifiers(modifiers.active())
        onLockedModifiers(modifiers.locked())
    }

    /**
     * Navigation keys leave touch mode unless they are marked as a soft-keyboard event.
     * The editor then paints a gray focus wash over the field.
     */
    private fun sendKeyEvent(keyCode: Int, meta: Int) {
        val ic = connection() ?: return
        ic.finishComposingText()
        val time = System.currentTimeMillis()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        ic.sendKeyEvent(softKeyEvent(time, KeyEvent.ACTION_DOWN, keyCode, meta, flags))
        ic.sendKeyEvent(softKeyEvent(time, KeyEvent.ACTION_UP, keyCode, meta, flags))
    }

    private fun softKeyEvent(time: Long, action: Int, keyCode: Int, meta: Int, flags: Int): KeyEvent {
        return KeyEvent(
            time,
            time,
            action,
            keyCode,
            0,
            meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            flags,
        )
    }

    /**
     * Chooses [candidate] for the front syllables it spans. The rest stays to be chosen.
     * A suggestion, offered with nothing typed, is committed as it is.
     */
    fun onCandidate(candidate: Candidate) {
        if (candidate.source == CandidateSource.CLIPBOARD) {
            pasteClipboard()
            return
        }
        if (mode == KeyboardMode.LATIN && english.isNotEmpty()) {
            val isEmail = isEmailContext()
            val text = candidate.text
            val trailing = if (isEmail) "" else " "
            clearEnglishWord()
            acceptCommitted(text + trailing)
            if (isEmail) {
                emailSuggest.recordChoice(text, beforeUsername())
                saveEmailHistory()
            } else {
                learnEnglish(text)
            }
            return
        }
        if (mode != KeyboardMode.ZHUYIN || !hasComposition) {
            val isEmail = isEmailContext()
            val text = candidate.text
            commit(text)
            if (isEmail) {
                emailSuggest.recordChoice(text, beforeUsername())
                saveEmailHistory()
            }
            if (isSymbol(text)) {
                learnSymbol(textBeforeCursor(), text)
                refreshSymbolSuggestions()
            } else {
                dismissSuggestions()
            }
            return
        }
        val finished = composition.choose(candidate)
        if (finished != null) {
            finishComposition(finished)
        } else {
            apply(refresh())
        }
    }

    /** Leaving zhuyin commits what was typed, the way Enter does. */
    private fun setMode(next: KeyboardMode) {
        if (next == mode) return
        val switchingBetweenLatinAndSymbols = (mode == KeyboardMode.LATIN && next.isSymbols) ||
            (mode.isSymbols && next == KeyboardMode.LATIN)
        if (!switchingBetweenLatinAndSymbols && english.isNotEmpty()) {
            finishEnglish(trailing = "", takePrediction = false)
        }
        clearModifiers()
        if (mode == KeyboardMode.ZHUYIN && hasComposition) {
            finishComposition(convertAll())
        }
        if (next.isLetters && next != base) onLetterMode(next)
        if (next.isLetters) base = next
        mode = next
        presented = emptyList()
        publish()
    }

    private fun onBopomofo(action: KeyAction.Bopomofo) {
        hideClipboard()
        if (mode != KeyboardMode.ZHUYIN) {
            commit(action.label)
            return
        }
        if (!composition.append(action.label, action.nearby)) return
        apply(refresh())
    }

    /** A lone syllable lists the particles that fit what was typed before it first. */
    private fun refresh(): EngineSnapshot {
        val text = composition.text
        val pending = composition.pending
        var candidates = candidatesFor(pending, lookup(pending))
        if (pending.size == 1 && text == pending.text) {
            candidates = Particles.promote(candidates, recent.toString())
        }
        return EngineSnapshot(
            preedit = text,
            composingPreview = text,
            candidates = candidates,
            decodeMillis = lastLookupMillis,
        )
    }

    private fun lookup(pending: ZhuyinComposer): List<Candidate> {
        val queries = pending.queries() ?: run {
            lastLookupMillis = 0
            return emptyList()
        }
        if (!lexicon.isReady) {
            lastLookupMillis = 0
            return emptyList()
        }
        val before = recent.toString() + composition.chosenText
        val start = System.nanoTime()
        val exact = traced("ime.lookup") { lexicon.lookup(queries, before) }
        val corrected = ArrayList<Candidate>()
        for (correction in pending.corrections()) {
            corrected += lexicon.lookup(correction.queries, before)
        }
        lastLookupMillis = (System.nanoTime() - start) / 1_000_000L
        return mergeTouchCorrections(exact, corrected)
    }

    private fun onPunctuation(text: String) {
        if (english.isNotEmpty()) {
            finishEnglish(trailing = text, takePrediction = false)
            return
        }
        if (mode == KeyboardMode.ZHUYIN && hasComposition) {
            finishComposition(convertAll())
        }
        commit(text)
        if (isSymbol(text)) learnSymbol(textBeforeCursor(), text)
        refreshSymbolSuggestions()
    }

    private fun onSpace() {
        hideClipboard()
        if (english.isNotEmpty()) {
            finishEnglish(trailing = " ", takePrediction = false)
            return
        }
        when (KeyPolicy.space(mode, composing = hasComposition)) {
            KeyPolicy.Space.MarkLevelTone -> when {
                composition.pending.isEmpty -> finishComposition(convertAll())
                composition.markLevelTone() -> apply(refresh())
            }
            KeyPolicy.Space.CommitSpace -> {
                commit(" ")
                refreshSymbolSuggestions()
            }
        }
    }

    private fun onBackspace() {
        if (english.isNotEmpty()) {
            if (englishCaret <= 0) return
            english.deleteCharAt(englishCaret - 1)
            englishCaret -= 1
            if (english.isEmpty()) {
                englishCaret = 0
                ownedCaretSpan = null
                presented = emptyList()
                val ic = connection()
                ic?.setComposingText("", 1)
                ic?.finishComposingText()
                publish(EngineSnapshot())
            } else {
                showEnglish()
            }
            return
        }
        if (mode == KeyboardMode.ZHUYIN && hasComposition) {
            composition.backspace()
            apply(refresh())
            return
        }

        val ic = connection() ?: return
        val selected = try {
            ic.getSelectedText(0)
        } catch (_: Exception) {
            null
        }
        val hasSelection = !selected.isNullOrEmpty() || (selStart >= 0 && selEnd >= 0 && selStart != selEnd)
        if (hasSelection) {
            ic.commitText("", 1)
            selStart = -1
            selEnd = -1
            recent.setLength(0)
            return
        }

        ic.deleteSurroundingText(1, 0)
        if (recent.isNotEmpty()) recent.setLength(recent.length - 1)
    }

    private fun onEnter() {
        if (english.isNotEmpty()) {
            finishEnglish(trailing = "", takePrediction = true)
            return
        }
        if (mode == KeyboardMode.ZHUYIN && hasComposition) {
            finishComposition(convertAll())
            return
        }
        performEnter()
    }

    /** The first step uses the candidate on screen, so Enter matches what the bar shows. */
    private fun convertAll(): Commit {
        return composition.convertAll(presented.firstOrNull()) { pending ->
            candidatesFor(pending, lookup(pending))
        }
    }

    /** Commits, learns the phrases the user put together, and offers symbols learned after this word. */
    private fun finishComposition(done: Commit) {
        composition.clear()
        val ic = connection()
        ic?.commitText(done.text, 1)
        ic?.finishComposingText()
        remember(done.text)
        lexicon.learn(done.learned)
        refreshSymbolSuggestions()
    }

    /**
     * Symbols learned after the word touching the caret.
     * A space or no text before the caret clears them. Composition and the English word
     * keep their own candidates.
     */
    private fun refreshSymbolSuggestions() {
        if (closed || hasComposition || english.isNotEmpty() || !mode.isLetters) return
        if (selStart >= 0 && selEnd >= 0 && selStart != selEnd) return
        val symbols = symbolFrequency.predict(textBeforeCursor())
        val candidates = symbols.mapIndexed { index, text ->
            Candidate(
                text = text,
                engineIndex = -(index + 1),
                source = CandidateSource.SUGGESTION,
                span = 0,
            )
        }
        if (candidates == presented) return
        presented = candidates
        publish(EngineSnapshot(candidates = candidates))
    }

    /** The field text before the caret, or the characters this keyboard just committed. */
    private fun textBeforeCursor(): String {
        return try {
            connection()?.getTextBeforeCursor(64, 0)?.toString() ?: recent.toString()
        } catch (_: Exception) {
            recent.toString()
        }
    }

    private fun learnSymbol(before: String, symbol: String) {
        if (closed || !symbolFrequency.learn(before, symbol)) return
        val lines = symbolFrequency.serialize()
        englishWorker.execute {
            try {
                val file = symbolFile
                file.parentFile?.mkdirs()
                file.writeText(lines.joinToString("\n", postfix = "\n"))
            } catch (e: Exception) {
                Log.e(TAG, "failed to save symbol frequency", e)
            }
        }
    }

    private fun loadSymbolFrequency() {
        try {
            val file = symbolFile
            if (!file.isFile) return
            symbolFrequency.load(file.readLines())
        } catch (_: Exception) {
            // The row simply has no learned symbols until the next one is typed.
        }
    }

    /** Drops symbol suggestions once the user does something else. */
    private fun dismissSuggestions() {
        if (hasComposition || presented.isEmpty()) return
        presented = emptyList()
        publish(EngineSnapshot())
    }

    private fun remember(text: String) {
        recent.append(text)
        if (recent.length > RECENT_CHARS) recent.delete(0, recent.length - RECENT_CHARS)
    }

    private fun performEnter() {
        hideClipboard()
        val choice = EnterActions.choose(editorInfo())
        val ic = connection() ?: return
        if (!choice.newline && ic.performEditorAction(choice.actionId)) {
            recent.setLength(0)
            return
        }
        if (choice.newline) {
            commit("\n")
            refreshSymbolSuggestions()
        }
    }

    private fun apply(snapshot: EngineSnapshot) {
        val inline = snapshot.composingPreview.ifEmpty { snapshot.preedit }
        publish(snapshot)
        if (closed) return
        val ic = connection() ?: return
        traced("ime.setComposing") {
            if (inline.isNotEmpty()) {
                ic.setComposingText(inline, 1)
                val caret = visualCaret(inline)
                if (caret >= inline.length) ownedCaretSpan = null else placeComposingCaret(ic, inline, caret)
            } else {
                ownedCaretSpan = null
                ic.setComposingText("", 1)
                ic.finishComposingText()
            }
        }
    }

    /** Caret inside the underlined text. The end means setComposingText already placed it. */
    private fun visualCaret(inline: String): Int {
        val caret = when {
            english.isNotEmpty() -> englishCaret
            hasComposition -> composition.chosenText.length + composition.pending.caret
            else -> inline.length
        }
        return caret.coerceIn(0, inline.length)
    }

    /**
     * setComposingText leaves the caret at the end of [text]. A caret in the middle
     * is then moved with setSelection. A missing extract skips that; the next insert
     * still rewrites the whole composing string.
     */
    private fun placeComposingCaret(ic: InputConnection, text: String, caret: Int) {
        val extracted = try {
            val request = ExtractedTextRequest().apply { hintMaxChars = MAX_EXTRACT }
            ic.getExtractedText(request, 0)
        } catch (_: Exception) {
            null
        } ?: return
        val absolute = composingSelection(extracted.selectionEnd, text.length, caret) ?: return
        val start = absolute - caret
        ownedCaretSpan = start..(start + text.length)
        try {
            ic.setSelection(absolute, absolute)
        } catch (_: Exception) {
            // The buffer caret is already updated. The next key writes it again.
        }
    }

    private fun moveComposingCaret(dxPx: Float, dyPx: Float): Boolean {
        val next = CursorMotion.nearest(glideText, glideOrigin, dxPx, dyPx, ::textWidth, lineHeight())
        val current = if (english.isNotEmpty()) {
            englishCaret
        } else {
            composition.pending.caret
        }
        if (next == current) return false
        if (english.isNotEmpty()) {
            englishCaret = next
        } else {
            composition.pending.moveCaret(next)
        }
        val base = glideBase
        if (base >= 0) {
            val ic = connection()
            if (ic != null) {
                try {
                    ic.setSelection(base + glideVisualOffset + next, base + glideVisualOffset + next)
                } catch (_: Exception) {
                    // The next key rewrites the composing text and places the caret.
                }
            }
        }
        return true
    }

    /** Absolute start of the composing text, assuming the editor caret is at [caret] inside it. */
    private fun compositionAnchor(caret: Int): Int {
        val session = readCursor() ?: return -1
        val start = session.cursor - caret
        return if (start < 0) -1 else start
    }

    private fun rememberOwnedSpan(start: Int, length: Int) {
        ownedCaretSpan = if (start < 0) null else start..(start + length)
    }

    private fun textWidth(text: String): Float = caretPaint.measureText(text)

    private fun lineHeight(): Float = caretPaint.fontSpacing.coerceAtLeast(1f)

    private fun clearEnglishWord() {
        english.setLength(0)
        englishCaret = 0
        ownedCaretSpan = null
    }

    private fun commit(text: String) {
        hideClipboard()
        connection()?.commitText(text, 1)
        remember(text)
    }

    /**
     * Drops the clipboard chip once the user types. Deleting back to an empty field
     * does not bring it back; the next time the keyboard opens reads the clip again.
     * A tap on the chip does not come through here, so the chip stays for another paste.
     */
    private fun hideClipboard() {
        if (!clipVisible) return
        clipVisible = false
        publish()
    }

    /** Drops the chip after the system clipboard was cleared. The next open reads it again. */
    fun dismissClipboard() {
        clipOffer = null
        if (!clipVisible) return
        clipVisible = false
        publish()
    }

    /** Commits the cached offer, not the short chip label, and leaves the chip up. */
    private fun pasteClipboard() {
        val offer = clipOffer ?: return
        val ic = connection() ?: return
        pasteClip(ic, offer, editorInfo())
    }

    private fun refreshClipboard() {
        clipOffer = try {
            clipboard()
        } catch (_: Exception) {
            null
        }
        clipVisible = clipOffer != null
    }

    /** Restores the saved letter keyboard without writing the preference again. */
    private fun applySavedLetterMode() {
        val saved = try {
            letterMode()
        } catch (_: Exception) {
            return
        }
        if (!saved.isLetters) return
        mode = saved
        base = saved
    }

    private fun onCharacter(value: Char) {
        hideClipboard()
        val composeEnglish = EnglishInputPolicy.shouldCompose(editorInfo())
        if ((mode == KeyboardMode.LATIN || (mode.isSymbols && base == KeyboardMode.LATIN)) &&
            composeEnglish &&
            continuesEnglish(value)
        ) {
            val at = englishCaret.coerceIn(0, english.length)
            english.insert(at, value)
            englishCaret = at + 1
            showEnglish()
            return
        }
        if (english.isNotEmpty()) finishEnglish(trailing = value.toString(), takePrediction = false) else commit(value.toString())
        if (mode == KeyboardMode.LATIN && value == '@' && isEmailContext()) {
            showEmailDomainsAfterAt()
        }
    }

    /** A letter always extends the word. An apostrophe, hyphen, or digit does only after one has started. */
    private fun continuesEnglish(value: Char): Boolean {
        if (value.isLetter()) return true
        if (english.isEmpty()) return false
        return value == '\'' || value == '-' || value.isDigit()
    }

    private fun loadEnglish() {
        if (englishLoaded) return
        englishLoaded = true
        try {
            context.assets.open(ENGLISH_ASSET).bufferedReader().use { reader ->
                englishLexicon.load(reader.readLines())
            }
            if (userEnglishFile.isFile) {
                userEnglishFile.bufferedReader().use { reader ->
                    englishLexicon.loadUser(reader.readLines())
                }
            }
            if (emailHistoryFile.isFile) {
                emailHistoryFile.bufferedReader().use { reader ->
                    emailSuggest.loadHistory(reader.readLines())
                }
            }
        } catch (_: Exception) {
            // The letters still go into the field. The bar just has no completions.
        }
    }

    private fun showEnglish() {
        loadEnglish()
        val typed = english.toString()
        val before = connection()?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        val start = System.nanoTime()
        val candidates: List<Candidate>
        if (isEmailContext()) {
            val suggestions = emailSuggest.candidates(typed, before, ENGLISH_CANDIDATES)
            candidates = suggestions.mapIndexed { index, text ->
                Candidate(
                    text = text,
                    engineIndex = -(index + 1),
                    source = CandidateSource.SUGGESTION,
                    span = 0,
                )
            }
        } else {
            val hits = englishLexicon.complete(typed.lowercase(), ENGLISH_CANDIDATES)
            val texts = EnglishSuggest.candidates(typed, hits, ENGLISH_CANDIDATES)
            candidates = texts.mapIndexed { index, text ->
                Candidate(
                    text = text,
                    engineIndex = -(index + 1),
                    source = CandidateSource.SUGGESTION,
                    span = 0,
                )
            }
        }
        lastLookupMillis = (System.nanoTime() - start) / 1_000_000L
        apply(
            EngineSnapshot(
                preedit = typed,
                composingPreview = typed,
                candidates = candidates,
                decodeMillis = lastLookupMillis,
            ),
        )
    }

    private fun showEmailDomainsAfterAt() {
        val before = connection()?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        val domains = emailSuggest.candidates("", before, ENGLISH_CANDIDATES)
        val candidates = domains.mapIndexed { index, text ->
            Candidate(
                text = text,
                engineIndex = -(index + 1),
                source = CandidateSource.SUGGESTION,
                span = 0,
            )
        }
        if (candidates.isNotEmpty()) {
            presented = candidates
            publish(EngineSnapshot(candidates = candidates))
        }
    }

    /**
     * Ends the underlined word. Space and Enter pass [takePrediction]; punctuation,
     * switching keyboards, and leaving the field keep the letters that were typed.
     */
    private fun finishEnglish(trailing: String, takePrediction: Boolean) {
        if (english.isEmpty()) return
        val currentTyped = english.toString()
        clearEnglishWord()

        val before = connection()?.getTextBeforeCursor(currentTyped.length, 0)?.toString().orEmpty()
        if (before != currentTyped && !before.endsWith(currentTyped)) {
            clearComposing()
            presented = emptyList()
            publish(EngineSnapshot())
            return
        }

        val isEmail = isEmailContext()
        val word = if (isEmail) {
            if (takePrediction && presented.isNotEmpty()) presented.first().text else currentTyped
        } else {
            EnglishSuggest.commit(currentTyped, presented.firstOrNull()?.text, takePrediction)
        }
        val trailingChar = if (isEmail && trailing == " ") "" else trailing
        acceptCommitted(word + trailingChar)
        if (isEmail) {
            emailSuggest.recordChoice(word, beforeUsername())
            saveEmailHistory()
        } else {
            learnEnglish(word)
        }
        if (isSymbol(trailingChar)) learnSymbol(textBeforeCursor(), trailingChar)
        refreshSymbolSuggestions()
    }

    /** Writes the underlined letters as they stand. Used when the field is closing. */
    private fun commitEnglishLiteral() {
        finishEnglish(trailing = "", takePrediction = false)
    }

    private fun acceptCommitted(text: String) {
        presented = emptyList()
        if (text.isNotEmpty()) commit(text)
        clearComposing()
        publish(EngineSnapshot())
    }

    private fun clearComposing() {
        val ic = connection()
        ic?.finishComposingText()
    }

    /**
     * Drops an in-progress word. An empty field is left untouched: [InputConnection.setComposingText]
     * with an empty string replaces the current selection, which deletes a browser address bar
     * that selected its text when focused.
     */
    private fun cancelComposition() {
        val discard = hasComposition || english.isNotEmpty()
        composition.clear()
        clearEnglishWord()
        presented = emptyList()
        if (!discard) return
        val ic = connection() ?: return
        ic.setComposingText("", 1)
        ic.finishComposingText()
    }

    private fun publish(snapshot: EngineSnapshot? = null) {
        if (closed) return
        val ticket = ++generation
        val shown = when {
            snapshot != null && mode == KeyboardMode.ZHUYIN -> pipeline.preview(snapshot)
            snapshot != null && (mode == KeyboardMode.LATIN || (mode.isSymbols && base == KeyboardMode.LATIN)) -> snapshot.candidates
            mode == KeyboardMode.ZHUYIN && snapshot == null -> presented
            else -> emptyList()
        }
        if (snapshot != null) {
            presented = shown
            // logd is a binder call. A line on every key holds the frame that shows that key.
            if (snapshot.decodeMillis >= SLOW_LOOKUP_MS) {
                Log.i(TAG, "latency lookup=${snapshot.decodeMillis}ms candidates=${snapshot.candidates.size}")
            }
        }
        onUi(ui(presented))
        if (snapshot != null && mode == KeyboardMode.ZHUYIN && pipeline.willScore(snapshot.candidates.size)) {
            val contextBefore = contextBefore()
            pipeline.presentAsync(snapshot, contextBefore) { trace ->
                post {
                    applyRerank(ticket, snapshot, trace)
                }
            }
        }
    }

    private fun applyRerank(ticket: Long, snapshot: EngineSnapshot, trace: RerankTrace) {
        val superseded = closed || ticket != generation
        if (superseded || worthLogging(trace)) {
            val fallback = if (superseded) "stale" else trace.fallback?.name ?: "none"
            Log.i(
                TAG,
                "latency lookup=${snapshot.decodeMillis}ms infer=${trace.inferMillis}ms " +
                    "elapsed=${trace.elapsedMillis}ms fallback=$fallback",
            )
        }
        if (superseded || !acceptRerankUpdate(ticket, generation, trace.fallback)) return
        if (trace.candidates == presented) return
        presented = trace.candidates
        onUi(ui(presented))
    }

    private fun worthLogging(trace: RerankTrace): Boolean {
        return trace.fallback != RerankFallback.DISABLED && trace.fallback != RerankFallback.UNAVAILABLE
    }

    private fun ui(candidates: List<Candidate>): ImeUiState {
        val lookedUp = shownCandidates(mode, candidates)
        val hasUnconfirmed = (mode == KeyboardMode.ZHUYIN && hasComposition) || english.isNotEmpty()
        val visible = if (lookedUp.isEmpty() && !hasUnconfirmed) clipboardCandidates() else lookedUp
        return ImeUiState(
            mode = mode,
            base = base,
            candidates = CandidateUiState(
                candidates = visible,
                message = if (visible.isEmpty()) statusMessage() else "",
            ),
            hasUnconfirmedText = hasUnconfirmed,
        )
    }

    /** One chip, and only on a letter keyboard before the user has typed. */
    private fun clipboardCandidates(): List<Candidate> {
        val offer = clipOffer ?: return emptyList()
        if (!clipVisible || !mode.isLetters) return emptyList()
        return listOf(
            Candidate(
                text = offer.label,
                engineIndex = -1,
                source = CandidateSource.CLIPBOARD,
                span = 0,
                clipboardImageUri = offer.imageUri,
            ),
        )
    }

    private fun readCursor(): CursorSession? {
        val ic = connection() ?: return null
        return try {
            val request = ExtractedTextRequest().apply { hintMaxChars = MAX_EXTRACT }
            val extracted = ic.getExtractedText(request, 0)
            val extractedText = extracted?.text?.toString()
            val selection = extracted?.selectionEnd ?: -1
            if (extracted != null && extractedText != null && selection >= 0) {
                return CursorSession(extractedText, extracted.startOffset, selection)
            }
            val before = ic.getTextBeforeCursor(MAX_EXTRACT, 0)?.toString() ?: return null
            val after = ic.getTextAfterCursor(MAX_EXTRACT, 0)?.toString() ?: return null
            // A full before-cursor read is an absolute offset. A capped one is not.
            if (before.length >= MAX_EXTRACT) return null
            CursorSession(before + after, startOffset = 0, cursor = before.length)
        } catch (_: Exception) {
            null
        }
    }

    private fun contextBefore(): String {
        return traced("ime.contextBefore") {
            try {
                connection()
                    ?.getTextBeforeCursor(RerankLimits.CONTEXT_CHARS, 0)
                    ?.toString()
                    .orEmpty()
            } catch (_: Exception) {
                ""
            }
        }
    }

    private fun isEmailContext(): Boolean {
        val before = connection()?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        return emailSuggest.hasEmailContext(before, editorInfo())
    }

    private fun beforeUsername(): String {
        val before = connection()?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        val at = before.lastIndexOf('@')
        if (at <= 0) return ""
        val sub = before.substring(0, at)
        return sub.substringAfterLast(' ', sub).substringAfterLast('\n', sub)
    }

    private fun learnEnglish(word: String) {
        if (!EnglishInputPolicy.shouldCompose(editorInfo())) return
        if (!englishLexicon.learn(word)) return
        englishWorker.execute {
            try {
                userEnglishFile.parentFile?.mkdirs()
                userEnglishFile.appendText("${word.lowercase()}\t1\n")
            } catch (e: Exception) {
                Log.e(TAG, "failed to append english word", e)
            }
        }
    }

    private fun saveEmailHistory() {
        englishWorker.execute {
            try {
                emailHistoryFile.parentFile?.mkdirs()
                val lines = emailSuggest.serializeHistory()
                emailHistoryFile.writeText(lines.joinToString("") { "$it\n" })
            } catch (e: Exception) {
                Log.e(TAG, "failed to save email history", e)
            }
        }
    }

    private companion object {
        const val TAG = "FsKeyboard"
        const val RECENT_CHARS = 32
        const val SLOW_LOOKUP_MS = 5L
        const val MAX_EXTRACT = 20_000
        const val ENGLISH_CANDIDATES = 8
        const val ENGLISH_ASSET = "lexicon/english.tsv"
        const val USER_ENGLISH_FILE = "lexicon/user_english.tsv"
        const val SYMBOL_FILE = "lexicon/symbol_freq.tsv"
        const val EMAIL_DOMAINS_FILE = "lexicon/email_domains.tsv"
    }

    private class CursorSession(val text: String, val startOffset: Int, var cursor: Int)

    private fun statusMessage(): String = when (lexicon.status) {
        EngineStatus.READY -> ""
        EngineStatus.LOADING -> context.getString(R.string.status_loading)
        EngineStatus.MISSING_LIBRARY -> context.getString(R.string.status_missing_rime)
        EngineStatus.FAILED -> context.getString(
            R.string.status_failed,
            lexicon.statusDetail.ifBlank { "unknown" },
        )
    }
}
