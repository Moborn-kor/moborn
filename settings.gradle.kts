pluginManagement {
    repositories {
        google()
        // Maven Central의 구글 공식 미러. 본 서버가 요청을 제한할 때를 대비한다.
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Maven Central의 구글 공식 미러. 본 서버가 요청을 제한할 때를 대비한다.
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
        gradlePluginPortal()
    }
}
rootProject.name = "moborn-hud"
include(":shared", ":phone", ":glasses")
