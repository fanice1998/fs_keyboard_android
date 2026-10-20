package com.fskeyboard.ime.engine

/**
 * JNI entry points for libfs_lexicon.so.
 * [load] is the only place that calls [System.loadLibrary]. Unit tests must not call it.
 */
internal class RustLexiconNative private constructor() {
    companion object {
        fun load() {
            System.loadLibrary("fs_lexicon")
        }

        @JvmStatic
        external fun nativeLoad(yaml: String): Int

        @JvmStatic
        external fun nativeLookup(code: String, exact: Boolean): String
    }
}
