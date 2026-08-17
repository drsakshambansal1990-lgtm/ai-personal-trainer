package com.coach.ai.android

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent

class VoiceRecognizer(private val activity: Activity) {
    companion object { const val REQUEST_CODE = 9001 }

    fun start() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a trainer command")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        activity.startActivityForResult(intent, REQUEST_CODE)
    }

    fun resultText(data: Intent?): String? =
        data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
}
