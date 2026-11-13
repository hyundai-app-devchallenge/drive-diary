// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // 라이브러리 플러그인도 같은 AGP 버전으로 해석한다.
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
