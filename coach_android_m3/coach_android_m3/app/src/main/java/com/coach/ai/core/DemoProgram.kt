package com.coach.ai.core

object DemoProgram {
    fun upperBodyA() = WorkoutPlan(
        id = "upper-a",
        name = "Upper Body A",
        exercises = listOf(
            ExercisePlan("db-bench", "Dumbbell Bench Press", 3, 8, 10, 20.0, 90),
            ExercisePlan("cable-row", "Seated Cable Row", 3, 8, 10, 45.0, 90),
            ExercisePlan("lat-pulldown", "Lat Pulldown", 3, 8, 12, 40.0, 90),
            ExercisePlan("lateral-raise", "Dumbbell Lateral Raise", 3, 10, 15, 7.5, 60)
        )
    )
}
