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

        /** [table] is the phrase table and [dict] the Rime dictionary, both UTF-8. */
        @JvmStatic
        external fun nativeLoad(table: ByteArray, dict: ByteArray): Int

        /** `span<TAB>text` lines for syllable queries joined by a space. */
        @JvmStatic
        external fun nativeLookup(code: String): String
    }
}
