plugins {
    id("com.android.application") version "9.5.1"
}

val encoderBench = providers.gradleProperty("encoderBench").isPresent

android {
    namespace = "com.drivediary.sttprobe"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "com.drivediary.sttprobe"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        manifestPlaceholders["batchExported"] = "false"
    }
    buildTypes { getByName("debug") { manifestPlaceholders["batchExported"] = "true" } }
    if (encoderBench) sourceSets["main"].java.directories.add("tools/android-encoder")
}

dependencies {
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    if (encoderBench) implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
}
