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
                slot.engine = loadLingxiEngine(root)
                log("lingxi resident kb=${Debug.getPss()}")
            } catch (error: Exception) {
                log("lingxi pack refused ${error.javaClass.simpleName}")
            }
        }, "fskeyboard-lingxi").apply { isDaemon = true }.start()
    }

    private fun install(context: Context): File {
        val root = File(context.filesDir, "lingxi")
        for (name in ASSETS) {
            val asset = "lingxi/$name"
            val dest = File(root, name)
            // openFd only works for uncompressed assets. The big tables are stored
            // that way and skipped when the copy already matches. Compressed json
            // falls through and is copied from the stream.
            val packed = runCatching { context.assets.openFd(asset).use { it.length } }.getOrNull()
            if (packed != null && dest.isFile && dest.length() == packed) continue
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
        return root
    }

    private val ASSETS = listOf(
        "lexicon.bin",
        "lm.bin",
        "english/words.bin",
        "select/model.onnx",
        "select/vocab.json",
        "select/manifest.json",
    )
}

internal class LingxiSlot {
    @Volatile var engine: LingxiEngine? = null
}
