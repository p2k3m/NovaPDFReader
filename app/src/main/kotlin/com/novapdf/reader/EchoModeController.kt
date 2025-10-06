package com.novapdf.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Handles TextToSpeech playback for Echo Mode summaries and coordinates fallbacks when
 * synthesis is unavailable on the current device.
 */
class EchoModeController(context: Context) : TextToSpeech.OnInitListener {
    private val applicationContext = context.applicationContext
    @Volatile private var textToSpeech: TextToSpeech? = null
    private val pendingRequests = ArrayDeque<EchoRequest>()
    private val supervisor = SupervisorJob()
    private val initScope = CoroutineScope(supervisor + Dispatchers.IO)
    private val mainScope = CoroutineScope(supervisor + Dispatchers.Main.immediate)

    private var isInitialized = false
    private var readyForPlayback = false
    private var initializationJob: Job? = null

    override fun onInit(status: Int) {
        mainScope.launch {
            val engine = textToSpeech
            if (engine == null) {
                isInitialized = true
                readyForPlayback = false
                drainPendingWithFallback()
                return@launch
            }
            isInitialized = true
            readyForPlayback = status == TextToSpeech.SUCCESS
            if (!readyForPlayback) {
                drainPendingWithFallback()
                return@launch
            }

            val localeResult = engine.setLanguage(Locale.getDefault())
            if (localeResult == TextToSpeech.LANG_MISSING_DATA || localeResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                readyForPlayback = false
                drainPendingWithFallback()
                return@launch
            }

            flushPending()
        }
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

        val engine = textToSpeech
        when {
            readyForPlayback && engine != null -> {
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
            !isInitialized -> {
                pendingRequests.clear()
                pendingRequests.addLast(EchoRequest(trimmedSummary, onFallback))
                ensureInitialised()
            }
            !readyForPlayback -> {
                onFallback()
            }
            else -> {
                pendingRequests.clear()
                pendingRequests.addLast(EchoRequest(trimmedSummary, onFallback))
                ensureInitialised()
            }
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
        textToSpeech = null
        supervisor.cancel()
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

    private fun ensureInitialised() {
        if (isInitialized || initializationJob?.isActive == true) {
            return
        }
        initializationJob = initScope.launch {
            val initialised = runCatching { TextToSpeech(applicationContext, this@EchoModeController) }
            val engine = initialised.getOrNull()
            textToSpeech = engine
            initializationJob = null
            if (engine == null) {
                Log.w(TAG, "TextToSpeech engine unavailable; falling back to haptic feedback", initialised.exceptionOrNull())
                mainScope.launch {
                    isInitialized = true
                    readyForPlayback = false
                    drainPendingWithFallback()
                }
            }
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
