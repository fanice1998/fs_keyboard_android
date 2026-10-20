package com.fskeyboard.ime.engine

import android.content.Context
import com.fskeyboard.ime.candidate.Candidate

/**
 * Zhuyin lookup. The keyboard asks this interface and does not call librime.
 */
internal interface ZhuyinLexicon {
    val status: EngineStatus
    val statusDetail: String
    val isReady: Boolean

    fun start(context: Context, onReady: () -> Unit)
    fun close()

    /**
     * Candidates for the front of [queries], from [com.fskeyboard.ime.keyboard.ZhuyinComposer.queries].
     * The longest phrase comes first. Each hit carries its span and a negative index.
     */
    fun lookup(queries: List<String>): List<Candidate>
}
