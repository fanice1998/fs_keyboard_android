package com.fskeyboard.ime.candidate

import android.util.Log
import com.fskeyboard.ime.engine.EngineSnapshot
import com.fskeyboard.ime.keyboard.KeyboardMode
import com.fskeyboard.ime.latency.KeyStages
import com.fskeyboard.ime.rerank.CandidatePipeline
import com.fskeyboard.ime.rerank.RerankFallback
import com.fskeyboard.ime.rerank.RerankTrace
import com.fskeyboard.ime.rerank.acceptRerankUpdate

/**
 * Applies preference and the rerank pipeline, then hands the row back.
 * Owns the rerank ticket so a later key can drop a stale result.
 */
internal class CandidatePresenter(
    private val pipeline: CandidatePipeline,
    private val preference: CandidatePreference,
    private val post: (Runnable) -> Unit,
    private val closed: () -> Boolean,
    private val contextBefore: () -> String,
    private val dropUnusedHabits: () -> Boolean,
    private val savePreference: () -> Unit,
    private val onPresented: (List<Candidate>) -> Unit,
) {
    private var generation = 0L

    var presented: List<Candidate> = emptyList()
        private set

    fun show(candidates: List<Candidate>) {
        presented = candidates
    }

    /** Drops an in-flight rerank. Matches the bump [close] used to do on the controller. */
    fun invalidate() {
        generation += 1
    }

    fun publish(snapshot: EngineSnapshot?, mode: KeyboardMode, base: KeyboardMode) {
        if (closed()) return
        val ticket = ++generation
        if (snapshot != null) {
            val shown = when {
                mode == KeyboardMode.ZHUYIN -> pipeline.preview(snapshot)
                mode == KeyboardMode.LATIN || (mode.isSymbols && base == KeyboardMode.LATIN) ->
                    snapshot.candidates
                else -> emptyList()
            }
            if (mode == KeyboardMode.ZHUYIN && preference.remembersAny()) {
                if (preference.noteShown(shown, dropUnusedHabits())) savePreference()
            }
            val rankStart = System.nanoTime()
            presented = prefer(shown, snapshot.readingSyllables)
            // logd is a binder call. A line on every key holds the frame that shows that key.
            if (snapshot.decodeMillis >= SLOW_LOOKUP_MS) {
                Log.i(TAG, "latency lookup=${snapshot.decodeMillis}ms candidates=${snapshot.candidates.size}")
            }
            KeyStages.noteRank(System.nanoTime() - rankStart)
        }
        onPresented(presented)
        if (snapshot != null && snapshot.isComposing && mode == KeyboardMode.ZHUYIN &&
            pipeline.willScore(snapshot.candidates.size)
        ) {
            val gapStart = System.nanoTime()
            val before = contextBefore()
            pipeline.presentAsync(snapshot, before) { trace ->
                post {
                    applyRerank(ticket, snapshot, trace)
                }
            }
            KeyStages.addGap(System.nanoTime() - gapStart)
        }
    }

    /** Preference first. Length bands run after it and are not sorted again. */
    private fun prefer(items: List<Candidate>, syllables: Int): List<Candidate> {
        return LengthBand.order(preference.apply(items), syllables, preference::isCreated)
    }

    private fun applyRerank(ticket: Long, snapshot: EngineSnapshot, trace: RerankTrace) {
        val superseded = closed() || ticket != generation
        if (superseded || worthLogging(trace)) {
            val fallback = if (superseded) "stale" else trace.fallback?.name ?: "none"
            Log.i(
                TAG,
                "latency lookup=${snapshot.decodeMillis}ms infer=${trace.inferMillis}ms " +
                    "elapsed=${trace.elapsedMillis}ms fallback=$fallback",
            )
        }
        if (superseded || !acceptRerankUpdate(ticket, generation, trace.fallback)) return
        val ordered = prefer(trace.candidates, snapshot.readingSyllables)
        if (ordered == presented) return
        presented = ordered
        onPresented(presented)
    }

    private fun worthLogging(trace: RerankTrace): Boolean {
        return trace.fallback != RerankFallback.DISABLED && trace.fallback != RerankFallback.UNAVAILABLE
    }

    private companion object {
        const val TAG = "FsKeyboard"
        const val SLOW_LOOKUP_MS = 5L
    }
}
