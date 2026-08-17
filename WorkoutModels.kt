package com.coach.ai.core

enum class WorkoutState {
    NOT_STARTED,
    EXERCISE_READY,
    ACTIVE_SET,
    AWAITING_RPE,
    RESTING,
    PAUSED,
    WORKOUT_COMPLETE
}

data class ExercisePlan(
    val id: String,
    val name: String,
    val targetSets: Int,
    val minReps: Int,
    val maxReps: Int,
    val prescribedWeightKg: Double,
    val restSeconds: Int
)

data class LoggedSet(
    val exerciseId: String,
    val setNumber: Int,
    val weightKg: Double,
    val reps: Int,
    val rpe: Double? = null,
    val completedAtEpochMs: Long = System.currentTimeMillis()
)

data class WorkoutPlan(
    val id: String,
    val name: String,
    val exercises: List<ExercisePlan>
)

data class WorkoutSnapshot(
    val state: WorkoutState,
    val planName: String,
    val exerciseIndex: Int,
    val currentExercise: ExercisePlan?,
    val currentSetNumber: Int,
    val completedSetsForExercise: Int,
    val restRemainingSeconds: Int,
    val loggedSets: List<LoggedSet>,
    val isPaused: Boolean
)

data class EngineReply(
    val spokenText: String,
    val snapshot: WorkoutSnapshot,
    val event: String
)
