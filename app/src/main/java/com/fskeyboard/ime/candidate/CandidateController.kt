package com.fskeyboard.ime.candidate

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
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
) {
    /** Ctrl, Alt, and Meta chosen from an English swipe, until the next key. */
    var onComputerModifiers: (Set<ComputerKey>) -> Unit = {}

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
    private val englishLexicon = EnglishLexicon()
    private val emailSuggest = EmailSuggest()
    private val englishWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fskeyboard-english").apply { isDaemon = true }
    }
    private val userEnglishFile by lazy { File(context.filesDir, USER_ENGLISH_FILE) }
    private val emailHistoryFile by lazy { File(context.filesDir, EMAIL_DOMAINS_FILE) }
    private var englishLoaded = false
    private var generation = 0L
    private var closed = false
    private var lastLookupMillis = 0L
    private var cursorSession: CursorSession? = null
    private var selStart = -1
    private var selEnd = -1

    private val hasComposition: Boolean
        get() = !composition.isEmpty

    fun start() {
        loadEnglish()
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
        english.setLength(0)
        cancelComposition()
        clearModifiers()
        if (emailSuggest.isEmailField(editorInfo())) {
            setMode(KeyboardMode.LATIN)
        }
        publish()
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
            var changed = false
            if (english.isNotEmpty()) {
                english.setLength(0)
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
        }
    }

    fun onFinishInput() {
        endCursor()
        commitEnglishLiteral()
        cancelComposition()
        selStart = -1
        selEnd = -1
    }

    /** Space can glide the caret only when it is not marking 平聲 or finishing an English word. */
    fun canMoveCursor(): Boolean = !closed && !hasComposition && english.isEmpty()

    /**
     * Takes the editor text once. Later steps reuse it, so a drag does not re-read the field.
     * False when the field does not report a caret; the space bar then stays a tap.
     */
    fun beginCursor(): Boolean {
        if (!canMoveCursor()) return false
        val session = readCursor() ?: return false
        dismissSuggestions()
        cursorSession = session
        return true
    }

    /** [dx] is graphemes to the right, [dy] is lines down. False when the caret did not move. */
    fun moveCursor(dx: Int, dy: Int): Boolean {
        val session = cursorSession ?: return false
        val local = session.cursor - session.startOffset
        if (local !in 0..session.text.length) return false
        val moved = CursorMotion.move(session.text, local, dx, dy)
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
     * A symbol is typed and any waiting modifier is cleared.
     * A modifier waits. Any other computer key is sent, with the waiting modifiers, then those clear.
     */
    private fun onComputer(key: ComputerKey) {
        val symbol = key.symbol
        if (symbol != null) {
            clearModifiers()
            onPunctuation(symbol.toString())
            return
        }
        if (key.modifier) {
            modifiers.toggle(key)
            onComputerModifiers(modifiers.active())
            return
        }
        dismissSuggestions()
        if (english.isNotEmpty()) finishEnglish(trailing = "", takePrediction = false)
        if (mode == KeyboardMode.ZHUYIN && hasComposition) finishComposition(convertAll())
        sendKeyEvent(key.keyCode, modifiers.metaState())
        clearModifiers()
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
            clearModifiers()
            return false
        }
        dismissSuggestions()
        if (english.isNotEmpty()) finishEnglish(trailing = "", takePrediction = false)
        if (mode == KeyboardMode.ZHUYIN && hasComposition) finishComposition(convertAll())
        sendKeyEvent(keyCode, modifiers.metaState(shift))
        clearModifiers()
        return true
    }

    private fun clearModifiers() {
        if (!modifiers.isActive()) return
        modifiers.clear()
        onComputerModifiers(emptySet())
    }

    private fun sendKeyEvent(keyCode: Int, meta: Int) {
        val ic = connection() ?: return
        ic.finishComposingText()
        val time = System.currentTimeMillis()
        ic.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    /**
     * Chooses [candidate] for the front syllables it spans. The rest stays to be chosen.
     * A suggestion, offered with nothing typed, is committed as it is.
     */
    fun onCandidate(candidate: Candidate) {
        if (mode == KeyboardMode.LATIN && english.isNotEmpty()) {
            val isEmail = isEmailContext()
            val text = candidate.text
            val trailing = if (isEmail) "" else " "
            english.setLength(0)
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
            dismissSuggestions()
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
        if (next.isLetters) base = next
        mode = next
        presented = emptyList()
        publish()
    }

    private fun onBopomofo(action: KeyAction.Bopomofo) {
        if (mode != KeyboardMode.ZHUYIN) {
            commit(action.label)
            return
        }
        if (!composition.append(action.label)) return
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
        val found = traced("ime.lookup") { lexicon.lookup(queries, before) }
        lastLookupMillis = (System.nanoTime() - start) / 1_000_000L
        return found
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
        dismissSuggestions()
    }

    private fun onSpace() {
        if (english.isNotEmpty()) {
            finishEnglish(trailing = " ", takePrediction = false)
            return
        }
        when (KeyPolicy.space(mode, composing = hasComposition)) {
            KeyPolicy.Space.MarkLevelTone -> when {
                composition.pending.isEmpty -> finishComposition(convertAll())
                composition.markLevelTone() -> apply(refresh())
            }
            KeyPolicy.Space.CommitSpace -> commit(" ")
        }
    }

    private fun onBackspace() {
        if (english.isNotEmpty()) {
            english.deleteCharAt(english.lastIndex)
            if (english.isEmpty()) {
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

    /** Commits, learns the phrases the user put together, and offers punctuation after a particle. */
    private fun finishComposition(done: Commit) {
        composition.clear()
        presented = emptyList()
        val ic = connection()
        ic?.commitText(done.text, 1)
        ic?.finishComposingText()
        remember(done.text)
        lexicon.learn(done.learned)
        val punctuation = Particles.punctuationAfter(recent.toString()).mapIndexed { index, text ->
            Candidate(text = text, engineIndex = -(index + 1), source = CandidateSource.SUGGESTION, span = 0)
        }
        publish(EngineSnapshot(candidates = punctuation))
    }

    /** Drops punctuation offered after a commit once the user does something else. */
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
        val choice = EnterActions.choose(editorInfo())
        val ic = connection() ?: return
        if (!choice.newline && ic.performEditorAction(choice.actionId)) {
            recent.setLength(0)
            return
        }
        if (choice.newline) commit("\n")
    }

    private fun apply(snapshot: EngineSnapshot) {
        val inline = snapshot.composingPreview.ifEmpty { snapshot.preedit }
        publish(snapshot)
        if (closed) return
        val ic = connection() ?: return
        traced("ime.setComposing") {
            if (inline.isNotEmpty()) {
                ic.setComposingText(inline, 1)
            } else {
                ic.setComposingText("", 1)
                ic.finishComposingText()
            }
        }
    }

    private fun commit(text: String) {
        connection()?.commitText(text, 1)
        remember(text)
    }

    private fun onCharacter(value: Char) {
        val composeEnglish = EnglishInputPolicy.shouldCompose(editorInfo())
        if ((mode == KeyboardMode.LATIN || (mode.isSymbols && base == KeyboardMode.LATIN)) &&
            composeEnglish &&
            continuesEnglish(value)
        ) {
            english.append(value)
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
        english.setLength(0)

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

    private fun cancelComposition() {
        composition.clear()
        val ic = connection()
        ic?.setComposingText("", 1)
        ic?.finishComposingText()
        presented = emptyList()
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
        val visible = shownCandidates(mode, candidates)
        val hasUnconfirmed = (mode == KeyboardMode.ZHUYIN && hasComposition) || english.isNotEmpty()
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
