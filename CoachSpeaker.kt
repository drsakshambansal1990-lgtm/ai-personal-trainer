package com.coach.ai.android

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class CoachSpeaker(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.UK
            tts.setSpeechRate(1.02f)
            tts.setPitch(1.0f)
        }
    }

    fun say(text: String) {
        if (!ready) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "coach-${System.nanoTime()}")
    }

    fun shutdown() = tts.shutdown()
}
