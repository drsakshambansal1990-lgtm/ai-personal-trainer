plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val picovoiceAccessKey = providers.gradleProperty("PICOVOICE_ACCESS_KEY").orElse("").get()
val realtimeTokenUrl = providers.gradleProperty("REALTIME_TOKEN_URL").orElse("").get()
val realtimeModel = providers.gradleProperty("OPENAI_REALTIME_MODEL").orElse("gpt-realtime-2.1").get()
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.coach.ai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.coach.ai"
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"

        buildConfigField("String", "PICOVOICE_ACCESS_KEY", quoted(picovoiceAccessKey))
        buildConfigField("String", "REALTIME_TOKEN_URL", quoted(realtimeTokenUrl))
        buildConfigField("String", "OPENAI_REALTIME_MODEL", quoted(realtimeModel))
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("ai.picovoice:porcupine-android:4.0.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:144.7559.12")
}
