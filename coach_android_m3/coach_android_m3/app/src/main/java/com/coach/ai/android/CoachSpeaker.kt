package com.coach.ai.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class CoachSpeaker(context: Context) : TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                complete(utteranceId)
            }

            @Deprecated("Deprecated in Android API")
            override fun onError(utteranceId: String?) {
                complete(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                complete(utteranceId)
            }
        })
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.UK
            tts.setSpeechRate(1.02f)
            tts.setPitch(1.0f)
        }
    }

    fun say(text: String, onDone: (() -> Unit)? = null) {
        if (!ready) {
            onDone?.let { main.post(it) }
            return
        }
        val utteranceId = "coach-${System.nanoTime()}"
        if (onDone != null) callbacks[utteranceId] = onDone
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) complete(utteranceId)
    }

    fun stop() {
        callbacks.clear()
        runCatching { tts.stop() }
    }

    fun shutdown() {
        callbacks.clear()
        runCatching { tts.stop() }
        tts.shutdown()
    }

    private fun complete(utteranceId: String?) {
        if (utteranceId == null) return
        callbacks.remove(utteranceId)?.let { callback -> main.post(callback) }
    }
}
