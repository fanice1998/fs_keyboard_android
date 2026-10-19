package com.fskeyboard.ime.rerank.onnx

import android.content.Context
import java.io.File

/**
 * Resolves model.onnx once per session. The shipped file is
 * assets/rerank/model.onnx. [resolve] copies it to files/rerank so ONNX
 * Runtime can map a path. A non-empty files/rerank/model.onnx already on
 * disk wins. A missing or empty file leaves the original candidate order.
 */
internal class OnnxModelStore(context: Context) {
    private val appContext = context.applicationContext

    fun hasModel(): Boolean {
        if (localFile().let { it.isFile && it.length() > 0L }) return true
        return assetHasBytes
    }

    fun stamp(): Long {
        val local = localFile()
        return if (local.isFile && local.length() > 0L) local.lastModified() else 0L
    }

    fun resolve(): File? {
        val local = localFile()
        if (local.isFile && local.length() > 0L) return local
        synchronized(this) {
            if (local.isFile && local.length() > 0L) return local
            try {
                appContext.assets.open(ASSET_PATH).use { input ->
                    local.parentFile?.mkdirs()
                    local.outputStream().use { input.copyTo(it) }
                }
            } catch (_: Exception) {
                if (local.exists()) local.delete()
                return null
            }
            if (local.length() == 0L) {
                local.delete()
                return null
            }
            return local
        }
    }

    private fun localFile(): File {
        return File(appContext.filesDir, "$DIR/$FILE_NAME")
    }

    private val assetHasBytes: Boolean by lazy {
        runCatching {
            appContext.assets.open(ASSET_PATH).use { input -> input.read() != -1 }
        }.getOrDefault(false)
    }

    companion object {
        const val DIR = "rerank"
        const val FILE_NAME = "model.onnx"
        const val ASSET_PATH = "rerank/model.onnx"
    }
}
