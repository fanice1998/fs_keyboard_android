package com.fskeyboard.ime.engine

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * One pass over ChiaKey `unigrams`, then `user_unigrams` when that table exists.
 * The scan is not on the key thread. It does not run a prefix query.
 */
internal object ChiaKeyUnigramScan {
    /**
     * Reads unigrams whose qstring length is [syllables] times two bytes.
     * The connection is read-only and, when the platform allows it, immutable,
     * so this scan does not take a lock the engine needs.
     */
    fun read(root: File, accept: (qstring: String, text: String, probability: Double) -> Unit) {
        read(root, 1..Int.MAX_VALUE, accept)
    }

    fun read(
        root: File,
        syllables: IntRange,
        accept: (qstring: String, text: String, probability: Double) -> Unit,
    ) {
        val database = File(root, "ChiaKeySource.db")
        if (!database.isFile) return
        val where = lengthClause(syllables)
        readTable(database, "unigrams", where, accept)
        val user = File(root, "writable/SmartMandarinUserData.db")
        if (user.isFile) readTable(user, "user_unigrams", where, accept)
    }

    /** Read-only `user_unigrams`. Missing file or table yields nothing. Does not open the engine. */
    fun readUserFile(file: File, accept: (qstring: String, text: String, probability: Double) -> Unit) {
        if (!file.isFile) return
        readTable(file, "user_unigrams", null, accept)
    }

    /** One syllable is two ASCII bytes in ChiaKey's absolute order. */
    internal fun lengthClause(syllables: IntRange): String? {
        val first = syllables.first.coerceAtLeast(1)
        if (first == 1 && syllables.last == Int.MAX_VALUE) return null
        val min = first * 2
        val max = syllables.last.coerceAtLeast(first) * 2
        return "length(qstring) BETWEEN $min AND $max"
    }

    private fun readTable(
        file: File,
        table: String,
        where: String?,
        accept: (qstring: String, text: String, probability: Double) -> Unit,
    ) {
        val db = openReadOnly(file)
        try {
            if (!hasTable(db, table)) return
            val sql = if (where == null) {
                "SELECT qstring, current, probability FROM $table"
            } else {
                "SELECT qstring, current, probability FROM $table WHERE $where"
            }
            db.rawQuery(sql, null).use { cursor ->
                val qstring = cursor.getColumnIndex("qstring")
                val text = cursor.getColumnIndex("current")
                val probability = cursor.getColumnIndex("probability")
                if (qstring < 0 || text < 0 || probability < 0) return
                while (cursor.moveToNext()) {
                    if (cursor.isNull(qstring) || cursor.isNull(text) || cursor.isNull(probability)) continue
                    accept(cursor.getString(qstring), cursor.getString(text), cursor.getDouble(probability))
                }
            }
        } finally {
            db.close()
        }
    }

    /**
     * Immutable open takes no file lock, so this scan does not stall the
     * engine. A platform that rejects the URI falls back to a plain read.
     */
    private fun openReadOnly(file: File): SQLiteDatabase {
        val uri = "file:${file.absolutePath}?mode=ro&immutable=1"
        val opened = try {
            SQLiteDatabase.openDatabase(uri, null, SQLiteDatabase.OPEN_READONLY)
        } catch (_: RuntimeException) {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        }
        try {
            opened.execSQL("PRAGMA busy_timeout = 2000")
        } catch (_: RuntimeException) {
            // The scan still works if this platform rejects the pragma.
        }
        return opened
    }

    private fun hasTable(db: SQLiteDatabase, table: String): Boolean {
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table),
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }
}
