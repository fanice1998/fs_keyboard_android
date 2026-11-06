package com.fskeyboard.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class LexiconInstallerTest {
    @Test
    fun installsTheWholeReleaseWhenTheChecksumAgrees() {
        val db = "SQLite-whole-lexicon".toByteArray()
        val sha = sha256(db)
        val files = hashMapOf(
            MANIFEST_URL to manifest(sha).toByteArray(),
            CHECKSUM_URL to checksum(sha).toByteArray(),
            DB_URL to db,
        )
        val root = tempRoot()
        val installer = LexiconInstaller(root) { url -> files[url] ?: error(url) }
        assertTrue(installer.install(releaseJson()))
        assertEquals(db.toList(), installer.database.readBytes().toList())
        root.deleteRecursively()
    }

    @Test
    fun refusesADatabaseOutsideTheAllowlist() {
        val root = tempRoot()
        File(root, "ChiaKeySource.db").writeBytes(byteArrayOf(9))
        val sha = "a".repeat(64)
        val evil = "https://evil.example/ChiaKeySource.db"
        val fetched = mutableListOf<String>()
        val files = hashMapOf(
            MANIFEST_URL to manifest(sha, evil).toByteArray(),
            CHECKSUM_URL to checksum(sha).toByteArray(),
            evil to byteArrayOf(1),
        )
        val installer = LexiconInstaller(root) { url ->
            fetched += url
            files[url] ?: error(url)
        }
        assertFalse(installer.install(releaseJson()))
        assertEquals(listOf(9.toByte()), installer.database.readBytes().toList())
        assertFalse(fetched.contains(evil))
        root.deleteRecursively()
    }

    @Test
    fun refusesAnUnsupportedSchema() {
        val root = tempRoot()
        val db = "SQLite-whole-lexicon".toByteArray()
        val sha = sha256(db)
        val files = hashMapOf(
            MANIFEST_URL to manifest(sha).replace(
                "\"database_schema_version\": 1",
                "\"database_schema_version\": 2",
            ).toByteArray(),
            CHECKSUM_URL to checksum(sha).toByteArray(),
            DB_URL to db,
        )
        val installer = LexiconInstaller(root) { url -> files[url] ?: error(url) }
        assertFalse(installer.install(releaseJson()))
        assertFalse(installer.database.exists())
        root.deleteRecursively()
    }

    @Test
    fun leavesUserLearningUntouched() {
        val db = "SQLite-whole-lexicon".toByteArray()
        val sha = sha256(db)
        val files = hashMapOf(
            MANIFEST_URL to manifest(sha).toByteArray(),
            CHECKSUM_URL to checksum(sha).toByteArray(),
            DB_URL to db,
        )
        val root = tempRoot()
        val user = File(root, "SmartMandarinUserData.db").apply { writeBytes(byteArrayOf(7)) }
        val installer = LexiconInstaller(root) { url -> files[url] ?: error(url) }
        assertTrue(installer.install(releaseJson()))
        assertEquals(listOf(7.toByte()), user.readBytes().toList())
        root.deleteRecursively()
    }

    @Test
    fun readsTheManifestWhenTheReleaseBodyContainsEscapes() {
        val db = "SQLite-whole-lexicon".toByteArray()
        val sha = sha256(db)
        val files = hashMapOf(
            MANIFEST_URL to manifest(sha).toByteArray(),
            CHECKSUM_URL to checksum(sha).toByteArray(),
            DB_URL to db,
        )
        val root = tempRoot()
        val installer = LexiconInstaller(root) { url -> files[url] ?: error(url) }
        val release = """
            {"body":"see \"lexicon-manifest.json\"\nnext","assets":[{"name":"lexicon-manifest.json","browser_download_url":"$MANIFEST_URL"}]}
        """.trimIndent()
        assertTrue(installer.install(release))
        assertEquals(db.toList(), installer.database.readBytes().toList())
        root.deleteRecursively()
    }

    @Test
    fun keepsTheOldFileWhenTheChecksumDisagrees() {
        val root = tempRoot()
        val existing = File(root, "ChiaKeySource.db").apply { writeBytes(byteArrayOf(9)) }
        val files = hashMapOf(
            MANIFEST_URL to manifest("d".repeat(64)).toByteArray(),
            CHECKSUM_URL to checksum("d".repeat(64)).toByteArray(),
            DB_URL to byteArrayOf(1, 2, 3),
        )
        val installer = LexiconInstaller(root) { url -> files[url] ?: error(url) }
        assertFalse(installer.install(releaseJson()))
        assertEquals(listOf(9.toByte()), existing.readBytes().toList())
        root.deleteRecursively()
    }

    private fun tempRoot() = File.createTempFile("chiakey-lexicon", "").apply { delete(); mkdirs() }

    private fun releaseJson(): String = """
        {"assets":[{"name":"lexicon-manifest.json","browser_download_url":"$MANIFEST_URL"}]}
    """.trimIndent()

    private fun manifest(dbSha: String, dbUrl: String = DB_URL): String = """
        {
          "database_schema_version": 1,
          "artifacts": [
            {"kind":"chiakey-source-db","url":"$dbUrl","filename":"ChiaKeySource.db","sha256":"$dbSha"},
            {"kind":"checksum","url":"$CHECKSUM_URL","filename":"SHA256SUMS","sha256":"${"b".repeat(64)}"}
          ]
        }
    """.trimIndent()

    private fun checksum(dbSha: String) = "$dbSha  ChiaKeySource.db\n"

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MANIFEST_URL =
            "https://github.com/chiakich/ChiaKey-Lexicon/releases/download/2026.06.7/lexicon-manifest.json"
        const val DB_URL =
            "https://github.com/chiakich/ChiaKey-Lexicon/releases/download/2026.06.7/ChiaKeySource.db"
        const val CHECKSUM_URL =
            "https://cdn.chiaki.ch/chiakey/lexicon/SHA256SUMS"
    }
}
