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
    private var presented: List<Candidate> = emptyList()
    private val composition = ZhuyinComposition()
    private var generation = 0L
    private var closed = false

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
            cancelComposition()
            publish()
        }
    }

    fun onFinishInput() {
        cancelComposition()
    }

    fun onKey(action: KeyAction) {
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

    /** Chooses [candidate] for the front syllables it spans. The rest stays to be chosen. */
    fun onCandidate(candidate: Candidate) {
        if (mode != KeyboardMode.ZHUYIN) {
            commit(candidate.text)
            return
        }
        val finished = composition.choose(candidate)
        if (finished != null) {
            finishComposition(finished)
        } else {
            apply(refresh())
        }
    }

    private fun setMode(next: KeyboardMode) {
        if (next == mode) return
        if (mode == KeyboardMode.ZHUYIN) {
            cancelComposition()
        }
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

    private fun refresh(): EngineSnapshot {
        val text = composition.text
        return EngineSnapshot(
            preedit = text,
            composingPreview = text,
            candidates = candidatesFor(composition.pending, lookup(composition.pending)),
            decodeMillis = 0,
        )
    }

    private fun lookup(pending: ZhuyinComposer): List<Candidate> {
        val queries = pending.queries() ?: return emptyList()
        if (!lexicon.isReady) return emptyList()
        return traced("ime.lookup") { lexicon.lookup(queries) }
    }

    private fun onPunctuation(text: String) {
        if (mode == KeyboardMode.ZHUYIN && hasComposition) {
            finishComposition(convertAll())
        }
        commit(text)
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
    private fun convertAll(): String {
        return composition.convertAll(presented.firstOrNull()) { pending ->
            candidatesFor(pending, lookup(pending))
        }
    }

    private fun finishComposition(text: String) {
        composition.clear()
        presented = emptyList()
        val ic = connection()
        ic?.commitText(text, 1)
        ic?.finishComposingText()
        publish(EngineSnapshot())
    }

    private fun performEnter() {
        val info = editorInfo()
        val actionId = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noAction = actionId == EditorInfo.IME_ACTION_NONE ||
            actionId == EditorInfo.IME_ACTION_UNSPECIFIED
        val ic = connection() ?: return
        if (!noAction && ic.performEditorAction(actionId)) return
        ic.commitText("\n", 1)
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
            Log.i(TAG, "latency lookup=${snapshot.decodeMillis}ms candidates=${snapshot.candidates.size}")
        }
        onUi(ui(snapshot, presented))
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
        onUi(ui(snapshot, presented))
    }

    private fun worthLogging(trace: RerankTrace): Boolean {
        return trace.fallback != RerankFallback.DISABLED && trace.fallback != RerankFallback.UNAVAILABLE
    }

    private fun ui(snapshot: EngineSnapshot?, candidates: List<Candidate>): ImeUiState {
        val preedit = when {
            mode != KeyboardMode.ZHUYIN || snapshot == null -> ""
            else -> snapshot.preedit.ifEmpty { snapshot.composingPreview }
        }
        val visible = if (mode == KeyboardMode.ZHUYIN) candidates else emptyList()
        return ImeUiState(
            mode = mode,
            candidates = CandidateUiState(
                preedit = preedit,
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
