package com.coach.ai.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

class PcmMicStreamer(
    private val context: Context,
    private val onPcm: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object { const val SAMPLE_RATE = 24_000 }
    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running.getAndSet(true)) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            running.set(false); onError("Microphone permission is missing"); return
        }
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(min, 4_800)
        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
            running.set(false); recorder?.release(); recorder = null; onError("Could not initialize microphone"); return
        }
        recorder?.startRecording()
        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buffer = ByteArray(2_400) // ~50 ms of 24 kHz mono PCM16
            while (running.get()) {
                val read = recorder?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) onPcm(buffer.copyOf(read))
            }
        }.apply { name = "coach-mic"; start() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { recorder?.stop() }
        thread?.join(300)
        thread = null
        recorder?.release()
        recorder = null
    }
}

class PcmAudioPlayer(private val context: Context) {
    companion object { const val SAMPLE_RATE = 24_000 }
    private var track: AudioTrack? = null
    private var previousMode: Int? = null

    fun start() {
        if (track != null) return
        val audioManager = context.getSystemService(AudioManager::class.java)
        previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        track = AudioTrack(attrs, format, maxOf(min, 9_600), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE).also { it.play() }
    }

    fun write(bytes: ByteArray) {
        if (track == null) start()
        track?.write(bytes, 0, bytes.size, AudioTrack.WRITE_BLOCKING)
    }

    fun interrupt() {
        val t = track ?: return
        runCatching { t.pause(); t.flush(); t.play() }
    }

    fun stop() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
        val audioManager = context.getSystemService(AudioManager::class.java)
        previousMode?.let { audioManager.mode = it }
        previousMode = null
    }
}
