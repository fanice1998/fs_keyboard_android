package com.fskeyboard.ime.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fskeyboard.ime.bridge.RimeNative
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class RimeEngine(
    private val candidateLimit: Int = DEFAULT_CANDIDATE_LIMIT,
) : InputEngine {
    @Volatile
    private var statusValue = EngineStatus.LOADING

    @Volatile
    private var detailValue = ""

    override val status: EngineStatus
        get() = statusValue

    override val statusDetail: String
        get() = detailValue

    override val isReady: Boolean
        get() = statusValue == EngineStatus.READY && session != 0L

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fskeyboard-rime").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val alive = AtomicBoolean(true)
    private val closed = AtomicBoolean(false)
    private var started = false

    @Volatile
    private var session = 0L

    override fun start(context: Context, onReady: () -> Unit) {
        val app = context.applicationContext
        worker.execute {
            boot(app)
            main.post {
                if (alive.get()) onReady()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        alive.set(false)
        try {
            worker.submit {
                if (session != 0L && RimeNative.isLoaded()) {
                    RimeNative.destroySession(session)
                    session = 0L
                }
                if (started && RimeNative.isLoaded()) {
                    RimeNative.shutdown()
                    started = false
                }
            }.get()
        } catch (error: Exception) {
            Log.e(TAG, "rime shutdown failed", error)
        } finally {
            worker.shutdown()
        }
    }

    override fun processKey(keycode: Int, mask: Int): EngineSnapshot {
        if (!isReady) return EngineSnapshot()
        return call {
            timed {
                RimeNative.processKey(session, keycode, mask, candidateLimit)?.toSnapshot()
                    ?: EngineSnapshot()
            }
        }
    }

    override fun select(engineIndex: Int): EngineSnapshot {
        if (!isReady || engineIndex < 0) return EngineSnapshot()
        return call {
            timed {
                RimeNative.selectCandidate(session, engineIndex, candidateLimit)?.toSnapshot()
                    ?: EngineSnapshot()
            }
        }
    }

    override fun snapshot(): EngineSnapshot {
        if (!isReady) return EngineSnapshot()
        return call {
            timed {
                RimeNative.snapshot(session, candidateLimit)?.toSnapshot() ?: EngineSnapshot()
            }
        }
    }

    override fun clear() {
        if (!isReady) return
        call {
            RimeNative.clearComposition(session)
            EngineSnapshot()
        }
    }

    override fun setOption(name: String, value: Boolean) {
        if (!isReady) return
        call {
            RimeNative.setOption(session, name, value)
            EngineSnapshot()
        }
    }

    override fun getOption(name: String): Boolean {
        if (!isReady) return false
        return try {
            worker.submit<Boolean> { RimeNative.getOption(session, name) }.get()
        } catch (error: Exception) {
            Log.e(TAG, "getOption failed", error)
            false
        }
    }

    private fun boot(context: Context) {
        if (!alive.get()) return
        try {
            if (!RimeNative.load()) {
                statusValue = EngineStatus.MISSING_LIBRARY
                detailValue = "libfskeyboard.so"
                return
            }
            val paths = RimeDataInstaller(context).sync()
            // Empty log dir asks librime to write glog to stderr (logcat).
            val startedOk = RimeNative.startup(
                paths.sharedDir.absolutePath,
                paths.userDir.absolutePath,
                "",
            )
            if (!startedOk) {
                fail(RimeNative.lastError().ifBlank { "startup failed" })
                return
            }
            started = true
            if (!alive.get()) return
            val id = RimeNative.createSession()
            if (id == 0L) {
                fail(RimeNative.lastError().ifBlank { "create_session failed" })
                return
            }
            session = id
            if (!RimeNative.selectSchema(id, RimeSchema.ID)) {
                fail(RimeNative.lastError().ifBlank { "schema ${RimeSchema.ID} unavailable" })
                return
            }
            Log.i(TAG, "librime ${RimeNative.version()}")
            statusValue = EngineStatus.READY
            detailValue = ""
        } catch (error: Exception) {
            Log.e(TAG, "boot failed", error)
            fail(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun fail(detail: String) {
        statusValue = EngineStatus.FAILED
        detailValue = detail
    }

    private inline fun timed(block: () -> EngineSnapshot): EngineSnapshot {
        val start = System.nanoTime()
        return block().copy(decodeMillis = (System.nanoTime() - start) / 1_000_000L)
    }

    private fun call(block: () -> EngineSnapshot): EngineSnapshot {
        return try {
            worker.submit(block).get()
        } catch (error: Exception) {
            Log.e(TAG, "rime call failed", error)
            EngineSnapshot()
        }
    }

    private companion object {
        const val TAG = "FsKeyboard"
        const val DEFAULT_CANDIDATE_LIMIT = 40
    }
}
