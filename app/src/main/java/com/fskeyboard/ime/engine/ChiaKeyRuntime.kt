package com.fskeyboard.ime.engine

import com.fskeyboard.ime.candidate.PartialLexicon
import java.io.File

/**
 * The zhuyin engine and its unigram index live for the process, not for one
 * [android.inputmethodservice.InputMethodService] instance. Switching to
 * another keyboard destroys that service. Closing the database there made the
 * next open wait on the scan, and a second scan of the same table exhausted
 * the heap. The listener is replaced when a new service attaches, and the
 * lexicon already built is handed to that listener.
 */
internal object ChiaKeyRuntime {
    private val gate = Any()
    private var session: ChiaKeySession? = null
    private var listener: ((PartialLexicon) -> Unit)? = null
    private var latest: PartialLexicon? = null
    private var epoch = 0
    private var opening = false
    private var scanning = false
    private var scanComplete = false

    fun session(root: File): ChiaKeySession = synchronized(gate) {
        session ?: ChiaKeySession(root).also { session = it }
    }

    /** Remembers [onLexicon] and delivers the index already on hand. */
    fun attach(onLexicon: (PartialLexicon) -> Unit) {
        val snapshot = synchronized(gate) {
            listener = onLexicon
            val lexicon = latest ?: return@synchronized null
            epoch to lexicon
        } ?: return
        publishIfCurrent(onLexicon, snapshot.first, snapshot.second)
    }

    fun detach(onLexicon: (PartialLexicon) -> Unit) = synchronized(gate) {
        if (listener === onLexicon) listener = null
    }

    fun claimOpen(): Boolean = synchronized(gate) {
        if (opening) false else {
            opening = true
            true
        }
    }

    fun finishOpen() = synchronized(gate) {
        opening = false
    }

    /** One scan at a time. A finished scan is not started again. */
    fun claimScan(): Boolean = synchronized(gate) {
        if (scanComplete || scanning) false else {
            scanning = true
            true
        }
    }

    fun finishScan() = synchronized(gate) {
        scanning = false
    }

    /**
     * Publishes [lexicon]. [done] marks the sentence index.
     * A [done] of false after that index is already published does not move the
     * epoch or replace [latest]. The epoch still drops a delivery that lost to a
     * newer one; it does not itself reject a later false publish.
     */
    fun offer(lexicon: PartialLexicon, done: Boolean) {
        val snapshot = synchronized(gate) {
            if (!done && scanComplete) {
                null
            } else {
                epoch += 1
                latest = lexicon
                if (done) scanComplete = true
                listener?.let { it to epoch }
            }
        } ?: return
        publishIfCurrent(snapshot.first, snapshot.second, lexicon)
    }

    /** True once the sentence index has been published. */
    fun indexDone(): Boolean = synchronized(gate) { scanComplete }

    /**
     * Drops a delivery that lost the race with a newer index. Publishing the
     * older one afterwards would hide the sentence lexicon behind characters.
     */
    private fun publishIfCurrent(
        onLexicon: (PartialLexicon) -> Unit,
        token: Int,
        lexicon: PartialLexicon,
    ) {
        val current = synchronized(gate) {
            if (epoch == token && listener === onLexicon) onLexicon else null
        }
        current?.invoke(lexicon)
    }

    /** Drops process state. Unit tests call this so later tests start clean. */
    fun resetForTest() = synchronized(gate) {
        session = null
        listener = null
        latest = null
        opening = false
        scanning = false
        scanComplete = false
        epoch = 0
    }
}
