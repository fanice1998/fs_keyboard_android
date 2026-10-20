package com.fskeyboard.ime.candidate

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.fskeyboard.ime.R
import com.fskeyboard.ime.engine.EngineSnapshot
import com.fskeyboard.ime.engine.EngineStatus
import com.fskeyboard.ime.engine.KeyPolicy
import com.fskeyboard.ime.engine.ZhuyinLexicon
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.keyboard.KeyboardMode
import com.fskeyboard.ime.keyboard.ZhuyinComposer
import com.fskeyboard.ime.rerank.CandidatePipeline
import com.fskeyboard.ime.rerank.RerankFallback
import com.fskeyboard.ime.rerank.RerankLimits
import com.fskeyboard.ime.rerank.RerankTrace
import com.fskeyboard.ime.rerank.acceptRerankUpdate
import com.fskeyboard.ime.traced

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
    private var mode = KeyboardMode.ZHUYIN

    /** The letter keyboard the symbol pages return to. */
    private var base = KeyboardMode.ZHUYIN
    private var presented: List<Candidate> = emptyList()
    private val composition = ZhuyinComposition()

    /** The last characters this keyboard committed, for choosing particles. */
    private val recent = StringBuilder()
    private var generation = 0L
    private var closed = false
    private var lastLookupMillis = 0L

    private val hasComposition: Boolean
        get() = !composition.isEmpty

    fun start() {
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
        lexicon.close()
    }

    fun onStartInput(restarting: Boolean) {
        if (!restarting) {
            recent.setLength(0)
            cancelComposition()
            publish()
        }
    }

    fun onFinishInput() {
        cancelComposition()
    }

    fun onKey(action: KeyAction) {
        if (action !is KeyAction.Bopomofo) dismissSuggestions()
        when (action) {
            is KeyAction.SetMode -> setMode(action.mode)
            KeyAction.Shift -> Unit
            KeyAction.NextInputMethod -> Unit
            KeyAction.Backspace -> onBackspace()
            KeyAction.Enter -> onEnter()
            KeyAction.Space -> onSpace()
            is KeyAction.Character -> commit(action.value.toString())
            is KeyAction.Bopomofo -> onBopomofo(action)
            is KeyAction.Punctuation -> onPunctuation(action.text)
        }
    }

    /**
     * Chooses [candidate] for the front syllables it spans. The rest stays to be chosen.
     * A suggestion, offered with nothing typed, is committed as it is.
     */
    fun onCandidate(candidate: Candidate) {
        if (mode != KeyboardMode.ZHUYIN || !hasComposition) {
            commit(candidate.text)
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
        if (mode == KeyboardMode.ZHUYIN && hasComposition) {
            finishComposition(convertAll())
        }
        commit(text)
        dismissSuggestions()
    }

    private fun onSpace() {
        when (KeyPolicy.space(mode, composing = hasComposition)) {
            KeyPolicy.Space.MarkLevelTone -> when {
                composition.pending.isEmpty -> finishComposition(convertAll())
                composition.markLevelTone() -> apply(refresh())
            }
            KeyPolicy.Space.CommitSpace -> commit(" ")
        }
    }

    private fun onBackspace() {
        if (mode != KeyboardMode.ZHUYIN || !hasComposition) {
            connection()?.deleteSurroundingText(1, 0)
            if (recent.isNotEmpty()) recent.setLength(recent.length - 1)
            return
        }
        composition.backspace()
        apply(refresh())
    }

    private fun onEnter() {
        if (mode == KeyboardMode.ZHUYIN && hasComposition) {
            finishComposition(convertAll())
        } else {
            performEnter()
        }
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
        val info = editorInfo()
        val actionId = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noAction = actionId == EditorInfo.IME_ACTION_NONE ||
            actionId == EditorInfo.IME_ACTION_UNSPECIFIED
        val ic = connection() ?: return
        if (!noAction && ic.performEditorAction(actionId)) {
            recent.setLength(0)
            return
        }
        commit("\n")
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
        val visible = if (mode == KeyboardMode.ZHUYIN) candidates else emptyList()
        return ImeUiState(
            mode = mode,
            base = base,
            candidates = CandidateUiState(
                candidates = visible,
                message = if (visible.isEmpty()) statusMessage() else "",
            ),
        )
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

    private companion object {
        const val TAG = "FsKeyboard"
        const val RECENT_CHARS = 32
        const val SLOW_LOOKUP_MS = 5L
    }

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
