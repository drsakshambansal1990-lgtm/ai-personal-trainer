package com.coach.ai.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.coach.ai.BuildConfig
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native Android OpenAI Realtime transport using WebRTC.
 *
 * Audio travels as a WebRTC media track. Realtime client/server events travel
 * over the `oai-events` data channel. The normal OpenAI API key never enters
 * the APK; a companion backend returns only a short-lived client secret.
 */
class RealtimeWebRtcTrainerClient(
    private val context: Context,
    private val toolRouter: RealtimeToolRouter,
    private val onStatus: (String) -> Unit,
    private val onRoute: (String) -> Unit,
    private val onStateChanged: () -> Unit,
    private val onFinalTurn: () -> Unit,
) : RealtimeTrainerClient {

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val isConnected = AtomicBoolean(false)
    override val connected: Boolean get() = isConnected.get()

    private var peerFactory: PeerConnectionFactory? = null
    private var audioModule: JavaAudioDeviceModule? = null
    private var peer: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localOffer: SessionDescription? = null
    private var sdpPosted = false
    private var ephemeralToken: String? = null

    private val routeManager = AudioRouteManager(context) { onRoute(it) }

    override fun connect() {
        if (connected || peer != null) return
        val tokenUrl = BuildConfig.REALTIME_TOKEN_URL
        if (tokenUrl.isBlank()) {
            onStatus("Realtime backend is not configured")
            onFinalTurn()
            return
        }
        onStatus("Getting secure Realtime credential…")
        val request = Request.Builder().url(tokenUrl).get().build()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = fail("Token error: ${e.message}")
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return fail("Token endpoint returned ${it.code}")
                    val body = it.body?.string().orEmpty()
                    val token = runCatching { JSONObject(body).getString("value") }.getOrNull()
                    if (token.isNullOrBlank()) return fail("Token response did not contain value")
                    ephemeralToken = token
                    main.post { beginPeerConnection(token) }
                }
            }
        })
    }

    private fun beginPeerConnection(token: String) {
        try {
            routeManager.startCommunicationRouting()
            ensureFactory()
            val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            peer = peerFactory?.createPeerConnection(rtcConfig, peerObserver)
                ?: return fail("Unable to create WebRTC peer connection")

            audioSource = peerFactory?.createAudioSource(MediaConstraints())
            localAudioTrack = peerFactory?.createAudioTrack("coach-mic", audioSource)?.apply { setEnabled(true) }
            localAudioTrack?.let { peer?.addTrack(it, listOf("coach-audio")) }

            dataChannel = peer?.createDataChannel("oai-events", DataChannel.Init())?.also {
                it.registerObserver(dataObserver)
            }

            val offerConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            }
            peer?.createOffer(object : SimpleSdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription) {
                    localOffer = desc
                    peer?.setLocalDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            // Give native ICE a short window to add candidates. If ICE completes
                            // earlier, PeerObserver posts immediately.
                            main.postDelayed({ postOfferIfReady(token) }, 900)
                        }
                        override fun onSetFailure(error: String) = fail("Local SDP failed: $error")
                    }, desc)
                }
                override fun onCreateFailure(error: String) = fail("Offer creation failed: $error")
            }, offerConstraints)
        } catch (t: Throwable) {
            fail("WebRTC setup failed: ${t.message}")
        }
    }

    private fun ensureFactory() {
        if (peerFactory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        audioModule = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        peerFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .createPeerConnectionFactory()
    }

    private fun postOfferIfReady(token: String) {
        if (sdpPosted) return
        val sdp = peer?.localDescription?.description ?: localOffer?.description ?: return
        sdpPosted = true
        onStatus("Negotiating live audio…")
        val body = sdp.toRequestBody("application/sdp".toMediaType())
        val request = Request.Builder()
            .url("https://api.openai.com/v1/realtime/calls")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = fail("SDP exchange failed: ${e.message}")
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val answer = it.body?.string().orEmpty()
                    if (!it.isSuccessful || answer.isBlank()) return fail("Realtime call returned ${it.code}")
                    main.post {
                        peer?.setRemoteDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                onStatus("Coach live · ${routeManager.currentRouteLabel()}")
                            }
                            override fun onSetFailure(error: String) = fail("Remote SDP failed: $error")
                        }, SessionDescription(SessionDescription.Type.ANSWER, answer))
                    }
                }
            }
        })
    }

    private val dataObserver = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() {
            if (dataChannel?.state() == DataChannel.State.OPEN) {
                isConnected.set(true)
                onStatus("Coach is listening · ${routeManager.currentRouteLabel()}")
                sendStateContext()
            }
        }
        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary) return
            val data = buffer.data
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            handleServerEvent(String(bytes, StandardCharsets.UTF_8))
        }
    }

    private val peerObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> onStatus("Coach live · ${routeManager.currentRouteLabel()}")
                PeerConnection.IceConnectionState.FAILED -> fail("WebRTC ICE connection failed")
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                ephemeralToken?.let { postOfferIfReady(it) }
            }
        }
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) {
            // We create the canonical oai-events channel, but accept a remote one
            // defensively if the implementation exposes it this way.
            if (dataChannel == null) {
                dataChannel = channel
                channel.registerObserver(dataObserver)
            }
        }
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) {
            (receiver.track() as? AudioTrack)?.setEnabled(true)
        }
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

    private fun handleServerEvent(raw: String) {
        val event = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (event.optString("type")) {
            "input_audio_buffer.speech_started" -> {
                // Request immediate barge-in. WebRTC carries the media; the data channel
                // carries the control event.
                sendJson(JSONObject().put("type", "response.cancel"))
            }
            "response.done" -> handleResponseDone(event)
            "error" -> onStatus("Realtime error: ${event.optJSONObject("error")?.optString("message") ?: "unknown"}")
        }
    }

    private fun handleResponseDone(event: JSONObject) {
        val response = event.optJSONObject("response") ?: return
        val output = response.optJSONArray("output") ?: return onFinalTurn()
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
        if (calledTool) sendJson(JSONObject().put("type", "response.create")) else onFinalTurn()
    }

    private fun sendJson(value: JSONObject): Boolean {
        val bytes = value.toString().toByteArray(StandardCharsets.UTF_8)
        return dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false)) == true
    }

    override fun sendWorkoutEvent(name: String, payloadJson: String) {
        sendJson(JSONObject()
            .put("type", "conversation.item.create")
            .put("item", JSONObject()
                .put("type", "message")
                .put("role", "user")
                .put("content", org.json.JSONArray().put(JSONObject()
                    .put("type", "input_text")
                    .put("text", "Workout event $name: $payloadJson")))))
    }

    override fun disconnect() {
        isConnected.set(false)
        runCatching { dataChannel?.unregisterObserver() }
        runCatching { dataChannel?.close() }
        dataChannel = null
        runCatching { peer?.close() }
        runCatching { peer?.dispose() }
        peer = null
        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        runCatching { peerFactory?.dispose() }
        peerFactory = null
        runCatching { audioModule?.release() }
        audioModule = null
        routeManager.stopCommunicationRouting()
        localOffer = null
        ephemeralToken = null
        sdpPosted = false
    }

    private fun fail(message: String) {
        main.post {
            onStatus(message)
            disconnect()
            onFinalTurn()
        }
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }
}
