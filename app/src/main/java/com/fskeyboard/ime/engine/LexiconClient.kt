package com.fskeyboard.ime.engine

import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a published ChiaKey lexicon. Artifact URLs have to pass
 * [LexiconDownload.allowed] before any connection. The GitHub API that names
 * the latest release is not an artifact URL.
 */
internal object LexiconClient {
    fun bytes(url: String, fetch: (String) -> ByteArray = { download(it) }): ByteArray {
        if (!LexiconDownload.allowed(url)) error("blocked url")
        return fetch(url)
    }

    fun latestReleaseJson(): String = text(LATEST)

    private fun download(url: String): ByteArray = open(url, "application/octet-stream").useStream()

    private fun text(url: String): String = open(url, "application/vnd.github+json").useStream()
        .toString(Charsets.UTF_8)

    private fun open(url: String, accept: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 180_000
        connection.setRequestProperty("User-Agent", "FsKeyboard")
        connection.setRequestProperty("Accept", accept)
        return connection
    }

    private fun HttpURLConnection.useStream(): ByteArray = try {
        val code = responseCode
        if (code !in 200..299) error("http $code")
        inputStream.use { it.readBytes() }
    } finally {
        disconnect()
    }

    private const val LATEST =
        "https://api.github.com/repos/chiakich/ChiaKey-Lexicon/releases/latest"
}
