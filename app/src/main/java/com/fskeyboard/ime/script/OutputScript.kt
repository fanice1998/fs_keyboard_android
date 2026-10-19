package com.fskeyboard.ime.script

/**
 * Converts text a reranker invented.
 * Rime candidates are already converted by the schema's t2tw filter, so they
 * must not be passed through OpenCC a second time.
 */
internal fun interface OutputScript {
    fun convert(text: String): String

    companion object {
        val IDENTITY: OutputScript = OutputScript { text -> text }
    }
}
