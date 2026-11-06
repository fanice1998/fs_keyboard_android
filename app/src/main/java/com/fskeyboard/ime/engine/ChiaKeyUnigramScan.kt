package com.fskeyboard.ime.engine

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * One pass over ChiaKey `unigrams`, then `user_unigrams` when that table exists.
 * The scan is not on the key thread. It does not run a prefix query.
 */
internal object ChiaKeyUnigramScan {
    fun read(root: File, accept: (qstring: String, text: String, probability: Double) -> Unit) {
        val database = File(root, "ChiaKeySource.db")
        if (!database.isFile) return
        readTable(database, "unigrams", accept)
        val user = File(root, "writable/SmartMandarinUserData.db")
        if (user.isFile) readTable(user, "user_unigrams", accept)
    }

    private fun readTable(
        file: File,
        table: String,
        accept: (qstring: String, text: String, probability: Double) -> Unit,
    ) {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            if (!hasTable(db, table)) return
            db.rawQuery("SELECT qstring, current, probability FROM $table", null).use { cursor ->
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

    private fun hasTable(db: SQLiteDatabase, table: String): Boolean {
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table),
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }
}
