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
import com.fskeyboard.ime.engine.InputEngine
import com.fskeyboard.ime.engine.KeyPolicy
import com.fskeyboard.ime.engine.RimeKey
import com.fskeyboard.ime.engine.RimeSchema
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.keyboard.KeyboardMode
import com.fskeyboard.ime.rerank.CandidatePipeline
import com.fskeyboard.ime.rerank.RerankFallback
import com.fskeyboard.ime.rerank.RerankLimits
import com.fskeyboard.ime.rerank.RerankTrace
import com.fskeyboard.ime.rerank.acceptRerankUpdate

internal class CandidateController(
    private val context: Context,
    private val engine: InputEngine,
    private val pipeline: CandidatePipeline,
    private val connection: () -> InputConnection?,
    private val editorInfo: () -> EditorInfo?,
    private val onUi: (ImeUiState) -> Unit,
    private val post: (Runnable) -> Unit = { runnable ->
        Handler(Looper.getMainLooper()).post(runnable)
    },
) {
    private var mode = KeyboardMode.ZHUYIN
    private var traditional = true
    private var presented: List<Candidate> = emptyList()
    private var composing = false
    private var generation = 0L
    private var closed = false

    fun start() {
        publish()
        engine.start(context) {
            if (engine.isReady) {
                traditional = engine.getOption(RimeSchema.OPTION_TRADITIONAL)
            }
            publish(engine.snapshot())
        }
    }

    fun close() {
        closed = true
        generation += 1
        engine.close()
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
            KeyAction.Backspace -> onBackspace()
            KeyAction.Enter -> onEnter()
            KeyAction.Space -> onSpace()
            is KeyAction.Character -> commit(action.value.toString())
            is KeyAction.Bopomofo -> onBopomofo(action)
            is KeyAction.Punctuation -> onPunctuation(action.text)
        }
    }

    fun onCandidate(candidate: Candidate) {
        if (!engine.isReady || mode != KeyboardMode.ZHUYIN) {
            commit(candidate.text)
            return
        }
        when (val decision = SelectionPolicy.decide(candidate)) {
            is SelectionPolicy.Decision.SelectEngine -> apply(engine.select(decision.engineIndex))
            is SelectionPolicy.Decision.CommitSynthetic -> {
                engine.clear()
                composing = false
                presented = emptyList()
                connection()?.commitText(decision.text, 1)
                publish(EngineSnapshot())
            }
        }
    }

    fun onToggleScript() {
        if (!engine.isReady) return
        traditional = !traditional
        engine.setOption(RimeSchema.OPTION_TRADITIONAL, traditional)
        if (mode == KeyboardMode.ZHUYIN) {
            apply(engine.snapshot())
        } else {
            publish()
        }
    }

    private fun setMode(next: KeyboardMode) {
        if (next == mode) return
        if (mode == KeyboardMode.ZHUYIN) {
            cancelComposition()
        }
        mode = next
        if (engine.isReady) {
            engine.setOption(RimeSchema.OPTION_ASCII, mode == KeyboardMode.LATIN)
        }
        presented = emptyList()
        composing = false
        publish()
    }

    private fun onBopomofo(action: KeyAction.Bopomofo) {
        if (mode == KeyboardMode.ZHUYIN && engine.isReady) {
            val snapshot = engine.processKey(action.key.code, 0)
            if (!snapshot.consumed && snapshot.commit == null && !snapshot.isComposing) {
                commit(action.label)
                publish(snapshot)
            } else {
                apply(snapshot)
            }
        } else {
            commit(action.label)
        }
    }

    private fun onPunctuation(text: String) {
        when {
            mode == KeyboardMode.ZHUYIN && presented.isNotEmpty() -> {
                onCandidate(presented.first())
                commit(text)
            }
            mode == KeyboardMode.ZHUYIN && composing -> {
                cancelComposition()
                commit(text)
                publish()
            }
            else -> commit(text)
        }
    }

    private fun onSpace() {
        when (KeyPolicy.space(mode, presented, composing)) {
            KeyPolicy.Space.AcceptFirst -> presented.firstOrNull()?.let(::onCandidate)
            KeyPolicy.Space.SendToRime -> {
                val snapshot = engine.processKey(RimeKey.SPACE, 0)
                if (!snapshot.consumed && snapshot.commit == null && !snapshot.isComposing) {
                    commit(" ")
                    publish(snapshot)
                } else {
                    apply(snapshot)
                }
            }
            KeyPolicy.Space.CommitSpace -> commit(" ")
        }
    }

    private fun onBackspace() {
        if (KeyPolicy.backspaceToRime(mode, composing) && engine.isReady) {
            apply(engine.processKey(RimeKey.BACKSPACE, 0))
        } else {
            connection()?.deleteSurroundingText(1, 0)
        }
    }

    private fun onEnter() {
        if (mode == KeyboardMode.ZHUYIN && engine.isReady && composing) {
            apply(engine.processKey(RimeKey.RETURN, 0))
        } else {
            performEnter()
        }
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
        val ic = connection()
        val commitText = snapshot.commit
        val inline = snapshot.composingPreview.ifEmpty { snapshot.preedit }
        if (!commitText.isNullOrEmpty()) {
            ic?.commitText(commitText, 1)
            if (inline.isNotEmpty() && inline != commitText) {
                ic?.setComposingText(inline, 1)
            }
        } else if (inline.isNotEmpty()) {
            ic?.setComposingText(inline, 1)
        } else {
            ic?.setComposingText("", 1)
            ic?.finishComposingText()
        }
        publish(snapshot)
    }

    private fun commit(text: String) {
        connection()?.commitText(text, 1)
    }

    private fun cancelComposition() {
        if (engine.isReady) engine.clear()
        val ic = connection()
        ic?.setComposingText("", 1)
        ic?.finishComposingText()
        composing = false
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
            composing = snapshot.isComposing
            Log.i(TAG, "latency rime=${snapshot.decodeMillis}ms candidates=${snapshot.candidates.size}")
        }
        onUi(ui(snapshot, presented))
        if (snapshot != null && mode == KeyboardMode.ZHUYIN && snapshot.candidates.size >= 2) {
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
                "latency rime=${snapshot.decodeMillis}ms infer=${trace.inferMillis}ms " +
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
                traditional = traditional,
            ),
        )
    }

    private fun contextBefore(): String {
        return try {
            connection()
                ?.getTextBeforeCursor(RerankLimits.CONTEXT_CHARS, 0)
                ?.toString()
                .orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private companion object {
        const val TAG = "FsKeyboard"
    }

    private fun statusMessage(): String = when (engine.status) {
        EngineStatus.READY -> ""
        EngineStatus.LOADING -> context.getString(R.string.status_loading)
        EngineStatus.MISSING_LIBRARY -> context.getString(R.string.status_missing_rime)
        EngineStatus.FAILED -> context.getString(
            R.string.status_failed,
            engine.statusDetail.ifBlank { "unknown" },
        )
    }
}
