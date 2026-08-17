import com.coach.ai.core.*

fun main() {
    val engine = WorkoutEngine(DemoProgram.upperBodyA())
    val controller = TrainerController(engine)

    check(controller.startWorkout().snapshot.state == WorkoutState.EXERCISE_READY)
    check(controller.handle(TrainerCommandParser.parse("Coach, ready")).snapshot.state == WorkoutState.ACTIVE_SET)
    check(controller.handle(TrainerCommandParser.parse("10 reps")).snapshot.state == WorkoutState.AWAITING_RPE)
    val logged = controller.handle(TrainerCommandParser.parse("RPE 8"))
    check(logged.snapshot.loggedSets.size == 1)
    check(logged.snapshot.state == WorkoutState.RESTING)
    check(TrainerCommandParser.parse("my shoulder hurts") == TrainerCommand.Pain("shoulder"))
    check(TrainerCommandParser.parse("what's next") == TrainerCommand.WhatsNext)
    println("Core smoke test passed: ${logged.snapshot.loggedSets.first()}")
}
