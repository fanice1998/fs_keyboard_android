package com.fskeyboard.ime.engine

import java.io.File
import java.io.InputStream

/**
 * Copies the packaged ChiaKey database into the app files directory.
 * A database that is already there is left in place. [install] does not
 * create or delete `writable/`.
 */
internal object BundledChiaKeyLexicon {
    const val ASSET = "chiakey/ChiaKeySource.db"

    fun install(openAsset: () -> InputStream?, destination: File) {
        if (destination.isFile) return
        val input = try {
            openAsset()
        } catch (_: Exception) {
            null
        } ?: return
        input.use { stream ->
            val root = destination.parentFile ?: return@use
            if (!root.exists() && !root.mkdirs() && !root.isDirectory) return@use
            val partial = File(root, "${destination.name}.partial")
            try {
                partial.outputStream().use { output -> stream.copyTo(output) }
                if (destination.isFile) {
                    partial.delete()
                    return@use
                }
                if (!partial.renameTo(destination)) {
                    if (!destination.isFile) partial.copyTo(destination, overwrite = true)
                    partial.delete()
                }
            } catch (_: Exception) {
                partial.delete()
            }
        }
    }
}
