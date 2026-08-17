package com.coach.ai.core

class WorkoutEngine(private val plan: WorkoutPlan) {
    private var state = WorkoutState.NOT_STARTED
    private var exerciseIndex = 0
    private val sets = mutableListOf<LoggedSet>()
    private var pendingReps: Int? = null
    private var restRemaining = 0
    private var pausedFrom: WorkoutState? = null

    fun snapshot(): WorkoutSnapshot {
        val exercise = plan.exercises.getOrNull(exerciseIndex)
        val completed = exercise?.let { ex -> sets.count { it.exerciseId == ex.id } } ?: 0
        return WorkoutSnapshot(
            state = state,
            planName = plan.name,
            exerciseIndex = exerciseIndex,
            currentExercise = exercise,
            currentSetNumber = completed + 1,
            completedSetsForExercise = completed,
            restRemainingSeconds = restRemaining,
            loggedSets = sets.toList(),
            isPaused = state == WorkoutState.PAUSED
        )
    }

    fun startWorkout(): EngineReply {
        if (state != WorkoutState.NOT_STARTED) return reply("Workout is already active.", "NOOP")
        state = WorkoutState.EXERCISE_READY
        val ex = currentExercise()
        return reply(
            "${plan.name}. First exercise: ${ex.name}. ${ex.targetSets} sets of ${ex.minReps} to ${ex.maxReps} reps at ${kg(ex.prescribedWeightKg)} kilos. Say ready when you are set.",
            "WORKOUT_STARTED"
        )
    }

    fun startSet(): EngineReply {
        requireActive()
        if (state == WorkoutState.RESTING) return reply("You're still resting. Say skip rest if you're ready early.", "NOOP")
        if (state == WorkoutState.ACTIVE_SET) return reply("Set is already active.", "NOOP")
        if (state == WorkoutState.AWAITING_RPE) return reply("Tell me the effort first, for example RPE eight.", "NOOP")
        state = WorkoutState.ACTIVE_SET
        val s = snapshot()
        val ex = currentExercise()
        return reply("Set ${s.currentSetNumber}. ${kg(ex.prescribedWeightKg)} kilos. Aim for ${ex.minReps} to ${ex.maxReps}. Go.", "SET_STARTED")
    }

    fun logReps(reps: Int): EngineReply {
        requireActive()
        if (state != WorkoutState.ACTIVE_SET && state != WorkoutState.EXERCISE_READY) {
            return reply("I wasn't expecting reps right now. Say ready before the set.", "NOOP")
        }
        if (reps !in 1..100) return reply("That rep count doesn't look right. Say the number again.", "INVALID_REPS")
        pendingReps = reps
        state = WorkoutState.AWAITING_RPE
        return reply("$reps reps. How hard was that? Say RPE one to ten.", "REPS_CAPTURED")
    }

    fun logRpe(rpe: Double): EngineReply {
        requireActive()
        val reps = pendingReps ?: return reply("Tell me the reps first.", "NOOP")
        if (rpe < 1.0 || rpe > 10.0) return reply("RPE should be between one and ten.", "INVALID_RPE")
        val ex = currentExercise()
        val setNumber = sets.count { it.exerciseId == ex.id } + 1
        sets += LoggedSet(ex.id, setNumber, ex.prescribedWeightKg, reps, rpe)
        pendingReps = null

        val completed = sets.count { it.exerciseId == ex.id }
        if (completed >= ex.targetSets) {
            return finishExercise(ex)
        }

        restRemaining = ex.restSeconds
        state = WorkoutState.RESTING
        return reply(
            "Logged: ${kg(ex.prescribedWeightKg)} kilos for $reps at RPE ${rpe.clean()}. Rest ${ex.restSeconds} seconds.",
            "SET_LOGGED"
        )
    }

    fun tickRest(seconds: Int = 1): EngineReply? {
        if (state != WorkoutState.RESTING) return null
        restRemaining = (restRemaining - seconds).coerceAtLeast(0)
        if (restRemaining == 0) {
            state = WorkoutState.EXERCISE_READY
            val s = snapshot()
            return reply("Rest over. Set ${s.currentSetNumber}. Same weight. Get ready.", "REST_COMPLETE")
        }
        return null
    }

    fun skipRest(): EngineReply {
        requireActive()
        if (state != WorkoutState.RESTING) return reply("There isn't an active rest timer.", "NOOP")
        restRemaining = 0
        state = WorkoutState.EXERCISE_READY
        return reply("Rest skipped. Ready for the next set.", "REST_SKIPPED")
    }

    fun pause(): EngineReply {
        if (state == WorkoutState.NOT_STARTED || state == WorkoutState.WORKOUT_COMPLETE) return reply("No active workout to pause.", "NOOP")
        if (state == WorkoutState.PAUSED) return reply("Workout is already paused.", "NOOP")
        pausedFrom = state
        state = WorkoutState.PAUSED
        return reply("Workout paused.", "WORKOUT_PAUSED")
    }

    fun resume(): EngineReply {
        if (state != WorkoutState.PAUSED) return reply("Workout isn't paused.", "NOOP")
        state = pausedFrom ?: WorkoutState.EXERCISE_READY
        pausedFrom = null
        return reply("Workout resumed.", "WORKOUT_RESUMED")
    }

    fun endWorkout(): EngineReply {
        if (state == WorkoutState.WORKOUT_COMPLETE) return reply("Workout is already complete.", "NOOP")
        state = WorkoutState.WORKOUT_COMPLETE
        restRemaining = 0
        val totalReps = sets.sumOf { it.reps }
        val volume = sets.sumOf { it.reps * it.weightKg }
        return reply(
            "Workout complete. ${sets.size} working sets, $totalReps reps, and ${volume.toInt()} kilo-reps of volume.",
            "WORKOUT_ENDED"
        )
    }

    fun whatsNext(): EngineReply {
        val ex = currentExerciseOrNull() ?: return reply("Workout complete.", "WORKOUT_COMPLETE")
        val s = snapshot()
        return reply(
            "${ex.name}, set ${s.currentSetNumber} of ${ex.targetSets}. ${kg(ex.prescribedWeightKg)} kilos, target ${ex.minReps} to ${ex.maxReps} reps.",
            "STATUS"
        )
    }

    fun reportPain(area: String): EngineReply {
        if (state == WorkoutState.WORKOUT_COMPLETE || state == WorkoutState.NOT_STARTED) {
            return reply("Pain noted. There isn't an active exercise right now.", "PAIN_REPORTED")
        }
        state = WorkoutState.PAUSED
        return reply(
            "Stop the current exercise. I've paused the workout because you reported $area pain. Don't push through it; we can choose a non-provoking alternative or end the exercise.",
            "PAIN_REPORTED"
        )
    }

    fun restoreSets(restored: List<LoggedSet>) {
        if (state != WorkoutState.NOT_STARTED) return
        sets.clear()
        sets.addAll(restored)
    }

    private fun finishExercise(ex: ExercisePlan): EngineReply {
        val indexBefore = exerciseIndex
        if (indexBefore >= plan.exercises.lastIndex) {
            state = WorkoutState.WORKOUT_COMPLETE
            return reply("${ex.name} complete. That's the workout finished.", "WORKOUT_COMPLETE")
        }
        exerciseIndex += 1
        state = WorkoutState.EXERCISE_READY
        val next = currentExercise()
        return reply(
            "${ex.name} complete. Next: ${next.name}. ${next.targetSets} sets of ${next.minReps} to ${next.maxReps} at ${kg(next.prescribedWeightKg)} kilos.",
            "EXERCISE_COMPLETE"
        )
    }

    private fun currentExercise(): ExercisePlan = plan.exercises[exerciseIndex]
    private fun currentExerciseOrNull(): ExercisePlan? = plan.exercises.getOrNull(exerciseIndex)

    private fun requireActive() {
        if (state == WorkoutState.PAUSED) throw IllegalStateException("Workout is paused")
        if (state == WorkoutState.NOT_STARTED) throw IllegalStateException("Workout has not started")
        if (state == WorkoutState.WORKOUT_COMPLETE) throw IllegalStateException("Workout is complete")
    }

    private fun reply(text: String, event: String) = EngineReply(text, snapshot(), event)
    private fun kg(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    private fun Double.clean(): String = if (this % 1.0 == 0.0) toInt().toString() else toString()
}
