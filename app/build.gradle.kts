import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.dongbantest.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.dongbantest.app"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Mapbox 공개 토큰(pk.)은 Git 에 올리지 않는 local.properties 의 MAPBOX_ACCESS_TOKEN 에서 읽는다.
        // Mapbox SDK 는 mapbox_access_token 문자열 리소스를 자동으로 찾는다.
        val localProps = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        resValue("string", "mapbox_access_token", localProps.getProperty("MAPBOX_ACCESS_TOKEN", ""))
    }

    // AAOS CarUxRestrictions API (android.car). Optional at runtime so the app
    // still runs on non-automotive devices in Mock mode.
    useLibrary("android.car", false)

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // 최종 APK에서도 대화 판정 모델을 압축하지 않는다.
    androidResources {
        noCompress += "onnx"
    }
    buildFeatures {
        compose = true
        resValues = true
    }
}

// 대화 기록 연동: Room 스키마를 app/schemas 에 남긴다(v5부터).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // 대화 기록 모듈을 연결한다.
    implementation(project(":talkrec"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    // Local persistence
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Pleos Vehicle SDK (read-only usage: CarInfo, Odometer, Gear)
    implementation(libs.pleos.vehicle)

    // Pleos NaviHelper SDK (읽기만 함: 현재 위치, 목적지, 도착 이벤트)
    implementation(libs.pleos.navihelper)

    // Pleos FusedLocation SDK (차량 위치. 못 쓰면 Android GPS 로 대체)
    implementation(libs.pleos.fusedlocation)

    // 지도: 다이어리 경로(S14)와 기록 지도(R01)
    // Pleos 차량에는 Google Play 서비스가 없을 수 있어 Cronet(Play 서비스)을 빼고,
    // Mapbox 가 기본 HTTP 클라이언트를 쓰게 한다 (공식 문서: removing-google-play-dependency).
    implementation(libs.mapbox.maps) {
        exclude(group = "com.google.android.gms", module = "play-services-cronet")
    }

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
