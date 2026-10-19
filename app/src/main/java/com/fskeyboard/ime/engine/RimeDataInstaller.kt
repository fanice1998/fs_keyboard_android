package com.fskeyboard.ime.engine

import android.content.Context
import java.io.File

internal data class RimePaths(
    val sharedDir: File,
    val userDir: File,
)

/**
 * Copies assets/rime/shared into the app files directory when VERSION changes.
 * files/rime/user is never wiped, so the user dictionary survives asset updates.
 */
internal class RimeDataInstaller(private val context: Context) {
    fun sync(): RimePaths {
        val root = File(context.filesDir, "rime")
        val shared = File(root, "shared")
        val user = File(root, "user")
        File(user, "build").mkdirs()

        val desired = context.assets.open("$ASSET_ROOT/VERSION")
            .bufferedReader()
            .use { it.readText().trim() }
        val stamp = File(shared, ".stamp")
        val current = if (stamp.isFile) stamp.readText().trim() else ""
        val schema = File(shared, "fs_zhuyin.schema.yaml")
        if (current != desired || !schema.isFile) {
            shared.deleteRecursively()
            copyTree(ASSET_ROOT, shared)
            stamp.writeText(desired)
            // The compiled prism lives here. Drop it so the new schema is built;
            // files/rime/user/*.userdb is left in place.
            File(user, "build").deleteRecursively()
        }
        return RimePaths(sharedDir = shared, userDir = user)
    }

    private fun copyTree(assetPath: String, dest: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) {
            copyTree("$assetPath/$child", File(dest, child))
        }
    }

    private companion object {
        const val ASSET_ROOT = "rime/shared"
    }
}
