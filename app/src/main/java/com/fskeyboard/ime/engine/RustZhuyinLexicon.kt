package com.fskeyboard.ime.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.fskeyboard.ime.candidate.Candidate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class RustZhuyinLexicon : ZhuyinLexicon {
    @Volatile
    private var statusValue = EngineStatus.LOADING

    @Volatile
    private var detailValue = ""

    override val status: EngineStatus
        get() = statusValue

    override val statusDetail: String
        get() = detailValue

    override val isReady: Boolean
        get() = statusValue == EngineStatus.READY

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fskeyboard-lexicon").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override fun start(context: Context, onReady: () -> Unit) {
        if (!started.compareAndSet(false, true)) {
            main.post(onReady)
            return
        }
        val app = context.applicationContext
        worker.execute {
            load(app)
            main.post {
                if (!closed.get()) onReady()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.shutdown()
    }

    override fun lookup(queries: List<String>): List<Candidate> {
        if (!isReady || queries.isEmpty()) return emptyList()
        val raw = try {
            RustLexiconNative.nativeLookup(queries.joinToString(" "))
        } catch (error: UnsatisfiedLinkError) {
            Log.e(TAG, "lexicon lookup failed", error)
            return emptyList()
        }
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.lineSequence()
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@mapNotNull null
                val span = line.substring(0, tab).toIntOrNull() ?: return@mapNotNull null
                span to line.substring(tab + 1)
            }
            .mapIndexed { index, (span, text) ->
                Candidate(text = text, engineIndex = -(index + 1), span = span)
            }
            .toList()
    }

    private fun load(context: Context) {
        try {
            val start = SystemClock.elapsedRealtime()
            RustLexiconNative.load()
            val table = context.assets.open(TABLE).use { it.readBytes() }
            val dict = context.assets.open(DICT).use { it.readBytes() }
            val rc = RustLexiconNative.nativeLoad(table, dict)
            if (rc == 0) {
                Log.i(TAG, "lexicon loaded in ${SystemClock.elapsedRealtime() - start}ms")
                statusValue = EngineStatus.READY
            } else {
                statusValue = EngineStatus.FAILED
                detailValue = "nativeLoad $rc"
            }
        } catch (error: UnsatisfiedLinkError) {
            statusValue = EngineStatus.MISSING_LIBRARY
            detailValue = error.message.orEmpty()
            Log.i(TAG, "fs_lexicon is not packaged")
        } catch (error: Exception) {
            statusValue = EngineStatus.FAILED
            detailValue = error.message.orEmpty()
            Log.e(TAG, "lexicon load failed", error)
        }
    }

    private companion object {
        const val TAG = "FsKeyboard"
        const val TABLE = "lexicon/mcbopomofo.tsv"
        const val DICT = "rime/shared/fs_zhuyin.dict.yaml"
    }
}
