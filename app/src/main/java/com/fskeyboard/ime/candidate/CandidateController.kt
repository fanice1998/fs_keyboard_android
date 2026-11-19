package com.fskeyboard.ime.candidate

import android.content.Context
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.fskeyboard.ime.compose.EnglishCompositionSession
import com.fskeyboard.ime.compose.EnglishHost
import com.fskeyboard.ime.compose.ZhuyinCompositionSession
import com.fskeyboard.ime.compose.ZhuyinHost
import com.fskeyboard.ime.engine.EngineSnapshot
import com.fskeyboard.ime.engine.UnavailableZhuyinEngine
import com.fskeyboard.ime.engine.ZhuyinEngine
import com.fskeyboard.ime.keyboard.ComputerKey
import com.fskeyboard.ime.keyboard.ComputerModifiers
import com.fskeyboard.ime.keyboard.CursorMotion
import com.fskeyboard.ime.keyboard.EnterActions
import com.fskeyboard.ime.keyboard.composingSelection
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.keyboard.KeySignal
import com.fskeyboard.ime.keyboard.KeyboardMode
import com.fskeyboard.ime.keyboard.NumberPad
import com.fskeyboard.ime.keyboard.ShiftMode
import com.fskeyboard.ime.keyboard.SignalKey
import com.fskeyboard.ime.keyboard.computerKeyCode
import com.fskeyboard.ime.keyboard.fnFunctionKeyCode
import com.fskeyboard.ime.keyboard.keySignal
import com.fskeyboard.ime.keyboard.signalKey
import com.fskeyboard.ime.latency.KeyStages
import com.fskeyboard.ime.lexicon.AssetLexicon
import com.fskeyboard.ime.lexicon.FsLexicon
import com.fskeyboard.ime.rerank.CandidatePipeline
import com.fskeyboard.ime.rerank.RerankLimits
import com.fskeyboard.ime.session.EditorSession
import com.fskeyboard.ime.settings.KeyboardPreferences
import com.fskeyboard.ime.state.CompositionLeave
import com.fskeyboard.ime.state.KeyFate
import com.fskeyboard.ime.state.KeyState
import com.fskeyboard.ime.state.LatinStateController
import com.fskeyboard.ime.state.NumberStateController
import com.fskeyboard.ime.state.SymbolStateController
import com.fskeyboard.ime.state.ZhuyinStateController
import com.fskeyboard.ime.state.modeLeave
import com.fskeyboard.ime.traced
import java.io.File
import java.util.concurrent.Executors

internal class CandidateController(
    private val context: Context,
    pipeline: CandidatePipeline,
    connection: () -> InputConnection?,
    private val editorInfo: () -> EditorInfo?,
    private val onUi: (ImeUiState) -> Unit,
    private val post: (Runnable) -> Unit = { runnable ->
        Handler(Looper.getMainLooper()).post(runnable)
    },
    private val letterMode: () -> KeyboardMode = { KeyboardMode.ZHUYIN },
    private val clipboard: () -> ClipOffer? = { readClipboard(context) },
    continuations: WordContinuations? = null,
    zhuyinEngine: ZhuyinEngine = UnavailableZhuyinEngine(),
    partialLexicon: PartialLexicon = PartialLexicon.EMPTY,
    private val userLexicon: FsLexicon? = null,
    lingxi: com.fskeyboard.ime.lingxi.LingxiSlot? = null,
) {
    /** Ctrl, Alt, Fn, and Meta chosen from an English swipe, until the next key. */
    var onComputerModifiers: (Set<ComputerKey>) -> Unit = {}

    /** Modifiers locked by a hold. They stay after the finger lifts. */
    var onLockedModifiers: (Set<ComputerKey>) -> Unit = {}

    /** The user switched between zhuyin and Latin. An email field does not call this. */
    var onLetterMode: (KeyboardMode) -> Unit = {}

    private val modifiers = ComputerModifiers()
    private var mode = KeyboardMode.ZHUYIN

    /** The letter keyboard the symbol pages return to. */
    private var base = KeyboardMode.ZHUYIN

    /** Set only while [mode] is the digit pad. A key cannot turn this on. */
    private var numberPad = NumberPad.NONE

    /**
     * Non-zero while a commit and the composing text that replaces it are one edit.
     * The editor reports the commit with no composing span, and that report must
     * not drop syllables that are still being chosen.
     */
    private var editDepth = 0

    /** The last characters this keyboard committed, for choosing particles. */
    private val recent = StringBuilder()

    private val englishLexicon = EnglishLexicon()
    private val preference = CandidatePreference()
    private val preferenceFile by lazy { File(context.filesDir, CANDIDATE_PREF_FILE) }
    private var spaceUndo: SpaceUndo? = null
    private val symbolFrequency = SymbolFrequency()
    private val emailSuggest = EmailSuggest()
    private val englishWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fskeyboard-english").apply { isDaemon = true }
    }
    private var closed = false
    private val editor = EditorSession(connection)
    private val lexiconStore = AssetLexicon(
        context = context,
        post = post,
        worker = englishWorker,
        continuations = continuations,
        partial = partialLexicon,
    )
    private val presenter = CandidatePresenter(
        pipeline = pipeline,
        preference = preference,
        post = post,
        closed = { closed },
        contextBefore = { contextBefore() },
        dropUnusedHabits = { dropUnusedHabits() },
        savePreference = { savePreference() },
        onPresented = { onUi(ui(it)) },
    )
    private val zhuyin = ZhuyinCompositionSession(
        engine = zhuyinEngine,
        lexicon = lexiconStore,
        lingxi = lingxi,
        host = object : ZhuyinHost {
            override fun mode(): KeyboardMode = this@CandidateController.mode
            override fun commit(text: String) = this@CandidateController.commit(text)
            override fun apply(snapshot: EngineSnapshot) = this@CandidateController.apply(snapshot)
            override fun edit(block: () -> Unit) = this@CandidateController.edit(block)
            override fun promote(candidate: Candidate) = preference.promote(candidate)
            override fun rememberPhrase(candidate: Candidate) {
                preference.remember(candidate)
                rememberUserRow(candidate)
            }
            override fun savePreference() = this@CandidateController.savePreference()
            override fun createdMatching(reading: String): List<Candidate> = preference.createdMatching(reading)
            override fun refreshIdleSuggestions() = this@CandidateController.refreshIdleSuggestions()
            override fun committedContext(): String {
                return try {
                    editor.textBefore(64)?.toString().orEmpty()
                } catch (_: Exception) {
                    ""
                }
            }
            override fun lingxiLine(message: String) {
                if (message.startsWith("lingxi key-to-row ms=")) Log.i("FsKeyboard", message)
            }
        },
    )
    private val englishHost = object : EnglishHost {
        override fun editorInfo(): EditorInfo? = this@CandidateController.editorInfo()
        override fun textBefore(n: Int): String? {
            loadEnglish()
            return editor.textBefore(n)?.toString()
        }
        override fun commit(text: String) = this@CandidateController.commit(text)
        override fun acceptCommitted(text: String) = this@CandidateController.acceptCommitted(text)
        override fun apply(snapshot: EngineSnapshot) = this@CandidateController.apply(snapshot)
        override fun finishComposing() = editor.finishComposing()
        override fun clearUnderline() = editor.clearUnderline()
        override fun clearOwnedCaretSpan() {
            ownedCaretSpan = null
        }
        override fun presented(): List<Candidate> = presenter.presented
        override fun publish(snapshot: EngineSnapshot) {
            presenter.show(snapshot.candidates)
            presenter.publish(snapshot, mode, base)
        }
        override fun showClearedWord() {
            presenter.show(emptyList())
            editor.clearUnderline()
            presenter.publish(EngineSnapshot(), mode, base)
        }
        override fun refreshIdleSuggestions() = this@CandidateController.refreshIdleSuggestions()
        override fun showEmailDomainsAfterAt() = this@CandidateController.showEmailDomainsAfterAt()
        override fun isEmailContext(): Boolean = this@CandidateController.isEmailContext()
        override fun beforeUsername(): String = this@CandidateController.beforeUsername()
        override fun learnEnglish(word: String) = this@CandidateController.learnEnglish(word)
        override fun recordEmail(choice: String, username: String) {
            emailSuggest.recordChoice(choice, username)
            saveEmailHistory()
        }
        override fun emailCandidates(typed: String, before: String, limit: Int): List<String> {
            return emailSuggest.candidates(typed, before, limit)
        }
        override fun learnSymbol(symbol: String) {
            this@CandidateController.learnSymbol(textBeforeCursor(), symbol)
        }
        override fun noteSpaceUndo(typed: String, committed: String) {
            spaceUndo = SpaceUndo.English(typed, committed)
        }
    }
    private val english = EnglishCompositionSession(
        lexicon = englishLexicon,
        host = englishHost,
    )
    private val zhuyinState = ZhuyinStateController(zhuyin)
    private val latinState = LatinStateController(english, englishHost)
    private val symbolState = SymbolStateController(english) {
        base == KeyboardMode.LATIN && EnglishInputPolicy.shouldCompose(editorInfo())
    }
    private val numberState = NumberStateController { commit(it) }
    private val userEnglishFile by lazy { File(context.filesDir, USER_ENGLISH_FILE) }
    private val symbolFile by lazy { File(context.filesDir, SYMBOL_FILE) }
    private val emailHistoryFile by lazy { File(context.filesDir, EMAIL_DOMAINS_FILE) }
    private var englishLoaded = false
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

    fun start() {
        applySavedLetterMode()
        loadEnglish()
        loadCandidatePreference()
        loadSymbolFrequency()
        loadWordContinuations()
        presenter.publish(null, mode, base)
    }

    fun close() {
        closed = true
        presenter.invalidate()
        endCursor()
        englishWorker.shutdown()
    }

    fun onStartInput(restarting: Boolean) {
        zhuyin.resetEngine()
        val info = editorInfo()
        selStart = info?.initialSelStart ?: -1
        selEnd = info?.initialSelEnd ?: -1
        if (!restarting) {
            recent.setLength(0)
        }
        cancelComposition()
        zhuyin.clearFrame()
        clearModifiers()
        refreshClipboard()
        applySavedLetterMode()
        numberPad = NumberPad.NONE
        if (emailSuggest.isEmailField(info)) {
            // This field opens in Latin. That must not replace the keyboard the user chose.
            mode = KeyboardMode.LATIN
            base = KeyboardMode.LATIN
        } else {
            info?.let { NumberPad.from(it.inputType) }?.let { pad ->
                mode = KeyboardMode.NUMBER
                numberPad = pad
            }
        }
        presenter.publish(null, mode, base)
        refreshIdleSuggestions()
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
        if (editDepth > 0) return
        if (candidatesStart == -1 && candidatesEnd == -1) {
            if (composingGlide) return
            val owned = ownedCaretSpan
            if (owned != null && newSelStart == newSelEnd && newSelStart in owned) return
            ownedCaretSpan = null
            var changed = false
            if (english.active) {
                english.clear()
                changed = true
            }
            if (zhuyin.hasComposition) {
                zhuyin.dropChain()
                changed = true
            }
            if (changed) {
                presenter.show(emptyList())
                presenter.publish(EngineSnapshot(), mode, base)
            }
            if (cursorSession == null && !zhuyin.hasComposition && !english.active && newSelStart == newSelEnd) {
                refreshIdleSuggestions()
            }
        }
    }

    fun onFinishInput() {
        endCursor()
        english.finish(trailing = "", takePrediction = false)
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
        spaceUndo = null
        if (!canMoveCursor()) return false
        english.caretTarget()?.let { target ->
            glideText = target.text
            glideOrigin = target.caret.coerceIn(0, glideText.length)
            glideVisualOffset = target.visualOffset
            glideBase = compositionAnchor(glideVisualOffset + glideOrigin)
            rememberOwnedSpan(glideBase, target.length)
            composingGlide = true
            cursorSession = null
            return true
        }
        if (zhuyin.hasComposition) {
            val pending = zhuyin.pendingText()
            glideText = pending
            glideOrigin = zhuyin.pendingCaret().coerceIn(0, pending.length)
            glideVisualOffset = zhuyin.chosenTextLength()
            glideBase = compositionAnchor(glideVisualOffset + glideOrigin)
            rememberOwnedSpan(glideBase, zhuyin.compositionLength())
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
            editor.setSelection(absolute, absolute)
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
        spaceUndo = null
        if (action is KeyAction.Computer) {
            onComputer(action)
            return
        }
        if (modifiers.isActive() && sendModified(action)) return
        // Latin only. A symbol page dismisses the row, then the word is drawn again.
        val typingEnglish = mode == KeyboardMode.LATIN &&
            EnglishInputPolicy.shouldCompose(editorInfo()) &&
            (english.active || (action is KeyAction.Character && action.value.isLetter()))
        if (action !is KeyAction.Bopomofo && !typingEnglish) dismissSuggestions()
        when (action) {
            is KeyAction.Punctuation -> deliverPunctuation(action.text)
            is KeyAction.SetMode -> setMode(action.mode)
            KeyAction.Shift, KeyAction.NextInputMethod -> Unit
            KeyAction.Backspace -> deliverBackspace()
            else -> {
                if (action is KeyAction.Space || action is KeyAction.Character ||
                    action is KeyAction.Bopomofo
                ) {
                    hideClipboard()
                }
                when (stateFor(mode).handle(action)) {
                    KeyFate.Consumed -> Unit
                    KeyFate.Continue -> deliverUnconsumed(action)
                }
            }
        }
    }

    private fun stateFor(mode: KeyboardMode): KeyState = when (mode) {
        KeyboardMode.ZHUYIN -> zhuyinState
        KeyboardMode.LATIN -> latinState
        KeyboardMode.SYMBOLS, KeyboardMode.SYMBOLS_MORE -> symbolState
        KeyboardMode.NUMBER -> numberState
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
     * A modifier waits. Any other computer key is delivered, then the tap-armed ones clear.
     */
    private fun onComputer(action: KeyAction.Computer) {
        val key = action.key
        val symbol = key.symbol
        if (symbol != null) {
            clearLatched()
            deliverPunctuation(symbol.toString())
            return
        }
        if (key.modifier && !action.repeating) {
            modifiers.toggle(key)
            publishModifiers()
            return
        }
        if (key.modifier) {
            dismissSuggestions()
            hideClipboard()
            if (english.active) english.finish(trailing = "", takePrediction = false)
            if (mode == KeyboardMode.ZHUYIN && zhuyin.hasComposition) zhuyin.finish()
            editor.sendDownUp(key.keyCode, modifiers.metaState() or key.metaState)
            return
        }
        dismissSuggestions()
        hideClipboard()
        if (english.active) english.finish(trailing = "", takePrediction = false)
        if (mode == KeyboardMode.ZHUYIN && zhuyin.hasComposition) zhuyin.finish()
        val kind = signalKey(key)
        if (kind == null) {
            editor.sendDownUp(key.keyCode, modifiers.metaState())
        } else {
            val signal = keySignal(
                kind,
                editorInfo()?.inputType ?: 0,
                action.shift,
                modifiers.metaState(),
            )
            when (signal) {
                is KeySignal.Event -> editor.sendDownUp(signal.keyCode, signal.meta)
                is KeySignal.Surrounding -> if (!clearSelection()) {
                    if (signal.after > 0) editor.deleteForward() else editor.deleteOne()
                }
            }
        }
        clearLatched()
    }

    /**
     * The next letter, space, enter, or backspace after Ctrl, Alt, Fn, or Meta.
     * Fn plus 1–9, 0, o, or p sends F1–F12. The function key itself is the event,
     * so that press does not also carry the Fn meta. Another latched modifier stays.
     * Returns false when this key has no key code, so the normal path still runs.
     */
    private fun sendModified(action: KeyAction): Boolean {
        if (action is KeyAction.Backspace) {
            val signal = keySignal(
                SignalKey.BACKSPACE,
                editorInfo()?.inputType ?: 0,
                ShiftMode.OFF,
                modifiers.metaState(),
            )
            if (signal !is KeySignal.Event) return false
            dismissSuggestions()
            hideClipboard()
            if (english.active) english.finish(trailing = "", takePrediction = false)
            if (mode == KeyboardMode.ZHUYIN && zhuyin.hasComposition) zhuyin.finish()
            editor.sendDownUp(signal.keyCode, signal.meta)
            clearLatched()
            return true
        }
        val fnFunction = (action as? KeyAction.Character)
            ?.takeIf { modifiers.has(ComputerKey.FN) }
            ?.let { fnFunctionKeyCode(it.value) }
        val shift = fnFunction == null &&
            action is KeyAction.Character &&
            action.value.isLetter() &&
            action.value.isUpperCase()
        val keyCode = fnFunction ?: when (action) {
            is KeyAction.Character -> computerKeyCode(action.value)
            KeyAction.Space -> KeyEvent.KEYCODE_SPACE
            KeyAction.Enter -> KeyEvent.KEYCODE_ENTER
            else -> null
        }
        if (keyCode == null) {
            clearLatched()
            return false
        }
        dismissSuggestions()
        hideClipboard()
        if (english.active) english.finish(trailing = "", takePrediction = false)
        if (mode == KeyboardMode.ZHUYIN && zhuyin.hasComposition) zhuyin.finish()
        val meta = modifiers.metaState(shift).let { state ->
            if (fnFunction != null) state and ComputerKey.FN.metaState.inv() else state
        }
        editor.sendDownUp(keyCode, meta)
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
     * Chooses [candidate] for the front syllables it spans. The rest stays to be chosen.
     * A suggestion, offered with nothing typed, is committed as it is.
     */
    fun onCandidate(candidate: Candidate) {
        if (candidate.source == CandidateSource.CLIPBOARD) {
            pasteClipboard()
            return
        }
        if (zhuyin.acceptCandidate(candidate)) return
        if (mode == KeyboardMode.LATIN && english.active) {
            english.acceptCandidate(candidate)
            return
        }
        if (mode != KeyboardMode.ZHUYIN || !zhuyin.hasComposition) {
            val isEmail = isEmailContext()
            val text = candidate.text
            commit(text)
            if (isEmail) {
                emailSuggest.recordChoice(text, beforeUsername())
                saveEmailHistory()
            }
            if (isSymbol(text)) learnSymbol(textBeforeCursor(), text)
            refreshIdleSuggestions()
        }
    }

    /**
     * Leaving zhuyin commits what was typed, the way Enter does.
     * The digit pad is not in this path. A field opens it, and the next field closes it.
     * [modeLeave] is not asked about the digit pad or a switch onto the same mode.
     */
    private fun setMode(next: KeyboardMode) {
        if (next == KeyboardMode.NUMBER || mode == KeyboardMode.NUMBER) return
        if (next == mode) return
        val leave = modeLeave(mode, next)
        if (leave.english == CompositionLeave.COMMIT_TYPED) {
            english.finish(trailing = "", takePrediction = false)
        }
        clearModifiers()
        if (leave.zhuyin == CompositionLeave.COMMIT_TYPED && zhuyin.hasComposition) {
            zhuyin.finish()
        }
        if (next.isLetters && next != base) onLetterMode(next)
        if (next.isLetters) base = next
        mode = next
        presenter.show(emptyList())
        presenter.publish(null, mode, base)
    }

    /**
     * Punctuation and a computer-key symbol. An open English word is finished with [text]
     * and not committed again, even when the field text does not match the word.
     */
    private fun deliverPunctuation(text: String) {
        if (english.active) {
            english.finish(trailing = text, takePrediction = false)
            return
        }
        if (mode == KeyboardMode.ZHUYIN && zhuyin.hasComposition) {
            zhuyin.finish()
        }
        commit(text)
        if (isSymbol(text)) learnSymbol(textBeforeCursor(), text)
        refreshIdleSuggestions()
    }

    /** Keys the current state did not take. Punctuation and email domains are not handled here. */
    private fun deliverUnconsumed(action: KeyAction) {
        when (action) {
            KeyAction.Enter -> performEnter()
            KeyAction.Space -> {
                commit(" ")
                refreshIdleSuggestions()
                spaceUndo = SpaceUndo.Space
            }
            is KeyAction.Character -> commit(action.value.toString())
            is KeyAction.Bopomofo -> commit(action.label)
            else -> Unit
        }
    }

    /** False when the last backspace changed nothing, so a hold can stop. */
    fun backspaceProgress(): Boolean = backspaceMoved

    private var backspaceMoved = true

    private fun deliverBackspace() {
        backspaceMoved = true
        english.consumedBackspace = false
        when (stateFor(mode).handle(KeyAction.Backspace)) {
            KeyFate.Consumed -> backspaceMoved =
                if (english.consumedBackspace) english.backspaceMoved() else true
            KeyFate.Continue -> backspaceMoved = deleteFieldBeforeCaret()
        }
    }

    /** False when there is no field. A key event counts as progress. A selection clear does too. */
    private fun deleteFieldBeforeCaret(): Boolean {
        if (!editor.hasField()) return false
        val signal = keySignal(
            SignalKey.BACKSPACE,
            editorInfo()?.inputType ?: 0,
            ShiftMode.OFF,
            modifiers.metaState(),
        )
        return when (signal) {
            is KeySignal.Event -> {
                editor.sendDownUp(signal.keyCode, signal.meta)
                clearLatched()
                true
            }
            is KeySignal.Surrounding -> {
                if (clearSelection()) return true
                val progressed = editor.deleteOne()
                if (progressed && recent.isNotEmpty()) recent.setLength(recent.length - 1)
                progressed
            }
        }
    }

    /** True when a selection was removed. A neighboring character is left in place. */
    private fun clearSelection(): Boolean {
        val selected = try {
            editor.selectedText()
        } catch (_: Exception) {
            null
        }
        val hasSelection = !selected.isNullOrEmpty() || (selStart >= 0 && selEnd >= 0 && selStart != selEnd)
        if (!hasSelection) return false
        editor.commitText("")
        selStart = -1
        selEnd = -1
        recent.setLength(0)
        return true
    }

    /**
     * Next words after the Han text at the caret, then symbols learned after that word.
     * A space, no text, or a selection leaves the row empty. Composition and the English
     * word keep their own candidates.
     */
    private fun refreshIdleSuggestions() {
        if (closed || zhuyin.hasComposition || english.active || !mode.isLetters) return
        if (cursorSession != null || composingGlide) return
        if (selStart >= 0 && selEnd >= 0 && selStart != selEnd) return
        val before = textBeforeCursor()
        val words = lexiconStore.following(before)
        val symbols = symbolFrequency.predict(before)
        val candidates = ArrayList<Candidate>(words.size + symbols.size)
        var index = 0
        for (text in words) {
            index += 1
            candidates.add(idleSuggestion(text, index))
        }
        for (text in symbols) {
            index += 1
            candidates.add(idleSuggestion(text, index))
        }
        if (candidates == presenter.presented) return
        presenter.show(candidates)
        presenter.publish(EngineSnapshot(candidates = candidates), mode, base)
    }

    private fun idleSuggestion(text: String, index: Int): Candidate {
        return Candidate(
            text = text,
            engineIndex = -index,
            source = CandidateSource.SUGGESTION,
            span = 0,
        )
    }

    /** The field text before the caret, or the characters this keyboard just committed. */
    private fun textBeforeCursor(): String {
        return try {
            editor.textBefore(64)?.toString() ?: recent.toString()
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

    /** Reads lexicon.bin off the main thread. A missing file leaves the row without next words. */
    private fun loadWordContinuations() {
        lexiconStore.ensureContinuations(stillOpen = { !closed }) {
            refreshIdleSuggestions()
        }
    }

    /** Drops symbol suggestions once the user does something else. */
    private fun dismissSuggestions() {
        if (zhuyin.hasComposition || presenter.presented.isEmpty()) return
        presenter.show(emptyList())
        presenter.publish(EngineSnapshot(), mode, base)
    }

    private fun remember(text: String) {
        recent.append(text)
        if (recent.length > RECENT_CHARS) recent.delete(0, recent.length - RECENT_CHARS)
    }

    private fun performEnter() {
        hideClipboard()
        val choice = EnterActions.choose(editorInfo())
        if (!choice.newline) {
            val acted = editor.performAction(choice.actionId) ?: return
            if (acted) {
                recent.setLength(0)
                return
            }
        } else if (!editor.hasField()) {
            return
        }
        if (choice.newline) {
            commit("\n")
            refreshIdleSuggestions()
        }
    }

    private fun apply(snapshot: EngineSnapshot) {
        val inline = snapshot.composingPreview.ifEmpty { snapshot.preedit }
        presenter.publish(snapshot, mode, base)
        if (closed) return
        if (!editor.hasField()) return
        val composingStart = System.nanoTime()
        traced("ime.setComposing") {
            if (inline.isNotEmpty()) {
                editor.setComposing(inline)
                val caret = visualCaret(inline)
                if (caret >= inline.length) ownedCaretSpan = null else placeComposingCaret(inline, caret)
            } else {
                ownedCaretSpan = null
                editor.clearUnderline()
            }
        }
        KeyStages.noteComposing(System.nanoTime() - composingStart)
    }

    /** Caret inside the underlined text. The end means setComposingText already placed it. */
    private fun visualCaret(inline: String): Int = inline.length.coerceIn(0, inline.length)

    /**
     * setComposingText leaves the caret at the end of [text]. A caret in the middle
     * is then moved with setSelection. A missing extract skips that; the next insert
     * still rewrites the whole composing string.
     */
    private fun placeComposingCaret(text: String, caret: Int) {
        val extracted = try {
            editor.extractedText(MAX_EXTRACT)
        } catch (_: Exception) {
            null
        } ?: return
        val absolute = composingSelection(extracted.selectionEnd, text.length, caret) ?: return
        val start = absolute - caret
        ownedCaretSpan = start..(start + text.length)
        try {
            editor.setSelection(absolute, absolute)
        } catch (_: Exception) {
            // The buffer caret is already updated. The next key writes it again.
        }
    }

    private fun moveComposingCaret(dxPx: Float, dyPx: Float): Boolean {
        val next = CursorMotion.nearest(glideText, glideOrigin, dxPx, dyPx, ::textWidth, lineHeight())
        val englishCaret = english.caretTarget()
        val current = englishCaret?.caret ?: zhuyin.pendingCaret()
        if (next == current) return false
        if (englishCaret != null) {
            english.moveCaret(next)
        } else {
            zhuyin.movePendingCaret(next)
        }
        val anchor = glideBase
        if (anchor >= 0) {
            try {
                editor.setSelection(anchor + glideVisualOffset + next, anchor + glideVisualOffset + next)
            } catch (_: Exception) {
                // The next key rewrites the composing text and places the caret.
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

    private fun commit(text: String) {
        hideClipboard()
        editor.commitText(text)
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
        presenter.publish(null, mode, base)
    }

    /** Drops the chip after the system clipboard was cleared. The next open reads it again. */
    fun dismissClipboard() {
        clipOffer = null
        if (!clipVisible) return
        clipVisible = false
        presenter.publish(null, mode, base)
    }

    /** Commits the cached offer, not the short chip label, and leaves the chip up. */
    private fun pasteClipboard() {
        val offer = clipOffer ?: return
        if (!editor.hasField()) return
        pasteClip(editor, offer, editorInfo())
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

    private fun showEmailDomainsAfterAt() {
        val before = editor.textBefore(64)?.toString().orEmpty()
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
            presenter.show(candidates)
            presenter.publish(EngineSnapshot(candidates = candidates), mode, base)
        }
    }

    private fun acceptCommitted(text: String) {
        presenter.show(emptyList())
        if (text.isNotEmpty()) commit(text)
        clearComposing()
        presenter.publish(EngineSnapshot(), mode, base)
    }

    private fun clearComposing() {
        editor.finishComposing()
    }

    /**
     * Drops an in-progress word. An empty field is left untouched: [android.view.inputmethod.InputConnection.setComposingText]
     * with an empty string replaces the current selection, which deletes a browser address bar
     * that selected its text when focused.
     */
    private fun cancelComposition() {
        val discard = zhuyin.hasComposition || english.active
        zhuyin.dropChain()
        english.clear()
        presenter.show(emptyList())
        if (!discard) return
        editor.clearUnderline()
    }

    private fun ui(candidates: List<Candidate>): ImeUiState {
        val lookedUp = shownCandidates(mode, candidates)
        val hasUnconfirmed = (mode == KeyboardMode.ZHUYIN && zhuyin.hasComposition) || english.active
        val visible = if (lookedUp.isEmpty() && !hasUnconfirmed) clipboardCandidates() else lookedUp
        return ImeUiState(
            mode = mode,
            base = base,
            candidates = CandidateUiState(
                candidates = visible,
                message = if (visible.isEmpty()) statusMessage() else "",
                openSyllables = if (mode == KeyboardMode.ZHUYIN) zhuyin.openSyllables else 0,
            ),
            hasUnconfirmedText = hasUnconfirmed,
            numberPad = if (mode == KeyboardMode.NUMBER) numberPad else NumberPad.NONE,
            preview = if (mode == KeyboardMode.ZHUYIN) {
                compositionText(zhuyin.previewComposing, zhuyin.previewReading)
            } else {
                ""
            },
            previewHint = if (mode == KeyboardMode.ZHUYIN) zhuyin.previewHint else "",
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
        if (!editor.hasField()) return null
        return try {
            val extracted = editor.extractedText(MAX_EXTRACT)
            val extractedText = extracted?.text?.toString()
            val selection = extracted?.selectionEnd ?: -1
            if (extracted != null && extractedText != null && selection >= 0) {
                return CursorSession(extractedText, extracted.startOffset, selection)
            }
            val before = editor.textBefore(MAX_EXTRACT)?.toString() ?: return null
            val after = editor.textAfter(MAX_EXTRACT)?.toString() ?: return null
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
                editor.textBefore(RerankLimits.CONTEXT_CHARS)?.toString().orEmpty()
            } catch (_: Exception) {
                ""
            }
        }
    }

    private fun isEmailContext(): Boolean {
        val before = editor.textBefore(64)?.toString().orEmpty()
        return emailSuggest.hasEmailContext(before, editorInfo())
    }

    private fun beforeUsername(): String {
        val before = editor.textBefore(64)?.toString().orEmpty()
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

    private fun loadCandidatePreference() {
        try {
            if (!preferenceFile.isFile) return
            preferenceFile.bufferedReader().use { reader ->
                preference.load(reader.readLines())
            }
        } catch (_: Exception) {
            // The row still shows the lexicon order.
        }
    }

    /**
     * Delete is for a phrase the user assembled, and for an English suggestion.
     * A base zhuyin row only moves up or down.
     */
    fun canForgetCandidate(candidate: Candidate): Boolean {
        if (adjustsEnglish(candidate)) return true
        return preference.isCreated(candidate)
    }

    /** One step up or down, or forget a word the user added. The text in the editor stays. */
    fun adjustCandidate(candidate: Candidate, weight: CandidateWeight) {
        if (closed || candidate.source == CandidateSource.CLIPBOARD) return
        val englishWord = adjustsEnglish(candidate)
        when (weight) {
            CandidateWeight.PROMOTE -> {
                if (englishWord) englishLexicon.learn(candidate.text)
                preference.promote(candidate)
            }
            CandidateWeight.DEMOTE -> {
                if (englishWord) englishLexicon.demote(candidate.text)
                preference.demote(candidate)
            }
            CandidateWeight.HIDE -> {
                if (englishWord) {
                    englishLexicon.forget(candidate.text)
                    preference.hide(candidate)
                } else if (preference.forget(candidate)) {
                    // Drop the assembled phrase, including the same text composed again from pieces.
                    removeUserRow(candidate)
                    preference.hide(candidate)
                }
            }
        }
        savePreference()
        if (englishWord) rewriteUserEnglish()
        reloadCandidates()
    }

    /**
     * Undoes the space tap that armed voice input.
     * A finished zhuyin phrase is deleted. Its syllables are not rebuilt.
     */
    fun revertLastSpaceTap() {
        val undo = spaceUndo ?: return
        spaceUndo = null
        when (undo) {
            SpaceUndo.Space -> {
                editor.deleteBefore(1)
                trimRecent(1)
            }
            is SpaceUndo.English -> {
                editor.deleteBefore(undo.committed.length)
                trimRecent(undo.committed.length)
                english.restore(undo.typed)
            }
        }
    }

    /** Inserts dictated text. An open word is committed first so the underline does not swallow it. */
    fun commitVoice(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || closed) return
        if (english.active) english.finish(trailing = " ", takePrediction = false)
        if (mode == KeyboardMode.ZHUYIN && zhuyin.hasComposition) {
            zhuyin.finish()
            commit(" ")
        }
        commit(trimmed)
        refreshIdleSuggestions()
    }

    private fun adjustsEnglish(candidate: Candidate): Boolean {
        val letters = if (mode.isLetters) mode else base
        if (letters != KeyboardMode.LATIN) return false
        return english.adjusts(candidate)
    }

    private fun reloadCandidates() {
        when {
            zhuyin.hasComposition -> zhuyin.reload()
            english.active -> english.reload()
            else -> {
                presenter.show(emptyList())
                refreshIdleSuggestions()
            }
        }
    }

    private fun savePreference() {
        val lines = preference.serialize()
        englishWorker.execute {
            try {
                preferenceFile.parentFile?.mkdirs()
                val text = if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
                preferenceFile.writeText(text)
            } catch (e: Exception) {
                Log.e(TAG, "failed to save candidate preference", e)
            }
        }
    }

    private fun rewriteUserEnglish() {
        val lines = englishLexicon.userEntries()
        englishWorker.execute {
            try {
                userEnglishFile.parentFile?.mkdirs()
                val text = if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
                userEnglishFile.writeText(text)
            } catch (e: Exception) {
                Log.e(TAG, "failed to save english words", e)
            }
        }
    }

    private fun trimRecent(count: Int) {
        val drop = count.coerceAtMost(recent.length)
        if (drop > 0) recent.setLength(recent.length - drop)
    }

    private sealed interface SpaceUndo {
        data object Space : SpaceUndo
        data class English(val typed: String, val committed: String) : SpaceUndo
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
        const val MAX_EXTRACT = 20_000
        const val ENGLISH_CANDIDATES = 8
        const val ENGLISH_ASSET = "lexicon/english.tsv"
        const val USER_ENGLISH_FILE = "lexicon/user_english.tsv"
        const val CANDIDATE_PREF_FILE = "lexicon/candidate_pref.tsv"
        const val SYMBOL_FILE = "lexicon/symbol_freq.tsv"
        const val EMAIL_DOMAINS_FILE = "lexicon/email_domains.tsv"
        const val FS_LEXICON_FILE = FsLexicon.FILE_NAME

        /** An assembled phrase has no lexicon score. Rank steps stay on [CandidatePreference]. */
        const val USER_PHRASE_WEIGHT = 0.0
    }

    private class CursorSession(val text: String, val startOffset: Int, var cursor: Int)


    /** On unless the settings screen turns it off, or the preference store cannot be read. */
    private fun dropUnusedHabits(): Boolean {
        return try {
            KeyboardPreferences(context).forgetUnusedPredictions()
        } catch (_: Exception) {
            true
        }
    }


    private fun edit(block: () -> Unit) {
        editDepth += 1
        try {
            editor.batch(block)
        } finally {
            editDepth -= 1
        }
    }

    /** Replaces sentence guesses after the ChiaKey unigram index is published. */
    fun publishPartialLexicon(next: PartialLexicon) {
        post {
            if (closed) return@post
            lexiconStore.replacePartial(next)
            if (mode != KeyboardMode.ZHUYIN) return@post
            zhuyin.afterPartialLexicon()
        }
    }

    /** Writes the phrase the user assembled. A missing files directory is ignored in tests. */
    private fun rememberUserRow(candidate: Candidate) {
        val lexicon = userLexicon
        if (lexicon != null) {
            lexicon.addUser(candidate.reading, candidate.text, USER_PHRASE_WEIGHT)
            return
        }
        runCatching {
            FsLexicon(File(context.filesDir, FS_LEXICON_FILE)).addUser(
                candidate.reading,
                candidate.text,
                USER_PHRASE_WEIGHT,
            )
        }
    }

    private fun removeUserRow(candidate: Candidate) {
        val lexicon = userLexicon
        if (lexicon != null) {
            lexicon.removeUser(candidate.reading, candidate.text)
            return
        }
        runCatching {
            FsLexicon(File(context.filesDir, FS_LEXICON_FILE)).removeUser(candidate.reading, candidate.text)
        }
    }

    private fun statusMessage(): String = ""
}
