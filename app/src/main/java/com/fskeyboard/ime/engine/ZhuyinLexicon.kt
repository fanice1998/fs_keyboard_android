package com.fskeyboard.ime.engine

import android.content.Context
import com.fskeyboard.ime.candidate.Candidate

/**
 * Exact zhuyin lookup. The keyboard asks this interface and does not call librime.
 */
internal interface ZhuyinLexicon {
    val status: EngineStatus
    val statusDetail: String
    val isReady: Boolean

    fun start(context: Context, onReady: () -> Unit)
    fun close()

    /** [code] is syllable readings joined by a space. Each hit uses a negative index. */
    fun lookup(code: String): List<Candidate>
}
