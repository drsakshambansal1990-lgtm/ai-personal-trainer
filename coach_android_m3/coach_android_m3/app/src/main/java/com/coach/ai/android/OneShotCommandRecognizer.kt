package com.coach.ai.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Fallback command capture used when OpenAI Realtime is not configured. */
class OneShotCommandRecognizer(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onStatus: (String) -> Unit = {},
) {
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0

    fun start() {
        stop()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition is not available on this device.")
            return
        }

        val myGeneration = ++generation
        var finished = false
        var lastPartial: String? = null

        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr

        fun isCurrent() = myGeneration == generation && recognizer === sr && !finished

        fun release() {
            if (recognizer === sr) recognizer = null
            runCatching { sr.destroy() }
        }

        fun finishWithResult(text: String) {
            if (!isCurrent()) return
            finished = true
            onResult(text)
            release()
        }

        fun finishWithError(message: String) {
            if (!isCurrent()) return
            finished = true
            onError(message)
            release()
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (isCurrent()) onStatus("Listening…")
            }

            override fun onBeginningOfSpeech() {
                if (isCurrent()) onStatus("Listening…")
            }

            override fun onEndOfSpeech() {
                if (isCurrent()) onStatus("Processing…")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!isCurrent()) return
                partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { lastPartial = it }
            }

            override fun onResults(results: Bundle?) {
                if (!isCurrent()) return
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: lastPartial
                if (text.isNullOrBlank()) finishWithError("I couldn't understand that. Tap Talk and try again.")
                else finishWithResult(text)
            }

            override fun onError(error: Int) {
                if (!isCurrent()) return
                if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && !lastPartial.isNullOrBlank()) {
                    finishWithResult(lastPartial!!)
                    return
                }
                val message = when (error) {
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Tap Talk and try again."
                    SpeechRecognizer.ERROR_NO_MATCH -> "I couldn't understand that. Tap Talk and try again."
                    SpeechRecognizer.ERROR_AUDIO -> "Microphone/audio error. Tap Talk and try again."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy. Tap Talk again in a moment."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for Talk."
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition network error. Tap Talk and try again."
                    else -> "Speech recognition reset. Tap Talk and try again."
                }
                finishWithError(message)
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
        }
        onStatus("Starting microphone…")
        sr.startListening(intent)
    }

    fun stop() {
        generation++
        val sr = recognizer
        recognizer = null
        runCatching { sr?.cancel() }
        runCatching { sr?.destroy() }
    }
}
