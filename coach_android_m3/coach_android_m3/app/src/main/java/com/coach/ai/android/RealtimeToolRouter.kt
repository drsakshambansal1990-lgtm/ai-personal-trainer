package com.coach.ai.android

import com.coach.ai.core.*
import org.json.JSONArray
import org.json.JSONObject

class RealtimeToolRouter {
    fun execute(name: String, argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        val reply = when (name) {
            "get_workout_state" -> EngineReply("Current workout state.", WorkoutSession.snapshot(), "STATE_READ")
            "start_set" -> WorkoutSession.command(TrainerCommand.Ready)
            "log_reps" -> WorkoutSession.command(TrainerCommand.Reps(args.getInt("reps")))
            "log_rpe" -> WorkoutSession.command(TrainerCommand.Rpe(args.getDouble("rpe")))
            "skip_rest" -> WorkoutSession.command(TrainerCommand.SkipRest)
            "whats_next" -> WorkoutSession.command(TrainerCommand.WhatsNext)
            "pause_workout" -> WorkoutSession.command(TrainerCommand.Pause)
            "resume_workout" -> WorkoutSession.command(TrainerCommand.Resume)
            "end_workout" -> WorkoutSession.command(TrainerCommand.EndWorkout)
            "report_pain" -> WorkoutSession.command(TrainerCommand.Pain(args.optString("area", "unspecified area")))
            else -> return JSONObject().put("ok", false).put("error", "Unknown tool: $name").toString()
        }
        return replyJson(reply)
    }

    fun snapshotJson(): String = snapshotJson(WorkoutSession.snapshot()).toString()

    private fun replyJson(reply: EngineReply) = JSONObject()
        .put("ok", reply.event != "INVALID_STATE")
        .put("event", reply.event)
        .put("engine_message", reply.spokenText)
        .put("state", snapshotJson(reply.snapshot))
        .toString()

    private fun snapshotJson(s: WorkoutSnapshot): JSONObject {
        val ex = s.currentExercise
        return JSONObject()
            .put("plan", s.planName)
            .put("state", s.state.name)
            .put("exercise", ex?.name ?: JSONObject.NULL)
            .put("set_number", s.currentSetNumber)
            .put("target_sets", ex?.targetSets ?: JSONObject.NULL)
            .put("weight_kg", ex?.prescribedWeightKg ?: JSONObject.NULL)
            .put("rep_range", ex?.let { "${it.minReps}-${it.maxReps}" } ?: JSONObject.NULL)
            .put("rest_remaining_seconds", s.restRemainingSeconds)
            .put("logged_sets", JSONArray().apply {
                s.loggedSets.forEach { set ->
                    put(JSONObject()
                        .put("exercise_id", set.exerciseId)
                        .put("weight_kg", set.weightKg)
                        .put("reps", set.reps)
                        .put("rpe", set.rpe ?: JSONObject.NULL))
                }
            })
    }
}
