package com.novapdf.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

/**
 * Handles TextToSpeech playback for Echo Mode summaries and coordinates fallbacks when
 * synthesis is unavailable on the current device.
 */
class EchoModeController(context: Context) : TextToSpeech.OnInitListener {
    private val textToSpeech: TextToSpeech = TextToSpeech(context.applicationContext, this)
    private val pendingRequests = ArrayDeque<EchoRequest>()

    private var isInitialized = false
    private var readyForPlayback = false

    override fun onInit(status: Int) {
        isInitialized = true
        readyForPlayback = status == TextToSpeech.SUCCESS
        if (!readyForPlayback) {
            drainPendingWithFallback()
            return
        }

        val localeResult = textToSpeech.setLanguage(Locale.getDefault())
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

        val result = textToSpeech.speak(
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
        textToSpeech.stop()
        textToSpeech.shutdown()
    }

    private fun flushPending() {
        while (pendingRequests.isNotEmpty()) {
            val request = pendingRequests.removeFirst()
            val result = textToSpeech.speak(
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
}
