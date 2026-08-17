# Coach Android — Milestone 3 (WebRTC + headset routing)

This is the Android-first source milestone for the voice personal-trainer app.

## What changed in M3

- OpenAI Realtime transport is now **native WebRTC** rather than manual PCM-over-WebSocket.
- The app fetches a short-lived Realtime client secret from the included backend; a normal OpenAI API key is never embedded in the APK.
- Realtime audio is a WebRTC media track.
- Realtime events/function calls travel over the `oai-events` WebRTC data channel.
- Tool calls still go through `RealtimeToolRouter` → deterministic `WorkoutSession` / `WorkoutEngine`.
- Live conversations are routed through Android's communication-audio path and prefer BLE Bluetooth, Bluetooth HFP/SCO, USB, or wired headsets.
- Wake-word listening remains local and is stopped before WebRTC takes the microphone.
- The foreground Workout Mode continues to own the wake word, rest timer, and live conversation lifecycle when the screen is locked.
- The old WebSocket client is retained only as a debugging/reference implementation; Workout Mode uses `RealtimeWebRtcTrainerClient`.

## Interaction flow

1. Open Coach and tap **START WORKOUT** while the app is visible.
2. Workout foreground service starts.
3. Local wake-word engine listens for **Coach** (custom Porcupine model required; built-in developer fallback is Porcupine).
4. Say the wake word.
5. Wake-word listener releases the microphone and the app plays a chime.
6. App fetches a short-lived Realtime client secret from `REALTIME_TOKEN_URL`.
7. Native WebRTC creates an audio track + `oai-events` data channel and exchanges SDP with `https://api.openai.com/v1/realtime/calls`.
8. Android switches live conversation audio to the best available communication device (Bluetooth/wired/handset).
9. Model tool calls update the deterministic workout engine.
10. After the short follow-up window closes, WebRTC disconnects and the local wake-word listener resumes.

## Local configuration

Add to `~/.gradle/gradle.properties`:

```properties
PICOVOICE_ACCESS_KEY=your_picovoice_key
REALTIME_TOKEN_URL=https://YOUR_BACKEND/token
OPENAI_REALTIME_MODEL=gpt-realtime-2.1
```

For the desired **Coach** wake phrase, create an Android Porcupine custom keyword model and place it here:

`app/src/main/assets/coach_android.ppn`

Without that file the developer build listens for Picovoice's built-in **Porcupine** keyword.

## Backend

```bash
cd server
npm install
OPENAI_API_KEY=... npm start
```

The `/token` route creates a short-lived Realtime client secret with the trainer's instructions and tool schemas.

## Build APK with GitHub Actions

The repository contains `.github/workflows/android-debug.yml`.

Repository secrets:

- `PICOVOICE_ACCESS_KEY`
- `REALTIME_TOKEN_URL`

Then run **Build Android Debug APK** from Actions. The workflow uploads `coach-debug-apk` containing `app-debug.apk`.

## Important headset limitation

A normal Android app cannot behave exactly like the privileged system voice assistant. In this design the wake-word engine is local and may use the handset microphone while idle. Once Coach is activated, the live WebRTC conversation explicitly switches to Android's communication-device route and prefers the connected headset microphone/output.

This avoids holding Bluetooth earbuds in low-fidelity call mode for the entire workout. A future milestone should add a MediaSession/headset-button activation option for gyms where a phone-in-pocket wake word is unreliable.

## Safety boundary

The LLM never directly mutates workout state. It can request only the exposed tool actions. The deterministic engine remains authoritative, and pain reports pause training rather than encouraging the user to continue through pain.
