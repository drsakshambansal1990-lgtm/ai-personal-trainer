# Build status — Android M3

## Completed in source
- Android foreground Workout Mode
- Local Porcupine wake-word gate
- Deterministic workout engine and tool router
- Native WebRTC Realtime client
- WebRTC microphone media track
- WebRTC `oai-events` data channel
- Short-lived Realtime credential flow
- Android communication-audio routing for Bluetooth/wired/handset
- Local rest timer and background session ownership
- GitHub Actions debug APK workflow

## Verified locally in this environment
- Existing pure Kotlin workout-engine smoke test
- Source structure / configuration consistency checks

## Not executable in this container
This environment does not include an Android SDK/emulator, so the full AAR-based Android project cannot be assembled or device-tested here.

The next verification step is a CI/device build, with special attention to:
1. BLE/HFP earbud route selection on Android 13–17.
2. WebRTC SDP/data-channel interoperability with the current Realtime endpoint.
3. Wake word → microphone hand-off without `AudioRecord` contention.
4. Screen-off behavior under Android 17 background-audio hardening.
5. Barge-in and return-to-wake-word timing in a noisy gym.
