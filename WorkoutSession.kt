package com.coach.ai.android

import com.coach.ai.core.*

/**
 * Process-local source of truth for an active workout.
 *
 * The foreground service and Activity both talk to this object so the workout
 * survives screen-off/background transitions without creating a second engine.
 */
object WorkoutSession {
    private var controller: TrainerController = TrainerController(WorkoutEngine(DemoProgram.upperBodyA()))

    @Synchronized
    fun reset() {
        controller = TrainerController(WorkoutEngine(DemoProgram.upperBodyA()))
    }

    @Synchronized
    fun start(): EngineReply = controller.startWorkout()

    @Synchronized
    fun command(raw: String): EngineReply = controller.handle(TrainerCommandParser.parse(raw))

    @Synchronized
    fun command(command: TrainerCommand): EngineReply = controller.handle(command)

    @Synchronized
    fun tickRest(seconds: Int = 1): EngineReply? = controller.tickRest(seconds)

    @Synchronized
    fun snapshot(): WorkoutSnapshot = controller.snapshot()
}
