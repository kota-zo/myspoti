// Native playback core: the Web Player's playback protocol (device registration, track
// resolution, Widevine licence transport, track-playback state reports, melody play reports)
// in plain Kotlin, with no Web Player page. Platforms plug in an HTTP client, a WebSocket, a
// token source and a small DRM player (see docs/api/NATIVE-PLAYBACK.md).
//
// Kotlin Multiplatform with only a jvm() target for now: every source is in commonMain, so
// android/iOS/native targets can be added later without moving code. The jvm variant is
// consumable by an Android module as it is.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
        jvmTest.dependencies {
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            implementation(libs.coroutines.test)
            runtimeOnly(libs.junit.launcher)
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
