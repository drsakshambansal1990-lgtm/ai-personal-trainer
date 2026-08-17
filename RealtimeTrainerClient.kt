package com.coach.ai.android

/**
 * Production seam for OpenAI Realtime.
 *
 * The app must NOT contain a normal OpenAI API key. The eventual implementation
 * will create a WebRTC session through the companion backend and translate model
 * tool calls into TrainerController commands. The deterministic workout engine
 * remains authoritative for workout state.
 */
interface RealtimeTrainerClient {
    val connected: Boolean
    fun connect()
    fun disconnect()
    fun sendWorkoutEvent(name: String, payloadJson: String = "{}")
}

class DisabledRealtimeTrainerClient : RealtimeTrainerClient {
    override val connected = false
    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun sendWorkoutEvent(name: String, payloadJson: String) = Unit
}
