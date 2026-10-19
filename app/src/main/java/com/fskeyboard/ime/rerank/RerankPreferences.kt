package com.fskeyboard.ime.rerank

import android.content.Context

internal class RerankPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    companion object {
        const val NAME = "fskeyboard"
        const val KEY_ENABLED = "rerank_enabled"
    }
}
