package com.fskeyboard.ime.bridge

internal object RimeNative {
    @Volatile
    private var loaded = false

    private var unavailable = false

    fun isLoaded(): Boolean = loaded

    @Synchronized
    fun load(): Boolean {
        if (loaded) return true
        if (unavailable) return false
        return try {
            System.loadLibrary("rime")
            System.loadLibrary("fskeyboard")
            loaded = true
            true
        } catch (_: UnsatisfiedLinkError) {
            unavailable = true
            false
        }
    }

    external fun startup(sharedDir: String, userDir: String, logDir: String): Boolean
    external fun shutdown()
    external fun createSession(): Long
    external fun destroySession(sessionId: Long)
    external fun selectSchema(sessionId: Long, schemaId: String): Boolean
    external fun processKey(sessionId: Long, keycode: Int, mask: Int, limit: Int): RimeResponse?
    external fun selectCandidate(sessionId: Long, index: Int, limit: Int): RimeResponse?
    external fun snapshot(sessionId: Long, limit: Int): RimeResponse?
    external fun clearComposition(sessionId: Long)
    external fun setOption(sessionId: Long, name: String, value: Boolean)
    external fun getOption(sessionId: Long, name: String): Boolean
    external fun lastError(): String
    external fun version(): String
}
