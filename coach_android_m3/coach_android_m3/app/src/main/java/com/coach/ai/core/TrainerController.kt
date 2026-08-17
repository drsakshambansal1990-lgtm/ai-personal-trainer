package com.coach.ai.core

class TrainerController(private val engine: WorkoutEngine) {
    fun startWorkout(): EngineReply = engine.startWorkout()

    fun handle(command: TrainerCommand): EngineReply = try {
        when (command) {
            TrainerCommand.Ready -> engine.startSet()
            is TrainerCommand.Reps -> engine.logReps(command.value)
            is TrainerCommand.Rpe -> engine.logRpe(command.value)
            TrainerCommand.SkipRest -> engine.skipRest()
            TrainerCommand.WhatsNext -> engine.whatsNext()
            TrainerCommand.Pause -> engine.pause()
            TrainerCommand.Resume -> engine.resume()
            TrainerCommand.EndWorkout -> engine.endWorkout()
            is TrainerCommand.Pain -> engine.reportPain(command.area)
            is TrainerCommand.Unknown -> EngineReply(
                "I didn't catch that. Try: ready, ten reps, RPE eight, what's next, pause, or end workout.",
                engine.snapshot(),
                "UNKNOWN_COMMAND"
            )
        }
    } catch (e: IllegalStateException) {
        EngineReply(e.message ?: "That command isn't available right now.", engine.snapshot(), "INVALID_STATE")
    }

    fun tickRest(seconds: Int = 1): EngineReply? = engine.tickRest(seconds)
    fun snapshot(): WorkoutSnapshot = engine.snapshot()
}
