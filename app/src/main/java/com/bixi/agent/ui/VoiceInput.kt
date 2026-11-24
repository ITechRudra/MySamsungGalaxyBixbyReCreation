package com.bixi.agent.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.bixi.agent.core.Bixi

/** Wraps Android's speech recogniser (on-device when the offline language pack is installed). */
class VoiceInput(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onReady()
        fun onPartial(text: String)
        fun onResults(hypotheses: List<String>)
        fun onLevel(level: Float)
        fun onError(message: String, retryable: Boolean)
    }

    private var recognizer: SpeechRecognizer? = null
    private var offline = true
    private var retriedOnline = false

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        retriedOnline = false
        offline = Bixi.prefs.preferOffline
        begin()
    }

    private fun begin() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(callbacks)
            recognizer = it
        }
        r.cancel()
        val lang = Bixi.prefs.speechLanguage
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
        }
        r.startListening(intent)
    }

    fun stop() = recognizer?.stopListening()

    fun cancel() = recognizer?.cancel()

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = listener.onReady()
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = listener.onLevel(0f)
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let(listener::onPartial)
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (list.isEmpty()) listener.onError("I didn't catch that.", true) else listener.onResults(list)
        }

        override fun onError(error: Int) {
            listener.onLevel(0f)
            val needsNetworkOrPack = error in setOf(
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            )
            if (needsNetworkOrPack && offline && !retriedOnline) {
                // The offline speech pack isn't downloaded yet: let the recogniser use its normal mode.
                retriedOnline = true
                offline = false
                begin()
                return
            }
            when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    recognizer?.cancel()
                    listener.onError("The microphone is busy. Tap to try again.", true)
                }
                SpeechRecognizer.ERROR_NO_MATCH -> listener.onError("I didn't catch that. Tap to try again.", true)
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listener.onError("I didn't hear anything. Tap to try again.", true)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> listener.onError("Bixi needs microphone permission.", false)
                SpeechRecognizer.ERROR_AUDIO -> listener.onError("Microphone error. Tap to try again.", true)
                SpeechRecognizer.ERROR_CLIENT -> Unit
                else -> listener.onError(
                    "Speech recognition isn't available. Download the offline speech pack (see Settings tab), or type instead.",
                    true,
                )
            }
        }
    }
}
