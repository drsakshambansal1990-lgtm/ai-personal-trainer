package com.coach.ai.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import com.coach.ai.core.*

class MainActivity : Activity() {
    private lateinit var speaker: CoachSpeaker
    private lateinit var voice: VoiceRecognizer
    private lateinit var store: WorkoutStore

    private lateinit var statusText: TextView
    private lateinit var exerciseText: TextView
    private lateinit var setText: TextView
    private lateinit var targetText: TextView
    private lateinit var restText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var commandInput: EditText
    private lateinit var startButton: Button
    private lateinit var actionButton: Button
    private lateinit var listenButton: Button

    private val workoutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            render(WorkoutSession.snapshot())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speaker = CoachSpeaker(this)
        voice = VoiceRecognizer(this)
        store = WorkoutStore(this)
        setContentView(buildUi())
        render(WorkoutSession.snapshot())
        requestRuntimePermissions()
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = ScrollView(this).apply { setBackgroundColor(Color.rgb(246, 247, 249)) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(40))
        }
        root.addView(col)

        fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
            textSize = size
            setTextColor(Color.rgb(25, 29, 36))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        col.addView(text(13f, true).apply {
            this.text = "COACH · ANDROID MILESTONE 3"
            setTextColor(Color.rgb(78, 84, 96))
        })
        col.addView(text(32f, true).apply {
            this.text = "Live Workout"
            setPadding(0, dp(4), 0, dp(8))
        })
        col.addView(text(14f).apply {
            this.text = "Start once, put the phone away, then say Coach. Wake-word detection stays on-device. Once activated, live audio switches to WebRTC and prefers a connected Bluetooth or wired headset."
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, dp(18))
        })

        startButton = Button(this).apply {
            text = "▶ START WORKOUT"
            textSize = 18f
            setOnClickListener { startWorkout() }
        }
        col.addView(startButton, LinearLayout.LayoutParams.MATCH_PARENT, dp(58))

        statusText = text(14f, true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(16), 0, dp(6))
        }
        col.addView(statusText)

        exerciseText = text(27f, true).apply { gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(exerciseText)

        setText = text(18f, true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        col.addView(setText)

        targetText = text(16f).apply { gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(targetText)

        restText = text(40f, true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
        }
        col.addView(restText)

        actionButton = Button(this).apply {
            text = "READY / START SET"
            setOnClickListener { handleCommand("ready") }
            isEnabled = false
        }
        col.addView(actionButton, LinearLayout.LayoutParams.MATCH_PARENT, dp(52))

        val micRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        listenButton = Button(this).apply {
            text = "🎙 SPEAK"
            isEnabled = false
            setOnClickListener { voice.start() }
        }
        micRow.addView(listenButton, LinearLayout.LayoutParams(0, dp(52), 1f))

        val whatsNext = Button(this).apply {
            text = "WHAT'S NEXT?"
            isEnabled = false
            setOnClickListener { handleCommand("what's next") }
        }
        micRow.addView(whatsNext, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        col.addView(micRow)

        commandInput = EditText(this).apply {
            hint = "Type: 10 reps · RPE 8 · skip rest · pause"
            setSingleLine(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        col.addView(commandInput, LinearLayout.LayoutParams.MATCH_PARENT, dp(56))

        val send = Button(this).apply {
            text = "SEND COMMAND"
            isEnabled = false
            setOnClickListener {
                val value = commandInput.text.toString().trim()
                if (value.isNotEmpty()) {
                    handleCommand(value)
                    commandInput.setText("")
                }
            }
        }
        col.addView(send, LinearLayout.LayoutParams.MATCH_PARENT, dp(50))

        transcriptText = text(15f).apply {
            setPadding(0, dp(18), 0, dp(18))
            this.text = "Coach: Tap START WORKOUT to begin.\n\nLast saved: ${store.lastWorkoutSummary()}"
        }
        col.addView(transcriptText)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pause = Button(this).apply {
            text = "PAUSE"
            setOnClickListener { handleCommand("pause") }
        }
        val resume = Button(this).apply {
            text = "RESUME"
            setOnClickListener { handleCommand("resume") }
        }
        val end = Button(this).apply {
            text = "END"
            setOnClickListener { handleCommand("end workout") }
        }
        controls.addView(pause, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(resume, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(end, LinearLayout.LayoutParams(0, dp(48), 1f))
        col.addView(controls)

        send.tag = "send"
        whatsNext.tag = "whatsNext"
        pause.tag = "sessionControl"
        resume.tag = "sessionControl"
        end.tag = "sessionControl"
        setControlsEnabled(col, false)
        startButton.isEnabled = true
        return root
    }

    private fun setControlsEnabled(view: View, enabled: Boolean) {
        if (view is Button && view !== startButton) view.isEnabled = enabled
        if (view is LinearLayout) for (i in 0 until view.childCount) setControlsEnabled(view.getChildAt(i), enabled)
    }

    private fun startWorkout() {
        if (!hasAudioPermission()) {
            requestRuntimePermissions()
            Toast.makeText(this, "Microphone permission is needed for Workout Mode.", Toast.LENGTH_LONG).show()
            return
        }
        val serviceIntent = Intent(this, WorkoutForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
        WorkoutSession.reset()
        val reply = WorkoutSession.start()
        startButton.isEnabled = false
        setControlsEnabled((startButton.parent as LinearLayout), true)
        startButton.isEnabled = false
        appendCoach(reply.spokenText)
        speaker.say(reply.spokenText)
        render(reply.snapshot)
    }

    private fun handleCommand(raw: String) {
        appendUser(raw)
        val cmd = TrainerCommandParser.parse(raw)
        val reply = WorkoutSession.command(cmd)
        appendCoach(reply.spokenText)
        speaker.say(reply.spokenText)
        render(reply.snapshot)

        if (reply.event == "WORKOUT_COMPLETE" || reply.event == "WORKOUT_ENDED") {
            store.saveCompletedWorkout(reply.snapshot.planName, reply.snapshot.loggedSets)
            stopService(Intent(this, WorkoutForegroundService::class.java))
            actionButton.isEnabled = false
            listenButton.isEnabled = false
        }
    }

    private fun render(s: WorkoutSnapshot) {
        statusText.text = when (s.state) {
            WorkoutState.NOT_STARTED -> "READY"
            WorkoutState.EXERCISE_READY -> "READY FOR SET"
            WorkoutState.ACTIVE_SET -> "SET ACTIVE"
            WorkoutState.AWAITING_RPE -> "TELL ME YOUR RPE"
            WorkoutState.RESTING -> "RESTING"
            WorkoutState.PAUSED -> "PAUSED"
            WorkoutState.WORKOUT_COMPLETE -> "WORKOUT COMPLETE"
        }
        val ex = s.currentExercise
        exerciseText.text = ex?.name ?: "Workout complete"
        setText.text = ex?.let { "SET ${s.currentSetNumber.coerceAtMost(it.targetSets)} / ${it.targetSets}" } ?: ""
        targetText.text = ex?.let { "${weight(it.prescribedWeightKg)} kg · ${it.minReps}–${it.maxReps} reps" } ?: "${s.loggedSets.size} working sets"
        restText.text = if (s.state == WorkoutState.RESTING) formatSeconds(s.restRemainingSeconds) else ""
        actionButton.text = when (s.state) {
            WorkoutState.RESTING -> "SKIP REST"
            WorkoutState.AWAITING_RPE -> "SAY / TYPE RPE"
            else -> "READY / START SET"
        }
        actionButton.setOnClickListener {
            if (WorkoutSession.snapshot().state == WorkoutState.RESTING) handleCommand("skip rest") else handleCommand("ready")
        }
    }

    private fun appendUser(text: String) {
        transcriptText.append("\n\nYou: $text")
    }

    private fun appendCoach(text: String) {
        transcriptText.append("\nCoach: $text")
    }

    @Deprecated("Legacy result path is sufficient for this milestone")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VoiceRecognizer.REQUEST_CODE && resultCode == RESULT_OK) {
            voice.resultText(data)?.let { handleCommand(it) }
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31) permissions += Manifest.permission.BLUETOOTH_CONNECT
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 7001)
    }

    private fun hasAudioPermission() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun formatSeconds(v: Int) = "%02d:%02d".format(v / 60, v % 60)
    private fun weight(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(WorkoutForegroundService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(workoutReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(workoutReceiver, filter)
        render(WorkoutSession.snapshot())
    }

    override fun onStop() {
        runCatching { unregisterReceiver(workoutReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }
}
