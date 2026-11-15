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
        versionCode = 2
        versionName = "0.2.0"
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
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric이 안드로이드 런타임을 받을 저장소 (Maven Central의 구글 미러)
                it.systemProperty(
                    "robolectric.dependency.repo.url",
                    "https://maven-central.storage-download.googleapis.com/maven2",
                )
            }
        }
    }
}
dependencies {
    implementation(project(":shared"))
    // 안경 없이 HUD 화면을 PNG로 그려 보는 테스트용
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
