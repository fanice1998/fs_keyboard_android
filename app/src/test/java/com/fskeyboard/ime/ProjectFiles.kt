package com.fskeyboard.ime

import java.io.File

internal fun readProjectFile(relative: String): String {
    val file = listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
        ?: error("missing $relative from ${File(".").absolutePath}")
    return file.readText()
}
