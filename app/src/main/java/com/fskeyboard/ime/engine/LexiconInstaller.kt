package com.fskeyboard.ime.engine

import java.io.File
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.moveTo

/**
 * Installs one published ChiaKey lexicon release into [database].
 * The bytes are the release file unchanged. A failed check leaves the
 * previous file, and never deletes `SmartMandarinUserData.db`.
 */
internal class LexiconInstaller(
    private val root: File,
    private val fetch: (String) -> ByteArray,
) {
    val database: File = File(root, DATABASE)

    fun install(releaseJson: String): Boolean = try {
        place(releaseJson)
    } catch (_: Exception) {
        false
    }

    private fun place(releaseJson: String): Boolean {
        val manifestUrl = manifestUrl(releaseJson) ?: return false
        if (!LexiconDownload.allowed(manifestUrl)) return false
        val manifest = jsonObject(fetch(manifestUrl).toString(Charsets.UTF_8)) ?: return false
        if (!LexiconDownload.schemaSupported(intField(manifest, "database_schema_version"))) {
            return false
        }
        val db = artifact(manifest, "chiakey-source-db") ?: return false
        val checksum = artifact(manifest, "checksum") ?: return false
        if (!LexiconDownload.allowed(db.url) || !LexiconDownload.allowed(checksum.url)) return false
        val sums = fetch(checksum.url).toString(Charsets.UTF_8)
        if (!LexiconDownload.digestAgrees(db.sha256, sums, db.filename)) return false
        val published = fetch(db.url)
        if (sha256(published) != db.sha256.trim().lowercase()) return false
        val payload = LexiconDownload.installPayload(published)
        root.mkdirs()
        val partial = File(root, "$DATABASE.partial")
        partial.writeBytes(payload)
        partial.toPath().moveTo(
            database.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        return true
    }

    private fun manifestUrl(releaseJson: String): String? {
        val assets = jsonObject(releaseJson)?.get("assets") as? List<*> ?: return null
        for (item in assets) {
            val asset = item as? Map<*, *> ?: continue
            if (asset["name"] == "lexicon-manifest.json") {
                val url = asset["browser_download_url"] as? String ?: return null
                if (url.isNotEmpty()) return url
            }
        }
        return null
    }

    private fun artifact(manifest: Map<String, Any?>, kind: String): Artifact? {
        val artifacts = manifest["artifacts"] as? List<*> ?: return null
        for (item in artifacts) {
            val row = item as? Map<*, *> ?: continue
            if (row["kind"] != kind) continue
            val url = row["url"] as? String ?: return null
            val filename = row["filename"] as? String ?: return null
            val sha256 = row["sha256"] as? String ?: return null
            if (url.isEmpty() || filename.isEmpty() || sha256.isEmpty()) return null
            return Artifact(url, filename, sha256)
        }
        return null
    }

    private fun jsonObject(text: String): Map<String, Any?>? {
        val parsed = MinimalJson.parse(text)
        @Suppress("UNCHECKED_CAST")
        return parsed as? Map<String, Any?>
    }

    private fun intField(obj: Map<String, Any?>, key: String): Int = when (val value = obj[key]) {
        is Int -> value
        is Long -> value.toInt()
        is Double -> value.toInt()
        else -> -1
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private data class Artifact(val url: String, val filename: String, val sha256: String)

    private companion object {
        const val DATABASE = "ChiaKeySource.db"
    }
}
