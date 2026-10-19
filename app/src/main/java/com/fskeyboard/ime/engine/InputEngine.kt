package com.fskeyboard.ime.engine

import android.content.Context

internal interface InputEngine {
    val status: EngineStatus
    val statusDetail: String
    val isReady: Boolean

    fun start(context: Context, onReady: () -> Unit)
    fun close()
    fun processKey(keycode: Int, mask: Int): EngineSnapshot
    fun select(engineIndex: Int): EngineSnapshot
    fun snapshot(): EngineSnapshot
    fun clear()
    fun setOption(name: String, value: Boolean)
    fun getOption(name: String): Boolean
}
