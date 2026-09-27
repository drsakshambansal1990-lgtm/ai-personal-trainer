package com.coach.ai.android

import com.coach.ai.BuildConfig
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.coach.ai.core.WorkoutState

class WorkoutForegroundService : Service() {
    companion object {
        const val CHANNEL_ID = "active_workout"
        const val NOTIFICATION_ID = 4101
        const val ACTION_STOP = "com.coach.ai.STOP_WORKOUT_SERVICE"
        const val ACTION_ACTIVATE = "com.coach.ai.ACTIVATE_COACH"
        const val ACTION_STATE_CHANGED = "com.coach.ai.WORKOUT_STATE_CHANGED"
        const val EXTRA_SERVICE_STATUS = "service_status"
        const val EXTRA_AUDIO_ROUTE = "audio_route"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var speaker: CoachSpeaker
    private var wakeWord: WakeWordDetector? = null
    private var fallbackRecognizer: OneShotCommandRecognizer? = null
    private var realtime: RealtimeTrainerClient? = null
    private var mediaSession: MediaSession? = null
    private var conversationActive = false
    private var restTickerStarted = false
    private var lastTickerElapsedRealtime = 0L
    private var lastActivationElapsedRealtime = 0L
    private var turnSerial = 0
    private var returnToWakeTurnId = 0
    private var turnTimeoutRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        speaker = CoachSpeaker(this)
        createNotificationChannel()
        setupMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdownWorkoutMode()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification("Workout Mode starting…")
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, types)
        else startForeground(NOTIFICATION_ID, notification)

        if (!restTickerStarted) startRestTicker()
        if (intent?.action == ACTION_ACTIVATE) {
            activateCoach("Talk button")
        } else {
            startWakeWordIfPossible()
            if (BuildConfig.PICOVOICE_ACCESS_KEY.isBlank()) {
                updateNotification("Tap Talk or press the earbud play/pause button")
            }
        }
        return START_NOT_STICKY
    }

    private fun startWakeWordIfPossible() {
        if (conversationActive || wakeWord?.active == true) return
        if (BuildConfig.PICOVOICE_ACCESS_KEY.isBlank()) {
            updateNotification("Manual mode · tap Talk or use the earbud button")
            return
        }
        try {
            if (wakeWord == null) wakeWord = PorcupineWakeWordDetector(this)
            wakeWord?.start { handler.post { activateCoach("wake word") } }
            val label = if (assets.list("")?.contains("coach_android.ppn") == true) "Coach" else "Porcupine (developer fallback)"
            updateNotification("Listening for $label · earbud button also active")
        } catch (t: Throwable) {
            updateNotification("Wake word unavailable · ${t.message ?: "setup required"}")
        }
    }

    private fun activateCoach(source: String) {
        val now = SystemClock.elapsedRealtime()

        // Some headsets deliver the same physical press through both the media-button
        // callback and onPlay/onPause. Treat near-simultaneous activations as one turn.
        if (conversationActive && now - lastActivationElapsedRealtime < 700L) return

        // A deliberate second Talk press should recover a stuck turn immediately.
        if (conversationActive) cancelActiveTurn()

        lastActivationElapsedRealtime = now
        val turnId = ++turnSerial
        conversationActive = true
        wakeWord?.stop()
        speaker.stop()
        handler.removeCallbacks(returnToWakeRunnable)
        clearTurnTimeout()

        ToneGenerator(AudioManager.STREAM_MUSIC, 60).apply {
            startTone(ToneGenerator.TONE_PROP_BEEP, 110)
            handler.postDelayed({ release() }, 180)
        }
        publishStatus("Coach activated by $source · listening")

        val useRealtime = BuildConfig.REALTIME_TOKEN_URL.isNotBlank()
        armTurnTimeout(turnId, if (useRealtime) 25_000L else 12_000L)
        handler.postDelayed({
            if (!isCurrentTurn(turnId)) return@postDelayed
            if (useRealtime) startRealtimeTurn(turnId) else startFallbackTurn(turnId)
        }, 180)
    }

    private fun setupMediaSession() {
        val playbackState = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE
            )
            .setState(PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
            .build()

        mediaSession = MediaSession(this, "CoachWorkout").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = mediaButtonIntent.keyEventCompat() ?: return false
                    if (event.action != KeyEvent.ACTION_DOWN) return true
                    return when (event.keyCode) {
                        KeyEvent.KEYCODE_HEADSETHOOK,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            handler.post { activateCoach("earbud button") }
                            true
                        }
                        else -> super.onMediaButtonEvent(mediaButtonIntent)
                    }
                }

                override fun onPlay() {
                    handler.post { activateCoach("earbud button") }
                }

                override fun onPause() {
                    handler.post { activateCoach("earbud button") }
                }
            }, handler)
            setPlaybackState(playbackState)
            isActive = true
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.keyEventCompat(): KeyEvent? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        else getParcelableExtra(Intent.EXTRA_KEY_EVENT)

    private fun startRealtimeTurn(turnId: Int) {
        realtime?.disconnect()
        realtime = RealtimeWebRtcTrainerClient(
            this,
            RealtimeToolRouter(),
            onStatus = { status -> handler.post {
                if (isCurrentTurn(turnId)) publishStatus(status)
            } },
            onRoute = { route -> handler.post {
                if (isCurrentTurn(turnId)) broadcastState(route = route)
            } },
            onStateChanged = { handler.post {
                if (isCurrentTurn(turnId)) broadcastState()
            } },
            onFinalTurn = { handler.post {
                if (isCurrentTurn(turnId)) scheduleReturnToWakeWord(turnId)
            } },
        ).also { it.connect() }
    }

    private fun startFallbackTurn(turnId: Int) {
        fallbackRecognizer?.stop()
        fallbackRecognizer = OneShotCommandRecognizer(
            this,
            onResult = { raw -> handler.post {
                if (!isCurrentTurn(turnId)) return@post
                fallbackRecognizer = null
                publishStatus("Heard: $raw")
                val reply = runCatching { WorkoutSession.command(raw) }.getOrElse {
                    publishStatus("That command can't be used right now")
                    speaker.say("That command can't be used right now.") {
                        handler.post { finishTurn(turnId) }
                    }
                    return@post
                }
                broadcastState()
                clearTurnTimeout()
                speaker.say(reply.spokenText) {
                    handler.post { finishTurn(turnId) }
                }
            } },
            onError = { message -> handler.post {
                if (!isCurrentTurn(turnId)) return@post
                fallbackRecognizer = null
                publishStatus(message)
                clearTurnTimeout()
                speaker.say("I didn't catch that.") {
                    handler.post { finishTurn(turnId) }
                }
            } },
            onStatus = { status -> handler.post {
                if (isCurrentTurn(turnId)) publishStatus(status)
            } },
        ).also { it.start() }
    }

    private fun scheduleReturnToWakeWord(turnId: Int, delayMs: Long = if (WorkoutSession.snapshot().state == WorkoutState.AWAITING_RPE) 12_000 else 5_000) {
        clearTurnTimeout()
        handler.removeCallbacks(returnToWakeRunnable)
        returnToWakeTurnId = turnId
        handler.postDelayed(returnToWakeRunnable, delayMs)
    }

    private val returnToWakeRunnable = Runnable {
        val turnId = returnToWakeTurnId
        if (isCurrentTurn(turnId)) finishTurn(turnId)
    }

    private fun finishTurn(turnId: Int) {
        if (!isCurrentTurn(turnId)) return
        clearTurnTimeout()
        handler.removeCallbacks(returnToWakeRunnable)
        realtime?.disconnect(); realtime = null
        fallbackRecognizer?.stop(); fallbackRecognizer = null
        conversationActive = false
        startWakeWordIfPossible()
    }

    private fun cancelActiveTurn() {
        clearTurnTimeout()
        handler.removeCallbacks(returnToWakeRunnable)
        realtime?.disconnect(); realtime = null
        fallbackRecognizer?.stop(); fallbackRecognizer = null
        conversationActive = false
    }

    private fun armTurnTimeout(turnId: Int, delayMs: Long) {
        clearTurnTimeout()
        val runnable = Runnable {
            if (!isCurrentTurn(turnId)) return@Runnable
            fallbackRecognizer?.stop(); fallbackRecognizer = null
            realtime?.disconnect(); realtime = null
            conversationActive = false
            publishStatus("Talk timed out · tap Talk to retry")
            startWakeWordIfPossible()
        }
        turnTimeoutRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun clearTurnTimeout() {
        turnTimeoutRunnable?.let { handler.removeCallbacks(it) }
        turnTimeoutRunnable = null
    }

    private fun isCurrentTurn(turnId: Int) = conversationActive && turnId == turnSerial

    private fun publishStatus(message: String) {
        updateNotification(message)
        broadcastState(status = message)
    }

    private fun startRestTicker() {
        restTickerStarted = true
        lastTickerElapsedRealtime = SystemClock.elapsedRealtime()
        handler.post(object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                val elapsedSeconds = ((now - lastTickerElapsedRealtime) / 1000L).toInt()

                if (elapsedSeconds > 0) {
                    lastTickerElapsedRealtime += elapsedSeconds * 1000L
                    val before = WorkoutSession.snapshot()
                    val reply = WorkoutSession.tickRest(elapsedSeconds)
                    val after = WorkoutSession.snapshot()

                    if (before.state == WorkoutState.RESTING || after.state == WorkoutState.RESTING || reply != null) {
                        broadcastState()
                    }

                    if (reply != null) {
                        // Never speak a timer cue into a microphone that is actively
                        // listening to the athlete; it can be mistaken for user speech.
                        if (!conversationActive) speaker.say(reply.spokenText)
                        realtime?.takeIf { it.connected }?.sendWorkoutEvent(
                            reply.event,
                            RealtimeToolRouter().snapshotJson()
                        )
                    }
                }

                handler.postDelayed(this, 250)
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
        clearTurnTimeout()
        handler.removeCallbacks(returnToWakeRunnable)
        realtime?.disconnect(); realtime = null
        fallbackRecognizer?.stop(); fallbackRecognizer = null
        wakeWord?.release(); wakeWord = null
        mediaSession?.isActive = false
        mediaSession?.release(); mediaSession = null
        conversationActive = false
        speaker.stop()
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
        val talk = PendingIntent.getService(
            this, 3, Intent(this, WorkoutForegroundService::class.java).setAction(ACTION_ACTIVATE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("Coach · Workout Mode")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, "Talk", talk).build())
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
