package com.coach.ai.android

import com.coach.ai.BuildConfig
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import com.coach.ai.core.WorkoutState

class WorkoutForegroundService : Service() {
    companion object {
        const val CHANNEL_ID = "active_workout"
        const val NOTIFICATION_ID = 4101
        const val ACTION_STOP = "com.coach.ai.STOP_WORKOUT_SERVICE"
        const val ACTION_STATE_CHANGED = "com.coach.ai.WORKOUT_STATE_CHANGED"
        const val EXTRA_SERVICE_STATUS = "service_status"
        const val EXTRA_AUDIO_ROUTE = "audio_route"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var speaker: CoachSpeaker
    private var wakeWord: WakeWordDetector? = null
    private var fallbackRecognizer: OneShotCommandRecognizer? = null
    private var realtime: RealtimeTrainerClient? = null
    private var conversationActive = false
    private var restTickerStarted = false

    override fun onCreate() {
        super.onCreate()
        speaker = CoachSpeaker(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdownWorkoutMode()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification("Wake word active · say Coach")
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, types)
        else startForeground(NOTIFICATION_ID, notification)

        if (!restTickerStarted) startRestTicker()
        startWakeWordIfPossible()
        return START_NOT_STICKY
    }

    private fun startWakeWordIfPossible() {
        if (conversationActive || wakeWord?.active == true) return
        if (BuildConfig.PICOVOICE_ACCESS_KEY.isBlank()) {
            updateNotification("Wake word needs Picovoice key · tap app to use manual mic")
            return
        }
        try {
            if (wakeWord == null) wakeWord = PorcupineWakeWordDetector(this)
            wakeWord?.start { handler.post { onWakeWord() } }
            val label = if (assets.list("")?.contains("coach_android.ppn") == true) "Coach" else "Porcupine (developer fallback)"
            updateNotification("Listening locally for $label")
        } catch (t: Throwable) {
            updateNotification("Wake word unavailable · ${t.message ?: "setup required"}")
        }
    }

    private fun onWakeWord() {
        if (conversationActive) return
        conversationActive = true
        wakeWord?.stop()
        ToneGenerator(AudioManager.STREAM_MUSIC, 60).apply {
            startTone(ToneGenerator.TONE_PROP_BEEP, 110)
            handler.postDelayed({ release() }, 180)
        }
        updateNotification("Coach activated · listening")
        handler.postDelayed({
            if (BuildConfig.REALTIME_TOKEN_URL.isNotBlank()) startRealtimeTurn() else startFallbackTurn()
        }, 180)
    }

    private fun startRealtimeTurn() {
        realtime?.disconnect()
        realtime = RealtimeWebRtcTrainerClient(
            this,
            RealtimeToolRouter(),
            onStatus = { status -> handler.post { updateNotification(status); broadcastState(status = status) } },
            onRoute = { route -> handler.post { broadcastState(route = route) } },
            onStateChanged = { handler.post { broadcastState() } },
            onFinalTurn = { handler.post { scheduleReturnToWakeWord() } },
        ).also { it.connect() }
    }

    private fun startFallbackTurn() {
        fallbackRecognizer?.stop()
        fallbackRecognizer = OneShotCommandRecognizer(
            this,
            onResult = { raw -> handler.post {
                val reply = WorkoutSession.command(raw)
                speaker.say(reply.spokenText)
                broadcastState()
                scheduleReturnToWakeWord(2200)
            } },
            onError = { message -> handler.post {
                speaker.say("I didn't catch that. Say Coach and try again.")
                updateNotification(message)
                scheduleReturnToWakeWord(1400)
            } },
        ).also { it.start() }
    }

    private fun scheduleReturnToWakeWord(delayMs: Long = if (WorkoutSession.snapshot().state == WorkoutState.AWAITING_RPE) 12_000 else 5_000) {
        // Keep Realtime open briefly for a natural follow-up (especially RPE), then go quiet again.
        handler.removeCallbacks(returnToWakeRunnable)
        handler.postDelayed(returnToWakeRunnable, delayMs)
    }

    private val returnToWakeRunnable = Runnable {
        realtime?.disconnect(); realtime = null
        fallbackRecognizer?.stop(); fallbackRecognizer = null
        conversationActive = false
        startWakeWordIfPossible()
    }

    private fun startRestTicker() {
        restTickerStarted = true
        handler.post(object : Runnable {
            override fun run() {
                val reply = WorkoutSession.tickRest(1)
                if (reply != null) {
                    speaker.say(reply.spokenText)
                    broadcastState()
                }
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun broadcastState(status: String? = null, route: String? = null) {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName).apply {
            status?.let { putExtra(EXTRA_SERVICE_STATUS, it) }
            route?.let { putExtra(EXTRA_AUDIO_ROUTE, it) }
        })
    }

    private fun shutdownWorkoutMode() {
        handler.removeCallbacks(returnToWakeRunnable)
        realtime?.disconnect(); realtime = null
        fallbackRecognizer?.stop(); fallbackRecognizer = null
        wakeWord?.release(); wakeWord = null
        speaker.shutdown()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Active workout", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps Coach Workout Mode and hands-free audio active"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, WorkoutForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("Coach · Workout Mode")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onDestroy() {
        shutdownWorkoutMode()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
