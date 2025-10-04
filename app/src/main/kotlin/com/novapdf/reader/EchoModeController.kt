package com.novapdf.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

/**
 * Handles TextToSpeech playback for Echo Mode summaries and coordinates fallbacks when
 * synthesis is unavailable on the current device.
 */
class EchoModeController(context: Context) : TextToSpeech.OnInitListener {
    private val applicationContext = context.applicationContext
    private val textToSpeech: TextToSpeech?
    private val pendingRequests = ArrayDeque<EchoRequest>()

    private var isInitialized = false
    private var readyForPlayback = false

    init {
        val initialised = runCatching { TextToSpeech(applicationContext, this) }
        textToSpeech = initialised.getOrNull()
        if (textToSpeech == null) {
            // Some devices (particularly headless CI images) do not ship with a TTS engine. The
            // platform constructor throws synchronously in that scenario, so mark the controller
            // as initialised but unavailable so that callers can gracefully fall back to haptics
            // instead of crashing the process during composition.
            isInitialized = true
            readyForPlayback = false
            Log.w(TAG, "TextToSpeech engine unavailable; falling back to haptic feedback", initialised.exceptionOrNull())
            drainPendingWithFallback()
        }
    }

    override fun onInit(status: Int) {
        val engine = textToSpeech
        if (engine == null) {
            isInitialized = true
            readyForPlayback = false
            drainPendingWithFallback()
            return
        }
        isInitialized = true
        readyForPlayback = status == TextToSpeech.SUCCESS
        if (!readyForPlayback) {
            drainPendingWithFallback()
            return
        }

        val localeResult = engine.setLanguage(Locale.getDefault())
        if (localeResult == TextToSpeech.LANG_MISSING_DATA || localeResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            readyForPlayback = false
            drainPendingWithFallback()
            return
        }

        flushPending()
    }

    /**
     * Speaks [summary] if the TextToSpeech engine is available, otherwise invokes [onFallback].
     */
    fun speakSummary(summary: String, onFallback: () -> Unit) {
        val trimmedSummary = summary.trim()
        if (trimmedSummary.isEmpty()) {
            onFallback()
            return
        }

        if (!readyForPlayback) {
            if (isInitialized) {
                onFallback()
            } else {
                pendingRequests.clear()
                pendingRequests.addLast(EchoRequest(trimmedSummary, onFallback))
            }
            return
        }

        val engine = textToSpeech
        if (engine == null) {
            onFallback()
            return
        }

        val result = engine.speak(
            trimmedSummary,
            TextToSpeech.QUEUE_FLUSH,
            /* params = */ null,
            /* utteranceId = */ UUID.randomUUID().toString()
        )
        if (result != TextToSpeech.SUCCESS) {
            onFallback()
        }
    }

    /**
     * Releases the underlying TextToSpeech resources.
     */
    fun shutdown() {
        pendingRequests.clear()
        textToSpeech?.let { engine ->
            engine.stop()
            engine.shutdown()
        }
    }

    private fun flushPending() {
        while (pendingRequests.isNotEmpty()) {
            val request = pendingRequests.removeFirst()
            val engine = textToSpeech
            if (engine == null) {
                request.onFallback()
                continue
            }
            val result = engine.speak(
                request.summary,
                TextToSpeech.QUEUE_FLUSH,
                /* params = */ null,
                /* utteranceId = */ request.utteranceId
            )
            if (result != TextToSpeech.SUCCESS) {
                request.onFallback()
            }
        }
    }

    private fun drainPendingWithFallback() {
        while (pendingRequests.isNotEmpty()) {
            pendingRequests.removeFirst().onFallback()
        }
    }

    private data class EchoRequest(
        val summary: String,
        val onFallback: () -> Unit,
        val utteranceId: String = UUID.randomUUID().toString()
    )

    private companion object {
        private const val TAG = "EchoModeController"
    }
}
