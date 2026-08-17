package com.coach.ai.android

import android.content.Context
import com.coach.ai.core.LoggedSet
import org.json.JSONArray
import org.json.JSONObject

class WorkoutStore(context: Context) {
    private val prefs = context.getSharedPreferences("coach_workout_store", Context.MODE_PRIVATE)

    fun saveCompletedWorkout(planName: String, sets: List<LoggedSet>) {
        val root = JSONObject()
        root.put("planName", planName)
        root.put("completedAt", System.currentTimeMillis())
        val array = JSONArray()
        sets.forEach { set ->
            array.put(JSONObject().apply {
                put("exerciseId", set.exerciseId)
                put("setNumber", set.setNumber)
                put("weightKg", set.weightKg)
                put("reps", set.reps)
                if (set.rpe != null) put("rpe", set.rpe)
                put("completedAtEpochMs", set.completedAtEpochMs)
            })
        }
        root.put("sets", array)
        prefs.edit().putString("last_completed_workout", root.toString()).apply()
    }

    fun lastWorkoutSummary(): String {
        val raw = prefs.getString("last_completed_workout", null) ?: return "No completed workout yet"
        return try {
            val root = JSONObject(raw)
            val sets = root.getJSONArray("sets")
            var reps = 0
            var volume = 0.0
            for (i in 0 until sets.length()) {
                val set = sets.getJSONObject(i)
                reps += set.getInt("reps")
                volume += set.getDouble("weightKg") * set.getInt("reps")
            }
            "${root.optString("planName", "Workout")}: ${sets.length()} sets · $reps reps · ${volume.toInt()} kg-reps"
        } catch (_: Exception) {
            "Previous workout saved"
        }
    }
}
