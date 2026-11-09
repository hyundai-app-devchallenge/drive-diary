plugins {
    id("com.android.application") version "9.5.1"
}

android {
    namespace = "com.drivediary.sttprobe"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "com.drivediary.sttprobe"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }
}

dependencies {
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
}
