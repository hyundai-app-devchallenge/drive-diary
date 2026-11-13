plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.drivediary.talkrec"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // ONNX 모델을 압축하지 않아 APK에서 직접 읽을 수 있게 한다.
    androidResources {
        noCompress += "onnx"
    }

    testOptions {
        unitTests.all { test ->
            test.systemProperty("talkrec.assetsDir", file("src/main/assets").absolutePath)
            test.inputs.dir(file("src/main/assets/m1")).withPropertyName("m1Assets")
            // Policy tests read assets directly on the JVM; invalidate cached results when rules change.
            test.inputs.dir(file("src/main/assets/policy")).withPropertyName("policyAssets")
            test.inputs.dir(file("src/main/assets/prompts")).withPropertyName("summaryPrompts")
            // Forward either opt-in to the forked JVM; explicit -D takes priority.
            providers.systemProperty("TALKREC_PRIVATE_DIR")
                .orElse(providers.environmentVariable("TALKREC_PRIVATE_DIR"))
                .orNull?.let { directory ->
                    test.systemProperty("TALKREC_PRIVATE_DIR", directory)
                }
        }
    }
}

// Android's AAR and the desktop jar contain the same ai.onnxruntime classes.
// Replace the AAR on BOTH local unit-test classpaths; Android keeps its AAR.
configurations.configureEach {
    if (name.endsWith("UnitTestCompileClasspath") || name.endsWith("UnitTestRuntimeClasspath")) {
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
    }
}

dependencies {
    implementation(libs.onnxruntime.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.pleos.speech.to.text)
    testImplementation(libs.junit)
    testImplementation("com.microsoft.onnxruntime:onnxruntime:${libs.versions.onnxRuntime.get()}")
    // Android's org.json is a stub on the host; use its JVM implementation in tests.
    testImplementation("org.json:json:20240303")
}
