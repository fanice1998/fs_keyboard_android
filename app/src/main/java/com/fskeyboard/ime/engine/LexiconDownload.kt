package com.fskeyboard.ime.engine

/**
 * Rules for fetching the published ChiaKey lexicon. The file is installed
 * whole, including the layers ChiaKey already folded in, such as libchewing,
 * rime-essay, and the reading supplements.
 */
internal object LexiconDownload {
    private val prefixes = listOf(
        "https://github.com/chiakich/ChiaKey-Lexicon/releases/download/",
        "https://cdn.chiaki.ch/chiakey/lexicon/",
    )

    fun allowed(url: String): Boolean {
        if (url.any { it.isWhitespace() }) return false
        return prefixes.any { prefix ->
            val rest = url.removePrefix(prefix)
            url.startsWith(prefix) && rest.isNotEmpty() && !rest.contains("..")
        }
    }

    /** [checksumFile] is a SHA256SUMS document: `digest  filename` per line. */
    fun digestAgrees(manifestSha256: String, checksumFile: String, filename: String): Boolean {
        if (filename.isEmpty() || filename.contains('/') || filename.contains('\\')) return false
        val want = manifestSha256.trim().lowercase()
        if (want.length != 64 || want.any { it !in '0'..'9' && it !in 'a'..'f' }) return false
        val line = checksumFile.lineSequence().map { it.trim() }.firstOrNull { row ->
            val parts = row.split(Regex("\\s+"))
            parts.size >= 2 && parts.last().removePrefix("*") == filename
        } ?: return false
        val got = line.split(Regex("\\s+")).first().lowercase()
        return got == want
    }

    fun schemaSupported(version: Int): Boolean = version == 1

    /** The bytes that get renamed into place. Nothing is stripped from the release. */
    fun installPayload(published: ByteArray): ByteArray = published.copyOf()
}
