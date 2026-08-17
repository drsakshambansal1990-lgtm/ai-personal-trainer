package com.coach.ai.android

import com.coach.ai.BuildConfig
import android.content.Context
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import java.io.IOException

interface WakeWordDetector {
    val active: Boolean
    fun start(onWake: () -> Unit)
    fun stop()
    fun release()
}

/**
 * On-device wake-word detector. Audio does not leave the phone while this is active.
 *
 * For the desired wake phrase "Coach", place coach_android.ppn in src/main/assets
 * and set PICOVOICE_ACCESS_KEY in ~/.gradle/gradle.properties.
 * If the custom model is absent, the developer build falls back to Picovoice's
 * built-in "Porcupine" keyword so the background flow can still be tested.
 */
class PorcupineWakeWordDetector(private val context: Context) : WakeWordDetector {
    private var manager: PorcupineManager? = null
    override var active: Boolean = false
        private set

    override fun start(onWake: () -> Unit) {
        if (active) return
        val accessKey = BuildConfig.PICOVOICE_ACCESS_KEY
        require(accessKey.isNotBlank()) {
            "PICOVOICE_ACCESS_KEY is missing. Add it to ~/.gradle/gradle.properties."
        }

        val callback = object : PorcupineManagerCallback {
            override fun invoke(keywordIndex: Int) = onWake()
        }
        manager = if (assetExists("coach_android.ppn")) {
            PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywordPaths(arrayOf("coach_android.ppn"))
                .build(context, callback)
        } else {
            PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywords(arrayOf(Porcupine.BuiltInKeyword.PORCUPINE))
                .build(context, callback)
        }
        manager?.start()
        active = true
    }

    override fun stop() {
        if (!active) return
        runCatching { manager?.stop() }
        active = false
    }

    override fun release() {
        stop()
        runCatching { manager?.delete() }
        manager = null
    }

    private fun assetExists(name: String): Boolean = try {
        context.assets.open(name).use { true }
    } catch (_: IOException) {
        false
    }
}
