package com.fskeyboard.ime.bridge

/**
 * Filled by rime_jni.cpp. The constructor signature is part of the JNI contract:
 * (ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[II)V
 */
internal class RimeResponse(
    val consumed: Boolean,
    val commit: String,
    val preedit: String,
    val preview: String,
    val candidates: Array<String>,
    val comments: Array<String>,
    val indices: IntArray,
    val highlighted: Int,
)
