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
     * [code] is syllable readings joined by a space.
     * [exact] keeps one tone. Otherwise every tone of those syllables is eligible.
     * Each hit uses a negative index.
     */
    fun lookup(code: String, exact: Boolean): List<Candidate>
}
