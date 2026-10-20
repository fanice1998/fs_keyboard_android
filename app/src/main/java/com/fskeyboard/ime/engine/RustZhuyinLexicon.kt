package com.fskeyboard.ime.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.fskeyboard.ime.candidate.Candidate
import java.io.File
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
    private var userFile: File? = null
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
                val parts = line.split('\t')
                val span = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val text = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Triple(span, text, parts.getOrNull(2).orEmpty())
            }
            .mapIndexed { index, (span, text, reading) ->
                Candidate(text = text, engineIndex = -(index + 1), span = span, reading = reading)
            }
            .toList()
    }

    /** Learns on the lexicon thread and appends what was new to the user file there. */
    override fun learn(phrases: List<Pair<String, String>>) {
        if (!isReady || phrases.isEmpty() || closed.get()) return
        worker.execute {
            try {
                val fresh = phrases.filter { (reading, text) -> RustLexiconNative.nativeLearn(reading, text) }
                if (fresh.isEmpty()) return@execute
                val file = userFile ?: return@execute
                file.parentFile?.mkdirs()
                file.appendText(fresh.joinToString("") { (reading, text) -> "$reading\t$text\n" })
            } catch (error: Exception) {
                Log.e(TAG, "lexicon learn failed", error)
            } catch (error: UnsatisfiedLinkError) {
                Log.e(TAG, "lexicon learn failed", error)
            }
        }
    }

    private fun load(context: Context) {
        try {
            val start = SystemClock.elapsedRealtime()
            RustLexiconNative.load()
            val compiled = context.assets.open(COMPILED).use { it.readBytes() }
            val user = File(context.filesDir, USER_FILE).also { userFile = it }
            val learned = if (user.isFile) user.readBytes() else ByteArray(0)
            val rc = RustLexiconNative.nativeLoad(compiled, learned)
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
        /** Built by tools/build-lexicon.sh from data/lexicon/mcbopomofo.tsv and the Rime dictionary. */
        const val COMPILED = "lexicon/lexicon.bin"
        const val USER_FILE = "lexicon/user.tsv"
    }
}
