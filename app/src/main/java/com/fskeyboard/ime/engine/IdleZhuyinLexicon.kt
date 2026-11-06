package com.fskeyboard.ime.engine

import android.content.Context
import com.fskeyboard.ime.candidate.Candidate

/** Zhuyin no longer asks the Rust lexicon. The type stays for the old tests. */
internal object IdleZhuyinLexicon : ZhuyinLexicon {
    override val status = EngineStatus.READY
    override val statusDetail = ""
    override val isReady = false

    override fun start(context: Context, onReady: () -> Unit) = Unit

    override fun close() = Unit

    override fun lookup(queries: List<String>, before: String): List<Candidate> = emptyList()

    override fun learn(phrases: List<Pair<String, String>>) = Unit
}
