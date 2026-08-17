package com.coach.ai.core

sealed interface TrainerCommand {
    data object Ready : TrainerCommand
    data class Reps(val value: Int) : TrainerCommand
    data class Rpe(val value: Double) : TrainerCommand
    data object SkipRest : TrainerCommand
    data object WhatsNext : TrainerCommand
    data object Pause : TrainerCommand
    data object Resume : TrainerCommand
    data object EndWorkout : TrainerCommand
    data class Pain(val area: String) : TrainerCommand
    data class Unknown(val raw: String) : TrainerCommand
}

object TrainerCommandParser {
    private val numberWords = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
        "nineteen" to 19, "twenty" to 20
    )

    fun parse(raw: String): TrainerCommand {
        val text = raw.lowercase().trim().removePrefix("coach,").removePrefix("coach ").trim()
        if (text in setOf("ready", "start", "start set", "go", "i'm ready", "im ready")) return TrainerCommand.Ready
        if ("skip rest" in text || "end rest" in text) return TrainerCommand.SkipRest
        if ("what's next" in text || "whats next" in text || "what next" in text || "status" == text) return TrainerCommand.WhatsNext
        if (text == "pause" || "pause workout" in text) return TrainerCommand.Pause
        if (text == "resume" || "resume workout" in text) return TrainerCommand.Resume
        if (text == "end" || "end workout" in text || "finish workout" in text) return TrainerCommand.EndWorkout

        if ("pain" in text || "hurts" in text || "hurt" in text) {
            val area = listOf("shoulder", "knee", "back", "wrist", "elbow", "hip", "ankle", "neck")
                .firstOrNull { it in text } ?: "pain"
            return TrainerCommand.Pain(area)
        }

        val rpeMatch = Regex("(?:rpe|effort)\\s*(?:is\\s*)?([0-9]+(?:\\.[0-9])?)").find(text)
        if (rpeMatch != null) return TrainerCommand.Rpe(rpeMatch.groupValues[1].toDouble())
        numberWords.entries.firstOrNull { (word, _) -> ("rpe $word" in text) || ("effort $word" in text) }
            ?.let { return TrainerCommand.Rpe(it.value.toDouble()) }

        val repMatch = Regex("([0-9]{1,2})\\s*(?:reps?|repetitions?)").find(text)
        if (repMatch != null) return TrainerCommand.Reps(repMatch.groupValues[1].toInt())
        numberWords.entries.firstOrNull { (word, _) -> ("$word reps" in text) || text == word }
            ?.let { return TrainerCommand.Reps(it.value) }

        return TrainerCommand.Unknown(raw)
    }
}
