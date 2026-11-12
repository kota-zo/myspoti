// Spotify implementation of :api. Plain JVM: it talks to the Web Player page through a
// BridgeHost (Android WebView or desktop Chrome) and to Spotify's HTTP API directly.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":api"))
    // The native engine as a listen-sync page (EngineSyncPage): a mirror with no Web Player page.
    api(project(":engine"))
    api(libs.serialization.json)
    implementation(libs.okhttp)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // Virtual time for the listen sync's timers.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test-jvm:1.11.0")
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}
