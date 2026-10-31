package com.fskeyboard.ime.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * The system speech recognizer. Offline is preferred once. A missing language
 * pack tries again with the system default. Call from the main thread.
 */
internal class SystemSpeech(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var language = "zh-TW"
    private var preferOffline = true
    private var retried = false
    private var listening = false
    private var finishRequested = false
    private var delivered = false
    private var lastPartial = ""
    private var onPartial: (String) -> Unit = {}
    private var onFailure: () -> Unit = {}
    private var pendingFinal: ((String) -> Unit)? = null

    fun start(language: String, onPartial: (String) -> Unit, onFailure: () -> Unit) {
        this.language = language
        this.onPartial = onPartial
        this.onFailure = onFailure
        preferOffline = true
        retried = false
        finishRequested = false
        delivered = false
        lastPartial = ""
        pendingFinal = null
        listening = true
        listen()
    }

    /** Stops the session and returns the final line, or the last partial if that is all that arrived. */
    fun finish(onText: (String) -> Unit) {
        if (!listening) {
            onText(lastPartial)
            return
        }
        finishRequested = true
        pendingFinal = onText
        val active = recognizer
        if (active == null) {
            deliver(lastPartial)
            return
        }
        active.stopListening()
    }

    /** Drops whatever was heard and leaves the recognizer idle. */
    fun cancel() {
        delivered = true
        finishRequested = false
        listening = false
        pendingFinal = null
        lastPartial = ""
        recognizer?.cancel()
    }

    fun destroy() {
        delivered = true
        listening = false
        pendingFinal = null
        val active = recognizer
        recognizer = null
        active?.destroy()
    }

    private fun listen() {
        val active = recognizer ?: create().also { recognizer = it }
        if (active == null) {
            fail()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        }
        try {
            active.startListening(intent)
        } catch (_: Exception) {
            fail()
        }
    }

    private fun create(): SpeechRecognizer? {
        if (!available(context)) return null
        return try {
            SpeechRecognizer.createSpeechRecognizer(context).also { created ->
                created.setRecognitionListener(listener)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fail() {
        listening = false
        if (delivered) return
        delivered = true
        onFailure()
    }

    private fun deliver(text: String) {
        if (delivered) return
        delivered = true
        listening = false
        val callback = pendingFinal
        pendingFinal = null
        callback?.invoke(text)
    }

    private fun heard(bundle: Bundle?): String {
        return bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
    }

    private fun retryable(error: Int): Boolean {
        return error == SpeechRecognizer.ERROR_NETWORK ||
            error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
            error == SpeechRecognizer.ERROR_SERVER ||
            error == ERROR_SERVER_DISCONNECTED ||
            error == ERROR_LANGUAGE_NOT_SUPPORTED ||
            error == ERROR_LANGUAGE_UNAVAILABLE
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = heard(partialResults)
            if (text.isEmpty()) return
            lastPartial = text
            onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            val text = heard(results).ifEmpty { lastPartial }
            if (text.isNotEmpty()) lastPartial = text
            deliver(lastPartial)
        }

        override fun onError(error: Int) {
            if (finishRequested) {
                deliver(lastPartial)
                return
            }
            if (preferOffline && !retried && retryable(error)) {
                retried = true
                preferOffline = false
                listen()
                return
            }
            fail()
        }
    }

    companion object {
        /** These three codes arrived in API 31. The fields are absent on older devices. */
        private const val ERROR_SERVER_DISCONNECTED = 11
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13

        fun available(context: Context): Boolean {
            return try {
                SpeechRecognizer.isRecognitionAvailable(context)
            } catch (_: Exception) {
                false
            }
        }
    }
}
