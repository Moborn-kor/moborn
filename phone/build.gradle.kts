plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "kr.moborn.hud.phone"
    compileSdk = 34
    defaultConfig {
        applicationId = "kr.moborn.hud.phone"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // 빌드할 때마다 서명이 달라지면 덮어쓰기 설치가 안 되므로 저장소의 디버그 키를 쓴다.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    lint { abortOnError = false }
}
dependencies { implementation(project(":shared")) }
