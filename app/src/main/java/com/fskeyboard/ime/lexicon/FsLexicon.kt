package com.fskeyboard.ime.lexicon

import java.io.File

/**
 * One lexicon row. [reading] is bopomofo with tone marks and no spaces.
 * A row has no left context. [source] is [SOURCE_BUILTIN] or [SOURCE_USER].
 */
internal data class Row(
    val reading: String,
    val text: String,
    val weight: Double,
    val source: String,
)

/**
 * UTF-8 lexicon. The first line is exactly [VERSION].
 * Later lines are `reading`, `text`, `weight`, `source` separated by tabs.
 * User phrases and converted builtin rows share this file.
 */
internal class FsLexicon(private val file: File) {
    fun rows(): List<Row> = synchronized(gate) { read() }

    fun addUser(reading: String, text: String, weight: Double) = synchronized(gate) {
        if (!fieldOk(reading) || !fieldOk(text) || !weight.isFinite()) return
        val current = read()
        val next = ArrayList<Row>(current.size + 1)
        var replaced = false
        for (row in current) {
            if (!replaced && row.source == SOURCE_USER && row.reading == reading && row.text == text) {
                next += Row(reading, text, weight, SOURCE_USER)
                replaced = true
            } else if (!(row.source == SOURCE_USER && row.reading == reading && row.text == text)) {
                next += row
            }
        }
        if (!replaced) next += Row(reading, text, weight, SOURCE_USER)
        write(next)
    }

    /** Drops a phrase the user added. Builtin rows with the same text stay. */
    fun removeUser(reading: String, text: String) = synchronized(gate) {
        val current = read()
        val next = current.filterNot { row ->
            row.source == SOURCE_USER && row.reading == reading && row.text == text
        }
        if (next.size != current.size) write(next)
    }

    /** Replaces builtin rows and keeps every user row already stored. */
    fun replaceBuiltin(rows: List<Row>) = synchronized(gate) {
        val user = read().filter { it.source == SOURCE_USER }
        val builtin = ArrayList<Row>(rows.size)
        for (row in rows) {
            if (!fieldOk(row.reading) || !fieldOk(row.text) || !row.weight.isFinite()) continue
            builtin += Row(row.reading, row.text, row.weight, SOURCE_BUILTIN)
        }
        write(builtin + user)
    }

    private fun read(): List<Row> {
        if (!file.isFile) return emptyList()
        val lines = try {
            file.readLines(Charsets.UTF_8)
        } catch (_: Exception) {
            return emptyList()
        }
        if (lines.isEmpty() || lines[0] != VERSION) return emptyList()
        val out = ArrayList<Row>()
        for (index in 1 until lines.size) {
            parse(lines[index])?.let { out += it }
        }
        return out
    }

    private fun parse(line: String): Row? {
        if (line.isEmpty()) return null
        val parts = line.split('\t')
        if (parts.size != 4) return null
        val reading = parts[0]
        val text = parts[1]
        val weight = parts[2].toDoubleOrNull() ?: return null
        val source = parts[3]
        if (source != SOURCE_BUILTIN && source != SOURCE_USER) return null
        if (!fieldOk(reading) || !fieldOk(text) || !weight.isFinite()) return null
        return Row(reading, text, weight, source)
    }

    private fun write(rows: List<Row>) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory) return
        val text = buildString {
            append(VERSION)
            append('\n')
            for (row in rows) {
                append(row.reading)
                append('\t')
                append(row.text)
                append('\t')
                append(row.weight.toString())
                append('\t')
                append(row.source)
                append('\n')
            }
        }
        val directory = parent ?: return
        val partial = File(directory, "${file.name}.partial")
        partial.writeText(text, Charsets.UTF_8)
        if (!partial.renameTo(file)) {
            partial.copyTo(file, overwrite = true)
            partial.delete()
        }
    }

    private val gate = Any()

    companion object {
        const val VERSION = "fslexicon 1"
        const val SOURCE_BUILTIN = "builtin"
        const val SOURCE_USER = "user"
        const val FILE_NAME = "lexicon/fslexicon.txt"

        fun fieldOk(value: String): Boolean {
            if (value.isEmpty()) return false
            return value.indexOf('\t') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0
        }
    }
}
