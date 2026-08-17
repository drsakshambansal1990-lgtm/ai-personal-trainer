CUSTOM WAKE WORD SETUP

The desired wake phrase is: Coach

1. Create/download an Android custom keyword named "Coach" from Picovoice Console.
2. Rename the Android keyword file to: coach_android.ppn
3. Place it in this directory: app/src/main/assets/
4. Set PICOVOICE_ACCESS_KEY in ~/.gradle/gradle.properties

If coach_android.ppn is absent, developer builds fall back to the built-in keyword "Porcupine".
The wake-word audio is processed locally on device. Only after detection does the app open a live command/realtime session.
