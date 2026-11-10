package com.fskeyboard.ime.lexicon

import android.content.Context
import com.fskeyboard.ime.candidate.Candidate
import com.fskeyboard.ime.candidate.PartialLexicon
import com.fskeyboard.ime.candidate.SyllableReading
import com.fskeyboard.ime.candidate.WordContinuations
import java.util.concurrent.Executor

/** Next-word rows and untone zhuyin rows. The controller does not open lexicon.bin. */
internal interface Lexicon {
    fun following(before: String): List<String>
    fun partialCandidates(readings: List<SyllableReading>): List<Candidate>
}

/**
 * [WordContinuations] from assets/lexicon/lexicon.bin, and [PartialLexicon] for partial zhuyin.
 * A missing bin leaves [following] empty. The read stays off the main thread.
 */
internal class AssetLexicon(
    private val context: Context,
    private val post: (Runnable) -> Unit,
    private val worker: Executor,
    continuations: WordContinuations?,
    partial: PartialLexicon,
) : Lexicon {
    @Volatile private var continuations: WordContinuations? = continuations

    @Volatile private var partial: PartialLexicon = partial

    override fun following(before: String): List<String> {
        return continuations?.following(before).orEmpty()
    }

    override fun partialCandidates(readings: List<SyllableReading>): List<Candidate> {
        return partial.candidates(readings)
    }

    fun replacePartial(next: PartialLexicon) {
        partial = next
    }

    /** No-op when a table was already supplied. [onReady] runs on [post] after a successful open. */
    fun ensureContinuations(stillOpen: () -> Boolean, onReady: () -> Unit) {
        if (continuations != null) return
        worker.execute {
            val loaded = try {
                context.assets.open(LEXICON_BIN).use { input ->
                    WordContinuations.open(input.readBytes())
                }
            } catch (_: Exception) {
                null
            }
            post {
                if (!stillOpen() || loaded == null) return@post
                continuations = loaded
                onReady()
            }
        }
    }

    private companion object {
        const val LEXICON_BIN = "lexicon/lexicon.bin"
    }
}
