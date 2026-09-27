# Coach M4 alpha02 — Device test checklist

## Install / permissions
- [ ] Install debug APK.
- [ ] Grant microphone permission.
- [ ] Grant notifications permission where requested.
- [ ] Grant nearby/Bluetooth connection permission where requested.
- [ ] Confirm persistent `Coach · Workout Mode` notification after START WORKOUT.
- [ ] Confirm notification shows both `Talk` and `Stop` actions.

## Wake word
- [ ] Screen on: say custom `Coach`; confirm chime.
- [ ] Screen locked: say `Coach`; confirm chime.
- [ ] Confirm unrelated speech does not open a Realtime session.
- [ ] Confirm local wake-word listener resumes after a live turn ends.

## Earbud / media-button activation
- [ ] Connect Bluetooth earbuds before START WORKOUT.
- [ ] With screen on, press play/pause once; confirm Coach chime and listening state.
- [ ] With screen locked, press play/pause once; confirm Coach activates.
- [ ] Confirm the notification `Talk` action activates Coach without opening the app.
- [ ] Confirm a second press while Coach is already listening does not start a duplicate session.
- [ ] Check whether play/pause is taken away from Spotify/YouTube Music while Workout Mode is active.
- [ ] Stop Workout Mode and confirm normal media-button behavior returns.

## Bluetooth / audio route
- [ ] Activate Coach and confirm reply is heard in earbuds.
- [ ] Confirm trainer hears speech through the intended communication route.
- [ ] Disconnect earbuds mid-session; confirm graceful fallback to handset.
- [ ] Reconnect earbuds; confirm next live turn routes back to them.

## Realtime workout tools
- [ ] `Coach, ready.` → starts set.
- [ ] `Coach, ten reps.` → logs reps.
- [ ] Follow-up `Eight.` when awaiting RPE → logs RPE 8.
- [ ] `What's next?` → does not mutate state.
- [ ] `Skip rest.` → ends rest locally.
- [ ] `Pause workout.` / `Resume workout.` work.
- [ ] `My shoulder hurts.` → workout pauses / pain pathway.
- [ ] `End workout.` → summary and Workout Mode shutdown.

## Background robustness
- [ ] Lock screen for 5+ minutes during rest/wake mode.
- [ ] Switch to another app and return.
- [ ] Receive a phone call; verify Coach releases/reacquires audio cleanly.
- [ ] Toggle Bluetooth during the workout.
- [ ] Toggle Wi-Fi/mobile data during a live turn; verify recovery.

## Noisy-gym test
- [ ] Wake-word false-positive rate acceptable.
- [ ] Earbud-button activation remains reliable in loud background audio.
- [ ] Speech recognition usable with music/background voices.
- [ ] Trainer response latency acceptable.
- [ ] Barge-in stops/shortens Coach response appropriately.
