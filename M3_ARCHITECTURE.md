# M3 architecture

```text
User taps START WORKOUT (visible Activity)
        |
        v
WorkoutForegroundService  [microphone + mediaPlayback FGS]
        |
        +---- local Porcupine wake word (no cloud audio)
        |              |
        |          "Coach" detected
        |              v
        |       release wake-word mic
        |              |
        |              v
        +---- RealtimeWebRtcTrainerClient
               |       |
               |       +---- Android AudioRouteManager
               |              prefers BLE/HFP/wired headset
               |
               +---- GET backend /token
               |       normal OpenAI key stays server-side
               |
               +---- WebRTC audio track ---> OpenAI Realtime
               |
               +---- oai-events DataChannel
                          |
                          +--> function_call
                          |      |
                          |      v
                          |  RealtimeToolRouter
                          |      |
                          |      v
                          |  WorkoutSession / deterministic engine
                          |
                          +<-- function_call_output
```

The Realtime model provides conversation and intent interpretation. The workout engine owns factual state and all state mutations.
