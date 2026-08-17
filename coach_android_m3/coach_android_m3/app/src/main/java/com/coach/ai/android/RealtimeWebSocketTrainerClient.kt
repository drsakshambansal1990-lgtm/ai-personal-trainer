package com.coach.ai.android

import com.coach.ai.BuildConfig
import android.content.Context
import android.util.Base64
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LEGACY development transport retained for comparison/debugging.
 * Production Workout Mode now uses RealtimeWebRtcTrainerClient.
 *
 * OpenAI recommends WebRTC for mobile clients. This milestone deliberately uses
 * a WebSocket transport because it lets us exercise wake-word gating, PCM audio,
 * barge-in, and function-tool routing in plain Kotlin. The Realtime interface is
 * isolated so the transport can be swapped to WebRTC without touching the workout engine.
 */
class RealtimeWebSocketTrainerClient(
    private val context: Context,
    private val toolRouter: RealtimeToolRouter,
    private val onStatus: (String) -> Unit,
    private val onStateChanged: () -> Unit,
    private val onFinalTurn: () -> Unit,
) : RealtimeTrainerClient {
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var socket: WebSocket? = null
    private var mic: PcmMicStreamer? = null
    private val player = PcmAudioPlayer(context)
    private val isConnected = AtomicBoolean(false)
    override val connected: Boolean get() = isConnected.get()

    override fun connect() {
        if (connected) return
        val tokenUrl = BuildConfig.REALTIME_TOKEN_URL
        if (tokenUrl.isBlank()) {
            onStatus("Realtime backend is not configured.")
            onFinalTurn()
            return
        }
        onStatus("Connecting live trainer…")
        val request = Request.Builder().url(tokenUrl).get().build()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onStatus("Realtime token error: ${e.message}")
                onFinalTurn()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        onStatus("Realtime token endpoint returned ${it.code}")
                        onFinalTurn(); return
                    }
                    val body = it.body?.string().orEmpty()
                    val token = runCatching { JSONObject(body).getString("value") }.getOrNull()
                    if (token.isNullOrBlank()) {
                        onStatus("Realtime token response did not contain value")
                        onFinalTurn(); return
                    }
                    openSocket(token)
                }
            }
        })
    }

    private fun openSocket(token: String) {
        val model = BuildConfig.OPENAI_REALTIME_MODEL.ifBlank { "gpt-realtime-2.1" }
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=$model")
            .header("Authorization", "Bearer $token")
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected.set(true)
                onStatus("Coach is listening")
                player.start()
                sendStateContext()
                mic = PcmMicStreamer(context, ::sendPcm) { onStatus(it) }.also { it.start() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerEvent(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected.set(false)
                onStatus("Realtime connection ended: ${t.message}")
                cleanupMedia()
                onFinalTurn()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected.set(false)
                cleanupMedia()
            }
        })
    }

    private fun sendStateContext() {
        val text = "Current deterministic workout state JSON: ${toolRouter.snapshotJson()}. Use tools for every state change."
        sendJson(JSONObject()
            .put("type", "conversation.item.create")
            .put("item", JSONObject()
                .put("type", "message")
                .put("role", "user")
                .put("content", org.json.JSONArray().put(JSONObject().put("type", "input_text").put("text", text)))))
    }

    private fun sendPcm(bytes: ByteArray) {
        val audio = Base64.encodeToString(bytes, Base64.NO_WRAP)
        sendJson(JSONObject().put("type", "input_audio_buffer.append").put("audio", audio))
    }

    private fun handleServerEvent(raw: String) {
        val event = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (event.optString("type")) {
            "input_audio_buffer.speech_started" -> player.interrupt()
            "response.output_audio.delta" -> {
                val delta = event.optString("delta")
                if (delta.isNotBlank()) player.write(Base64.decode(delta, Base64.DEFAULT))
            }
            "response.done" -> handleResponseDone(event)
            "error" -> onStatus("Realtime error: ${event.optJSONObject("error")?.optString("message") ?: raw}")
        }
    }

    private fun handleResponseDone(event: JSONObject) {
        val response = event.optJSONObject("response") ?: return
        val output = response.optJSONArray("output") ?: return
        var calledTool = false
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "function_call") continue
            val callId = item.optString("call_id")
            val name = item.optString("name")
            val args = item.optString("arguments", "{}")
            if (callId.isBlank() || name.isBlank()) continue
            calledTool = true
            val result = runCatching { toolRouter.execute(name, args) }
                .onSuccess { if (name != "get_workout_state") onStateChanged() }
                .getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "tool failed").toString() }
            sendJson(JSONObject()
                .put("type", "conversation.item.create")
                .put("item", JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", callId)
                    .put("output", result)))
        }
        if (calledTool) {
            sendJson(JSONObject().put("type", "response.create"))
        } else {
            onFinalTurn()
        }
    }

    private fun sendJson(value: JSONObject) { socket?.send(value.toString()) }

    override fun disconnect() {
        mic?.stop(); mic = null
        socket?.close(1000, "turn complete")
        socket = null
        isConnected.set(false)
        cleanupMedia()
    }

    private fun cleanupMedia() {
        mic?.stop(); mic = null
        player.stop()
    }

    override fun sendWorkoutEvent(name: String, payloadJson: String) {
        val content = "Workout event $name: $payloadJson"
        sendJson(JSONObject()
            .put("type", "conversation.item.create")
            .put("item", JSONObject()
                .put("type", "message")
                .put("role", "user")
                .put("content", org.json.JSONArray().put(JSONObject().put("type", "input_text").put("text", content)))))
    }
}
