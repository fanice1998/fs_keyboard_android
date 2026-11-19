package com.fskeyboard.ime.lingxi

import android.content.Context
import android.os.Debug
import java.io.File

/** Copies the packaged dictionary and select weights once, then publishes the engine. */
internal object LingxiRuntime {
    fun start(context: Context, slot: LingxiSlot, log: (String) -> Unit) {
        Thread({
            try {
                val root = install(context)
                val user = File(context.filesDir, "chiakey/writable/SmartMandarinUserData.db")
                val engine = loadLingxiEngine(root, user, log)
                slot.engine = engine
                log("lingxi resident kb=${Debug.getPss()}")
            } catch (error: Exception) {
                log("lingxi pack refused ${error.javaClass.simpleName}")
            }
        }, "fskeyboard-lingxi").apply { isDaemon = true }.start()
    }

    private fun install(context: Context): File {
        val root = File(context.filesDir, "lingxi")
        for (name in ASSETS) copyAsset(context, root, name)
        // A stale marker would describe a lexicon this pack did not merge.
        if (assetExists(context, MERGED_MARKER)) {
            copyAsset(context, root, MERGED_MARKER)
        } else {
            File(root, MERGED_MARKER).delete()
        }
        return root
    }

    private fun assetExists(context: Context, name: String): Boolean {
        return try {
            context.assets.open("lingxi/$name").close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun copyAsset(context: Context, root: File, name: String) {
        val asset = "lingxi/$name"
        val dest = File(root, name)
        // openFd only works for uncompressed assets. The big tables are stored
        // that way and skipped when the copy already matches. Compressed json
        // falls through and is copied from the stream.
        val packed = runCatching { context.assets.openFd(asset).use { it.length } }.getOrNull()
        if (packed != null && dest.isFile && dest.length() == packed) return
        dest.parentFile?.mkdirs()
        val partial = File(dest.parentFile, "${dest.name}.partial")
        context.assets.open(asset).use { input ->
            partial.outputStream().use { output -> input.copyTo(output) }
        }
        if (!partial.renameTo(dest)) {
            partial.copyTo(dest, overwrite = true)
            partial.delete()
        }
    }

    private val ASSETS = listOf(
        "lexicon.bin",
        "lm.bin",
        "english/words.bin",
        "select/model.onnx",
        "select/vocab.json",
        "select/manifest.json",
    )

    private const val MERGED_MARKER = "lexicon.merged"
}

internal class LingxiSlot {
    @Volatile var engine: LingxiEngine? = null
}
