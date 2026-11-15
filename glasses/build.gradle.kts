plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "kr.moborn.hud.glasses"
    compileSdk = 34
    defaultConfig {
        applicationId = "kr.moborn.hud.glasses"
        minSdk = 26
        // 30으로 고정: 안드로이드 12 이상 기기에서 블루투스 권한 창 없이 연결하기 위함
        // (안경에서는 권한 창을 누르기 어렵다).
        targetSdk = 30
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
